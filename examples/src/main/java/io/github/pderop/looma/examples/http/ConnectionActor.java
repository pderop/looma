/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.pderop.looma.examples.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.SplittableRandom;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;

/**
 * One connection: reads it, serialises its pipelined requests, runs each
 * request's fork chain over this actor's own state, and writes the responses
 * back in order.
 *
 * <p>
 * <b>This actor's state is the thing being measured.</b> {@link #state} is a
 * byte array owned by this connection alone — no other actor and no other
 * thread ever touches it, so there is no sharing, no lock and no race anywhere
 * in this class. What differs between the two schedulers is simply <em>which
 * core</em> holds those lines. On the carrier-affine implementation this actor
 * always resumes on its home carrier. L1d is 48 KiB on the P-cores this is
 * sized for, so a 64 KiB array does not stay there across a park — the other
 * connections on that carrier flush it. What stays hot is the private L2 (2
 * MiB), provided the carrier's working set still fits. On the JDK builtin
 * scheduler each continuation may resume on any worker, so the lines this actor
 * dirtied on one core have to be snooped away from it by the next — a HITM per
 * line, on state that never needed to move at all. <b>That asymmetry is the
 * entire experiment</b>, and it is the actor model's own promise: an actor's
 * state should stay where the actor runs.
 *
 * <p>
 * <b>Everything below happens on this actor's home carrier.</b> The reader
 * thread comes from {@link ActorContext#vThreadFactory()}, so does every thread
 * in the fork chain, and this actor is a root actor spawned with an explicit
 * carrier ({@link Acceptor}'s
 * {@code placedOn(carrier(Math.floorMod(connectionIndex,
 * carrierCount)))}, not round-robin), so it was assigned one home carrier at
 * spawn time and never leaves it. On the carrier-affine implementation that is
 * one core for the whole lifetime of the connection — socket read, dispatch,
 * state access, socket write. On the JDK builtin scheduler none of those
 * placements is tied to anything, which is the comparison.
 *
 * <p>
 * <b>Reading blocks, and that is on purpose.</b> {@code SocketChannel.read} in
 * blocking mode parks the virtual thread through Loom's poller and releases the
 * carrier. An NIO {@code Selector} would do the opposite — {@code select()} is
 * not a park point and blocks the carrier thread outright — which is why this
 * example has no selector anywhere.
 *
 * <p>
 * <b>The shape of one request.</b> The actor walks its own state along a
 * pointer chase, modifying every line of it, copies it into this connection's
 * {@link #snapshot} buffer, and hands that buffer to a fresh virtual thread
 * which follows the same chase through the copy and blocks on the upstream
 * call. The answer comes back as a message, so the actor modifies its state
 * again and forks one last virtual thread to write the response. The snapshot
 * is what makes this race-free without a single synchronisation primitive: the
 * actor keeps mutating its own array while the forked thread reads a copy
 * nobody else can see. It also makes the handoff itself measurable — the
 * snapshot is written on the actor's core and read on whichever core the forked
 * thread lands on, so its lines are dirty and their transfer is a HITM exactly
 * when the scheduler moved the continuation.
 *
 * <p>
 * <b>The snapshot buffer is allocated once per connection and overwritten by
 * every request, never reallocated.</b> A fresh array per request would make
 * this an allocation benchmark: at the rates this server reaches,
 * {@code stateKiB} of garbage per request is gigabytes per second, and the
 * young collections that follow sweep the very cache lines whose residency is
 * being measured — on both schedulers equally, which flattens the comparison.
 * Reusing one buffer keeps the per-request work identical (the copy still
 * dirties every line of it on the actor's core) while taking the collector out
 * of the measured path.
 *
 * <p>
 * <b>The read is load-bearing, not decoration.</b> What the forked thread reads
 * out of the snapshot comes back in {@code UpstreamDone} and is folded into the
 * actor's next modification, so no part of the chain can be optimised away and
 * the request carries a genuine data dependency across the park — which is what
 * a real request/response does.
 *
 * <p>
 * <b>Each request makes one real blocking HTTP call to {@link MockUpstream},
 * then decodes its JSON body and re-encodes it</b>, rather than sleeping for a
 * simulated think time. A timed park resumes from the JDK's timer queue and
 * never touches the per-carrier sub-poller, which is the path the locality
 * argument actually rests on; a socket round trip does, and it lets the carrier
 * discover its own ready fds in its own poll. The client is Apache HttpClient 5
 * classic, i.e. blocking {@code java.net.Socket} I/O, which on a virtual thread
 * parks through the same poller as the read above.
 *
 * <p>
 * <b>The decode/re-encode is the CPU half of a request, and it is the same one
 * the upstream project's {@code HandoffHttpServer} does</b>: the body comes
 * back as JSON, Jackson turns it into a {@link FruitsResponse}, and that object
 * graph is written back out as the bytes this connection answers with. Without
 * it a request would be pure I/O wait — the scheduler would be compared on
 * nothing but its parking, and the response would carry no backend data at all.
 * It runs on the forked virtual thread, never in {@code onReceive}: it is
 * bounded work, but work in the mailbox loop is work this connection's other
 * requests are queued behind.
 *
 * <p>
 * <b>One upstream connection per connection actor, never a shared pool.</b>
 * {@link BasicHttpClientConnectionManager} holds exactly one, and the client is
 * private to this actor — so the upstream fd is opened by, and stays registered
 * on, this connection's home carrier. A pool shared across connections would
 * put cross-carrier wakeups back into the very path being measured; it is the
 * same rule that keeps this actor's state carrier-private.
 *
 * <p>
 * <b>Response ordering.</b> HTTP/1.1 pipelining requires responses in request
 * order, but the mailbox hands over request k+1 as soon as {@code onReceive}
 * returns, while k's fork chain is still running. So exactly one chain is
 * allowed in flight ({@link #busy}) and the rest wait in {@link #stash}. Two
 * things fall out of that rule: ordering is guaranteed without any sequence
 * tracking, and only one thread can ever be writing to the socket, which is
 * what lets the response be written from the last forked thread rather than
 * from {@code onReceive} — blocking inside {@code onReceive} would hold this
 * actor's gate and stall its own mailbox.
 *
 * <p>
 * The consequence to keep in mind when sizing a run: per-connection concurrency
 * is 1, so achievable throughput is capped at {@code connections / upstreamRtt}
 * — a run whose measured throughput sits on that ceiling measured the client's
 * concurrency, not the server.
 */
final class ConnectionActor implements Actor {

	private static final int READ_BUFFER_BYTES = 16 * 1024;

	/**
	 * Shared by every connection on every carrier, like the
	 * {@code HandoffHttpServer} this mirrors. An {@code ObjectMapper} is
	 * thread-safe and, once warm, its (de)serializer caches are read-only — shared
	 * clean lines cost nothing to read from several cores. One mapper per
	 * connection would instead give each of the hundreds of connections its own
	 * cache to warm and to keep resident, which is cache footprint competing with
	 * the very actor state being measured.
	 */
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	/**
	 * What a request answers with when the upstream call or its decode failed.
	 * Empty rather than canned data, and deliberately not a 200 with fruits in it:
	 * a run whose bodies are all empty is a run whose backend was down, and that
	 * has to stay visible in the client's output. {@link HttpBenchMain} probes the
	 * mock before binding for the same reason.
	 */
	private static final byte[] UPSTREAM_FAILED_BODY = "{\"fruits\":[]}".getBytes(StandardCharsets.US_ASCII);

	/**
	 * The handler must wait for the backend for as long as it takes: a timeout
	 * firing mid-run would turn a scheduling stall into an error count instead of
	 * into the latency it actually is.
	 */
	private static final RequestConfig NO_TIMEOUT = RequestConfig.custom().setResponseTimeout(Timeout.DISABLED).build();

	/** One cache line on every CPU this benchmark targets. */
	private static final int LINE_BYTES = 64;

	/**
	 * The state is an {@code int[]} walked one cache line at a time, so this is the
	 * stride.
	 *
	 * <p>
	 * A Java array's elements begin after its header, so a 64-byte stride is not
	 * guaranteed to be 64-byte <em>aligned</em>: a logical line may straddle two
	 * physical ones. That doubles the lines actually touched, identically under
	 * both schedulers, so it costs a little absolute precision and nothing at all
	 * in the comparison.
	 */
	private static final int LINE_INTS = LINE_BYTES / Integer.BYTES;

	/**
	 * Slot within a line holding the index of the next line to visit — see
	 * {@link #modifyState(int)} for why the walk is threaded through the data
	 * instead of being drawn from a generator.
	 */
	private static final int NEXT = 0;

	/**
	 * Slot within a line holding the value actually read and modified. It shares
	 * the line with {@link #NEXT}, so touching it costs no second miss.
	 */
	private static final int PAYLOAD = 1;

	private final SocketChannel channel;

	private final int stateKiB;

	/**
	 * Seeded from the connection's index, so a run is reproducible and two
	 * co-resident connections do not walk their arrays in the same order. Used
	 * once, in {@link #preStart}, to lay out this connection's chase; the walk
	 * itself needs no generator at all.
	 */
	private final SplittableRandom random;

	private final URI upstream;

	private final Set<SocketChannel> openChannels;

	private final ArrayDeque<HttpRequest> stash = new ArrayDeque<>();

	private CloseableHttpClient httpClient;

	/**
	 * This connection's private state. Allocated in {@link #preStart}, i.e. by a
	 * thread already running on this actor's home carrier, so its pages are first
	 * touched by the core that will spend the rest of the run reading and writing
	 * them.
	 */
	private int[] state;

	/**
	 * The copy of {@link #state} handed to the request's forked virtual thread.
	 * Allocated once in {@link #preStart}, alongside the state it mirrors, and
	 * overwritten in place by every {@link #callUpstream}.
	 *
	 * <p>
	 * Reused rather than reallocated for the reason given in the class Javadoc, and
	 * safe to reuse because at most one fork chain is in flight per connection
	 * ({@link #busy}): the thread that read it last is the one whose
	 * {@code UpstreamDone} brought the actor back here, so its read is over and
	 * published before the next copy overwrites it.
	 */
	private int[] snapshot;

	/** {@code state.length / LINE_INTS}; {@code 0} in the control run. */
	private int lines;

	/**
	 * Where the next walk of {@link #state} picks the chase up. Carried across
	 * requests rather than reset, so nothing about the walk is constant from the
	 * JIT's point of view.
	 */
	private int chaseCursor;

	private boolean busy;

	ConnectionActor(SocketChannel channel, int connectionIndex, int stateKiB, URI upstream,
			Set<SocketChannel> openChannels) {
		this.channel = channel;
		this.stateKiB = stateKiB;
		this.random = new SplittableRandom(connectionIndex);
		this.upstream = upstream;
		this.openChannels = openChannels;
	}

	@Override
	public void preStart(ActorContext context) {
		/*
		 * Built here, but deliberately not connected here:
		 * BasicHttpClientConnectionManager opens its socket lazily, on the first
		 * execute() -- which runs on a forked virtual thread from this actor's factory,
		 * i.e. on the home carrier. NEG_ONE_MILLISECOND keeps that one connection alive
		 * for the whole run, so a request measures a round trip and not a TCP
		 * handshake.
		 */
		httpClient = HttpClientBuilder.create().setConnectionManager(new BasicHttpClientConnectionManager())
				.setConnectionManagerShared(false).setDefaultRequestConfig(NO_TIMEOUT)
				.setKeepAliveStrategy((response, context1) -> TimeValue.NEG_ONE_MILLISECOND).build();

		state = new int[stateKiB * 1024 / Integer.BYTES];
		snapshot = new int[state.length];
		lines = state.length / LINE_INTS;
		layOutChase();

		ActorRef self = context.self();
		context.vThreadFactory().newThread(() -> readLoop(self)).start();
	}

	@Override
	public void onReceive(Message message, ActorRef sender, ActorContext context) {
		switch (message) {
			case HttpRequest request -> {
				if (busy) {
					stash.add(request);
				} else {
					begin(context);
				}
			}

			case UpstreamDone done -> {
				/*
				 * Back on the actor: on its home carrier under the affine scheduler, on
				 * whichever worker picked the mailbox up under the JDK builtin. The checksum
				 * the forked thread computed is folded into this modification, so the read it
				 * did cannot be dead code.
				 */
				modifyState(done.checksum());
				respond(context, done.body());
			}

			case RequestDone ignored -> {
				busy = false;
				if (stash.poll() != null) {
					begin(context);
				}
			}

			case ConnectionClosed ignored -> {
				closeChannel();
				context.stop(context.self());
			}

			default -> throw new IllegalStateException("connection actor received unexpected event: " + message);
		}
	}

	@Override
	public void postStop(ActorContext context) {
		closeChannel();
		closeHttpClient();
	}

	/**
	 * Reads this connection until it closes, telling the actor one
	 * {@link HttpRequest} per complete request head. One read can yield several —
	 * that is exactly what a pipelining client produces.
	 */
	private void readLoop(ActorRef self) {
		/*
		 * Allocated here rather than in the constructor so the buffer is first touched
		 * by a thread already running on this connection's home carrier.
		 */
		ByteBuffer readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_BYTES);
		Http1Codec codec = new Http1Codec();

		try {
			while (true) {
				readBuffer.clear();
				int read = channel.read(readBuffer);
				if (read < 0) {
					break;
				}
				readBuffer.flip();
				int requests = codec.feed(readBuffer);
				for (int i = 0; i < requests; i++) {
					self.tell(new HttpRequest(), self);
				}
			}
		} catch (IOException e) {
			// Client went away, or the channel was closed under us at shutdown. Either
			// way the connection is over; the actor stops itself below.
		}

		self.tell(new ConnectionClosed(), self);
	}

	private void begin(ActorContext context) {
		busy = true;
		modifyState(0);
		callUpstream(context);
	}

	/**
	 * Hands a snapshot of this actor's state to a fresh virtual thread, which reads
	 * it and blocks on the upstream call.
	 *
	 * <p>
	 * A fresh thread for the call, rather than making it from {@code onReceive}, is
	 * deliberate: creating a thread from {@link ActorContext#vThreadFactory()}
	 * <em>is</em> the carrier-affine placement primitive, so every request
	 * exercises it. The block in the middle is what gives a scheduler the
	 * opportunity to move the continuation at all — without it both implementations
	 * would be measuring the same placement.
	 *
	 * <p>
	 * The copy is taken here, on the actor, so it is written by the actor's core
	 * and read by whichever core the forked thread runs on. Under the affine
	 * scheduler those are the same core and the snapshot is already hot; under the
	 * JDK builtin they usually are not, and every line of it is dirty.
	 */
	private void callUpstream(ActorContext context) {
		int[] snapshot = this.snapshot;
		System.arraycopy(state, 0, snapshot, 0, state.length);
		ActorRef self = context.self();
		context.vThreadFactory().newThread(() -> {
			int checksum = readSnapshot(snapshot);
			byte[] body = fetchUpstream();
			self.tell(new UpstreamDone(body, checksum), self);
		}).start();
	}

	/**
	 * The last fork of a request: writes the response from a virtual thread rather
	 * than from {@code onReceive}, because blocking inside {@code onReceive} would
	 * hold this actor's gate and stall its own mailbox.
	 */
	private void respond(ActorContext context, byte[] body) {
		ActorRef self = context.self();
		context.vThreadFactory().newThread(() -> {
			writeResponse(body);
			self.tell(new RequestDone(), self);
		}).start();
	}

	/**
	 * Threads a single random cycle through every line of {@link #state}: the
	 * {@link #NEXT} slot of each line holds the index of the line to visit after
	 * it, so following those indices {@link #lines} times visits every line exactly
	 * once and comes back to where it started. Built once per connection, from
	 * {@link #random}, so two co-resident connections walk different orders and a
	 * given run is reproducible.
	 *
	 * <p>
	 * A single cycle, rather than a random index per step, is what makes the walk a
	 * <em>chase</em> — see {@link #modifyState(int)}.
	 */
	private void layOutChase() {
		int n = lines;
		int[] order = new int[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
		}

		// Fisher-Yates: every permutation equally likely, so the cycle is a genuine
		// random order and not a stride some prefetcher could learn.
		for (int i = n - 1; i > 0; i--) {
			int j = random.nextInt(i + 1);
			int swapped = order[i];
			order[i] = order[j];
			order[j] = swapped;
		}

		for (int i = 0; i < n; i++) {
			state[order[i] * LINE_INTS + NEXT] = order[(i + 1) % n];
		}
	}

	/**
	 * Walks every line of {@link #state} once, leaving each one dirty, folding in
	 * what the forked thread read back.
	 *
	 * <p>
	 * <b>Why the next line is read out of the current one, instead of drawn from a
	 * generator.</b> A CPU does not wait for one load at a time — it keeps a dozen
	 * or so in flight at once. But it can only do that when it knows the addresses
	 * in advance, and an address drawn from a random number generator <em>is</em>
	 * known in advance, because computing it touches no memory. Written that way,
	 * four accesses cost one memory latency instead of four:
	 *
	 * <pre>
	 * index drawn from a generator      index read out of the line itself
	 * ----------------------------      ---------------------------------
	 * t=0    issue load of line 2       t=0     issue load of line 0
	 * t=0    issue load of line 0       t=30ns  arrives, holds 3 -&gt; issue line 3
	 * t=0    issue load of line 3       t=60ns  arrives, holds 1 -&gt; issue line 1
	 * t=0    issue load of line 1       t=90ns  arrives, holds 2 -&gt; issue line 2
	 * t=30ns all four have arrived      t=120ns arrives
	 * </pre>
	 *
	 * <p>
	 * That overlap is fatal here, because the entire experiment is a difference in
	 * per-line latency: roughly 5 ns when the line is already in this core's cache,
	 * against roughly 30 ns when it has to be snooped away from another core. Over
	 * the 1024 lines of a default 64 KiB state, per call:
	 *
	 * <pre>
	 *                                  home core   foreign core   difference
	 * a dozen loads in flight             0.4 us         2.6 us         2 us
	 * one dependent load at a time          5 us          31 us        26 us
	 * </pre>
	 *
	 * <p>
	 * Two microseconds inside a request that also waits on a backend is not
	 * measurable; twenty-six is. So the walk is a <b>pointer chase</b>: each line
	 * carries the index of the next one ({@link #layOutChase()}), and the loop
	 * below cannot compute an address until the previous load has come back. The
	 * value it modifies lives in the same line as that index, so the
	 * read-modify-write costs no second miss.
	 *
	 * <p>
	 * <b>The order is random, never sequential</b>, for a second and independent
	 * reason: a contiguous scan is exactly what a hardware prefetcher is built to
	 * hide. The L2 streamer would detect an ascending stride and fetch lines — and
	 * the read-for-ownership requests that go with them — far enough ahead to erase
	 * the very difference the chase exists to expose.
	 *
	 * <p>
	 * <b>Read-modify-write, never a plain read</b>, so each visited line is left
	 * <em>dirty</em>: a core that visits it next must take it away rather than
	 * merely share it, which is the coherence cost this benchmark exists to expose.
	 *
	 * <p>
	 * Called only from {@code onReceive}, so {@link #state} is touched by one
	 * thread at a time and needs no synchronisation of any kind.
	 */
	private void modifyState(int mix) {
		int[] local = state;
		int n = lines;
		int delta = 1 + mix;
		int cursor = chaseCursor;
		for (int i = 0; i < n; i++) {
			cursor = local[cursor * LINE_INTS + NEXT];
			local[cursor * LINE_INTS + PAYLOAD] += delta;
		}
		chaseCursor = cursor;
	}

	/**
	 * Follows the same chase through the snapshot, reading one value per line, and
	 * returns their sum — which the actor folds into its next modification, so this
	 * read cannot be dead code. See {@link #modifyState(int)} for why the walk is a
	 * chase, and the class Javadoc for why the result must reach the actor.
	 *
	 * <p>
	 * The chase was laid out in {@link #state} and copied along with everything
	 * else, so the snapshot carries it for free and this method needs no generator
	 * of its own — one less thing shared across the park.
	 */
	private static int readSnapshot(int[] snapshot) {
		int n = snapshot.length / LINE_INTS;
		if (n == 0) {
			return 0;
		}
		int cursor = 0;
		int sum = 0;
		for (int i = 0; i < n; i++) {
			cursor = snapshot[cursor * LINE_INTS + NEXT];
			sum += snapshot[cursor * LINE_INTS + PAYLOAD];
		}
		return sum;
	}

	/**
	 * One blocking GET against {@link MockUpstream}: the JSON body is decoded into
	 * a {@link FruitsResponse} and immediately re-encoded, and those bytes are what
	 * the client is eventually answered with.
	 *
	 * <p>
	 * Consuming the entity in full is not politeness: leaving bytes unread would
	 * leave the connection unreusable, and the manager would then open a fresh one
	 * per request — turning every request into a TCP handshake against a backend
	 * that only has one connection to give. Jackson stops at the end of the JSON
	 * document, so the explicit {@code consumeQuietly} after it is what guarantees
	 * the stream reached its end.
	 *
	 * <p>
	 * An upstream failure is swallowed rather than propagated: the alternative
	 * would be to kill the connection actor and silently shrink the connection
	 * count mid-run, which is worse than a request that took the round trip and got
	 * {@link #UPSTREAM_FAILED_BODY} back.
	 *
	 * @return the re-encoded JSON body, never {@code null}
	 */
	private byte[] fetchUpstream() {
		try {
			try (CloseableHttpResponse response = httpClient.execute(new HttpGet(upstream))) {
				HttpEntity entity = response.getEntity();
				if (entity == null) {
					throw new IOException("upstream " + upstream + " answered without a body");
				}
				try (InputStream content = entity.getContent()) {
					FruitsResponse fruits = OBJECT_MAPPER.readValue(content, FruitsResponse.class);
					return OBJECT_MAPPER.writeValueAsBytes(fruits);
				} finally {
					EntityUtils.consumeQuietly(entity);
				}
			}
		} catch (Exception e) {
			// See above: the client still gets an answer, an empty one.
			return UPSTREAM_FAILED_BODY;
		}
	}

	/**
	 * Safe without synchronization only because at most one chain is in flight per
	 * connection; see the class Javadoc.
	 */
	private void writeResponse(byte[] body) {
		ByteBuffer response = Http1Codec.jsonResponse(body);
		try {
			while (response.hasRemaining()) {
				if (channel.write(response) < 0) {
					break;
				}
			}
		} catch (IOException e) {
			// The reader thread observes the same close and drives the actor's stop.
		}
	}

	private void closeHttpClient() {
		if (httpClient == null) {
			return;
		}
		try {
			httpClient.close();
		} catch (IOException e) {
			// Already gone.
		}
	}

	private void closeChannel() {
		openChannels.remove(channel);
		try {
			channel.close();
		} catch (IOException e) {
			// Already gone.
		}
	}

	/** One complete request head was framed on this connection. */
	private record HttpRequest() implements Message {
	}

	/**
	 * The upstream call came back. Carries the re-encoded body and the checksum the
	 * forked thread read out of its snapshot; the actor folds that checksum into
	 * its next modification.
	 */
	private record UpstreamDone(byte[] body, int checksum) implements Message {
	}

	/** The fork chain finished and the response has been written. */
	private record RequestDone() implements Message {
	}

	/** The reader thread saw end-of-stream or an I/O error. */
	private record ConnectionClosed() implements Message {
	}
}
