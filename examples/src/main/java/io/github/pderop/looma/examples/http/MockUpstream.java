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
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * The backend {@link ConnectionActor} makes its blocking call to: a standalone,
 * dependency-free HTTP/1.1 server that answers every request with a canned JSON
 * body after a fixed delay.
 *
 * <p>
 * <b>Why a real server rather than a sleep.</b> Faking the handler's I/O wait
 * with {@code Thread.sleep} parks the virtual thread on the JDK's
 * <em>timer</em> queue: the continuation is resumed by the timer's unparker,
 * and the per-carrier sub-poller path ({@code jdk.pollerMode=3} → Master-Poller
 * → eventfd → home carrier) is never exercised at all. That path is the one the
 * whole locality argument rests on. A real blocking socket round trip to this
 * server keeps it in the measurement, and makes the carrier observe its own
 * ready fds in its own poll — including the batching that produces.
 *
 * <p>
 * <b>Run it in its own process, on its own cpuset.</b> Co-scheduling it with
 * the carriers would have it preempt them, and the run would then measure that
 * instead of what it claims to. {@code scripts/run-http-bench.sh} starts it
 * under its own {@code taskset}.
 *
 * <pre>
 * java -cp looma-examples.jar:looma-core.jar \
 *     io.github.pderop.looma.examples.http.MockUpstream
 * </pre>
 *
 * <h2>Design</h2>
 *
 * <p>
 * <b>Plain NIO, and the delay never blocks a thread.</b> A reactor owns a
 * {@link Selector} and a FIFO of due responses; the delay is a deadline in that
 * FIFO, not a sleep. A thread-per-connection server sleeping a millisecond per
 * request would need one thread per in-flight request and would become the
 * bottleneck long before the server under test does.
 *
 * <p>
 * <b>The FIFO is sorted because the delay is constant.</b> Deadlines are
 * therefore issued in increasing order, which is also the order HTTP/1.1
 * requires the responses to be written in on a pipelined connection. Making the
 * delay per-request would break both properties at once.
 *
 * <p>
 * <b>Sub-millisecond accuracy costs a bounded spin.</b> {@code select(timeout)}
 * has millisecond granularity, so a 1 ms delay waited on that way lands
 * anywhere in a 2 ms window — jitter of the same magnitude as the delay being
 * simulated. The loop therefore selects with a timeout only while the next
 * deadline is more than a millisecond away, and switches to
 * {@link Selector#selectNow()} inside that last millisecond. That burns a core
 * under load, which is one reason this process gets its own cpuset.
 *
 * <p>
 * <b>At the default {@code mock.thinkMicros=0} that spin never happens.</b>
 * Every response is due the moment its request is read, so the reactor's own
 * pass drains the queue and the next {@code await()} blocks in
 * {@link Selector#select()} with nothing pending. A cpuset of its own is still
 * wanted — this process must not preempt a carrier of the server under test —
 * but it no longer costs a core. Zero is the default because think time is pure
 * dilution of what the benchmark measures: a request costs {@code think + cpu},
 * only {@code cpu} depends on the scheduler, and the server's blocking socket
 * call — the thing this server exists to provide — is made either way.
 *
 * <p>
 * Framing is {@link Http1Codec}'s, i.e. "count {@code CRLFCRLF}" — see its
 * Javadoc for what that deliberately does not do.
 */
public final class MockUpstream {

	private static final ByteBuffer RESPONSE = newResponse();

	private static final int READ_BUFFER_BYTES = 16 * 1024;

	private static final int INITIAL_WRITE_BUFFER_BYTES = 4 * 1024;

	/**
	 * Below this, the reactor spins on {@code selectNow()}; see the class Javadoc.
	 */
	private static final long SPIN_THRESHOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

	private final ServerSocketChannel serverChannel;

	private final Reactor[] reactors;

	private volatile boolean running = true;

	private MockUpstream(int port, int backlog, int threads, long thinkNanos) throws IOException {
		this.serverChannel = ServerSocketChannel.open();
		this.serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
		this.serverChannel.bind(new InetSocketAddress(port), backlog);

		this.reactors = new Reactor[threads];
		for (int i = 0; i < threads; i++) {
			this.reactors[i] = new Reactor(i, thinkNanos);
		}
	}

	public static void main(String[] args) throws Exception {
		int port = Integer.getInteger("mock.port", 8081);
		int threads = Integer.getInteger("mock.threads", 1);
		int thinkMicros = Integer.getInteger("mock.thinkMicros", 0);
		int backlog = Integer.getInteger("mock.backlog", 4096);

		MockUpstream mock = new MockUpstream(port, backlog, threads, TimeUnit.MICROSECONDS.toNanos(thinkMicros));
		Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(mock::stop));
		mock.start();

		System.out.printf("mock upstream listening on port %d -- threads=%d thinkMicros=%d responseBytes=%d%n", port,
				threads, thinkMicros, RESPONSE.remaining());
		mock.acceptLoop();
	}

	private void start() {
		for (Reactor reactor : reactors) {
			reactor.start();
		}
	}

	/**
	 * Accepts on this thread and hands each connection to a reactor round-robin.
	 * Blocking accept on a platform thread: there is nothing here worth a selector,
	 * and nothing here that should share a reactor's loop.
	 */
	private void acceptLoop() {
		long seq = 0;
		while (running) {
			SocketChannel channel;
			try {
				channel = serverChannel.accept();
			} catch (ClosedChannelException e) {
				// stop() closed the listen socket: the only failure that ends this loop.
				return;
			} catch (IOException e) {
				/*
				 * About this accept, not about the listen socket. Ending here would leave the
				 * mock bound and silently refusing to serve anyone new -- and every server
				 * under test blocks on this mock, so the whole campaign would record that as
				 * its own behaviour.
				 */
				if (!running) {
					return;
				}
				System.err.println("mock upstream accept failed, continuing: " + e);
				try {
					TimeUnit.MILLISECONDS.sleep(10);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return;
				}
				continue;
			}

			try {
				channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
				channel.configureBlocking(false);
				reactors[(int) (seq++ % reactors.length)].add(channel);
			} catch (IOException | RuntimeException e) {
				System.err.println("mock upstream dropping a connection it could not hand to a reactor: " + e);
				closeQuietly(channel);
			}
		}
	}

	private static void closeQuietly(SocketChannel channel) {
		try {
			channel.close();
		} catch (IOException e) {
			// Already gone.
		}
	}

	private void stop() {
		running = false;
		try {
			serverChannel.close();
		} catch (IOException e) {
			// Shutting down anyway.
		}
		for (Reactor reactor : reactors) {
			reactor.stop();
		}
	}

	/**
	 * The canned body, byte-for-byte the one the upstream
	 * Netty-VirtualThread-Scheduler benchmark-runner's {@code MockHttpServer}
	 * serves: ten fruits, pretty-printed. Both the shape and the size matter —
	 * {@link ConnectionActor} decodes this into a {@code FruitsResponse} and
	 * re-encodes it on every round, so this is what sets the CPU cost of a round.
	 */
	private static ByteBuffer newResponse() {
		String body = """
				{
				  "fruits": [
				    {"name": "Apple", "color": "Red", "price": 1.20},
				    {"name": "Banana", "color": "Yellow", "price": 0.50},
				    {"name": "Orange", "color": "Orange", "price": 0.80},
				    {"name": "Grape", "color": "Purple", "price": 2.00},
				    {"name": "Mango", "color": "Yellow", "price": 1.50},
				    {"name": "Strawberry", "color": "Red", "price": 3.00},
				    {"name": "Blueberry", "color": "Blue", "price": 4.00},
				    {"name": "Pineapple", "color": "Yellow", "price": 2.50},
				    {"name": "Watermelon", "color": "Green", "price": 5.00},
				    {"name": "Kiwi", "color": "Brown", "price": 1.00}
				  ]
				}""";
		byte[] bodyBytes = body.getBytes(StandardCharsets.US_ASCII);
		String head = "HTTP/1.1 200 OK\r\n" + "Content-Type: application/json\r\n" + "Content-Length: "
				+ bodyBytes.length + "\r\n" + "\r\n";
		byte[] bytes = (head + body).getBytes(StandardCharsets.US_ASCII);
		ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length);
		direct.put(bytes).flip();
		return direct.asReadOnlyBuffer();
	}

	/** One selector, one thread, its own slice of the connections. */
	private final class Reactor implements Runnable {

		private final int index;

		private final long thinkNanos;

		private final Selector selector;

		private final ConcurrentLinkedQueue<SocketChannel> pending = new ConcurrentLinkedQueue<>();

		/** Sorted by construction: see the class Javadoc on the constant delay. */
		private final ArrayDeque<Due> due = new ArrayDeque<>();

		Reactor(int index, long thinkNanos) throws IOException {
			this.index = index;
			this.thinkNanos = thinkNanos;
			this.selector = Selector.open();
		}

		void start() {
			Thread.ofPlatform().name("mock-upstream-" + index).daemon(true).start(this);
		}

		void add(SocketChannel channel) {
			pending.add(channel);
			selector.wakeup();
		}

		/**
		 * Asks this reactor to finish. Deliberately only wakes the selector up and
		 * leaves closing it to the reactor thread itself: {@code running} is already
		 * {@code false} by the time this is called, so the woken loop exits on its next
		 * test and closes in its own {@code finally}. Closing a selector from another
		 * thread while it sits in {@code select()} instead raises an unchecked
		 * {@link ClosedSelectorException} inside the reactor, so every shutdown would
		 * end with a stack trace in the log for something that went entirely to plan.
		 */
		void stop() {
			selector.wakeup();
		}

		@Override
		public void run() {
			try {
				while (running) {
					try {
						await();
						registerPending();
						pollReady();
						completeDue();
					} catch (ClosedSelectorException e) {
						return;
					} catch (IOException e) {
						/*
						 * Only a selector-level failure reaches here now -- the per-connection paths
						 * below each close their own connection and carry on. This one really is fatal
						 * to the reactor, and it takes every connection it owns with it, so it is worth
						 * saying loudly.
						 */
						if (running) {
							System.err.println("mock upstream reactor " + index + " stopping on: " + e);
						}
						return;
					}
				}
			} finally {
				try {
					selector.close();
				} catch (IOException e) {
					// Shutting down anyway.
				}
			}
		}

		/**
		 * Blocks until there is I/O to do or the next response is due — and stops
		 * blocking altogether inside the last millisecond before that deadline.
		 */
		private void await() throws IOException {
			Due next = due.peek();
			if (next == null) {
				selector.select();
				return;
			}
			long remaining = next.deadlineNanos - System.nanoTime();
			if (remaining < SPIN_THRESHOLD_NANOS) {
				selector.selectNow();
			} else {
				selector.select(TimeUnit.NANOSECONDS.toMillis(remaining));
			}
		}

		/**
		 * Registers everything the accept loop handed over since the last pass.
		 *
		 * <p>
		 * A failure here belongs to one connection, not to the reactor: a client that
		 * closed between {@code accept} and this call makes {@code register} throw
		 * {@link ClosedChannelException}, and letting that reach the loop above would
		 * end the reactor and strand every other connection it owns, mid-run. Only
		 * {@link ClosedSelectorException} is the reactor's own, and it is rethrown.
		 */
		private void registerPending() {
			SocketChannel channel;
			while ((channel = pending.poll()) != null) {
				try {
					Conn conn = new Conn(channel);
					conn.key = channel.register(selector, SelectionKey.OP_READ, conn);
				} catch (ClosedSelectorException e) {
					throw e;
				} catch (IOException | RuntimeException e) {
					closeQuietly(channel);
				}
			}
		}

		private void pollReady() {
			Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
			while (keys.hasNext()) {
				SelectionKey key = keys.next();
				keys.remove();
				Conn conn = (Conn) key.attachment();
				try {
					if (key.isReadable()) {
						read(conn);
					}
					if (key.isValid() && key.isWritable()) {
						conn.flush();
					}
				} catch (IOException | CancelledKeyException e) {
					conn.close();
				}
			}
		}

		private void read(Conn conn) throws IOException {
			conn.in.clear();
			int read = conn.channel.read(conn.in);
			if (read < 0) {
				conn.close();
				return;
			}
			conn.in.flip();
			int requests = conn.codec.feed(conn.in);
			long deadline = System.nanoTime() + thinkNanos;
			for (int i = 0; i < requests; i++) {
				due.add(new Due(deadline, conn));
			}
		}

		/**
		 * Writes every response whose delay has elapsed. Stops at the first one that
		 * has not: the FIFO is in deadline order, so nothing later can be due.
		 */
		private void completeDue() {
			long now = System.nanoTime();
			for (Due next = due.peek(); next != null && next.deadlineNanos - now <= 0; next = due.peek()) {
				due.poll();
				try {
					next.conn.enqueueResponse();
				} catch (IOException | CancelledKeyException e) {
					next.conn.close();
				}
			}
		}
	}

	/** A response that becomes writable at {@link #deadlineNanos}. */
	private record Due(long deadlineNanos, Conn conn) {
	}

	/**
	 * One accepted connection: its read buffer, its framing, its pending output.
	 */
	private static final class Conn {

		private final SocketChannel channel;

		private final ByteBuffer in = ByteBuffer.allocateDirect(READ_BUFFER_BYTES);

		private final Http1Codec codec = new Http1Codec();

		private ByteBuffer out = ByteBuffer.allocateDirect(INITIAL_WRITE_BUFFER_BYTES);

		private SelectionKey key;

		Conn(SocketChannel channel) {
			this.channel = channel;
		}

		/**
		 * Appends one canned response and tries to write immediately. Buffering rather
		 * than writing straight to the channel is what makes a partial write, and a
		 * pipelined burst that outruns the socket buffer, both non-events.
		 */
		void enqueueResponse() throws IOException {
			ByteBuffer response = RESPONSE.duplicate();
			ensureCapacity(response.remaining());
			out.put(response);
			flush();
		}

		void flush() throws IOException {
			out.flip();
			try {
				channel.write(out);
			} finally {
				out.compact();
			}
			int interest = out.position() == 0 ? SelectionKey.OP_READ : SelectionKey.OP_READ | SelectionKey.OP_WRITE;
			if (key.interestOps() != interest) {
				key.interestOps(interest);
			}
		}

		private void ensureCapacity(int extra) {
			if (out.remaining() >= extra) {
				return;
			}
			ByteBuffer grown = ByteBuffer.allocateDirect(Math.max(out.capacity() * 2, out.position() + extra));
			out.flip();
			grown.put(out);
			out = grown;
		}

		void close() {
			if (key != null) {
				key.cancel();
			}
			try {
				channel.close();
			} catch (IOException e) {
				// Already gone.
			}
		}
	}
}
