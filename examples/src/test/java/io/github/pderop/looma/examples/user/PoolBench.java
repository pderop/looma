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
package io.github.pderop.looma.examples.user;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Random;
import java.util.StringJoiner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.infra.ThreadParams;
import org.openjdk.jmh.runner.IterationType;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Message;
import io.github.pderop.looma.examples.utils.ClientAffinity;

import static io.github.pderop.looma.Placement.carrier;
import static io.github.pderop.looma.SpawnOptions.placedOn;

/**
 * Compares blocking connection pools under an open load.
 *
 * <p>
 * There is one actor per carrier, and each actor has its own pool of
 * connections to {@link BackendServer}. A request goes like this:
 *
 * <pre>
 * client             tells one actor, at the planned time
 * actor              starts a virtual thread
 * virtual thread     takes a connection, does a GET, gives the connection back
 * actor              does some CPU work, then answers the client
 * </pre>
 *
 * <p>
 * The {@code pool} parameter chooses the pool. All of them hold the same
 * connections:
 *
 * <ul>
 * <li>{@code lbd-lifo}: a {@link LinkedBlockingDeque}, the connection given
 * back last goes first.
 * <li>{@code abq}: an {@link ArrayBlockingQueue}, first in first out.
 * <li>{@code abq-fair}: the same queue, created fair.
 * <li>{@code fair-lifo}: {@link FairPool}, the connection given back last goes
 * first.
 * <li>{@code fair-fifo}: {@link FairPool}, first in first out.
 * </ul>
 *
 * <p>
 * The clients and the ramp are those of {@link UserBench}. The rate goes up
 * during the warm-up. With {@code rampStep}, each measured iteration adds that
 * rate, and the fork stops at the first one not kept up with.
 *
 * <p>
 * Each iteration prints one {@code STEP} line, with the time spent waiting for
 * a connection. It also prints one {@code TIMELINE} line per 100 ms: per pool,
 * the requests waiting, the requests answered and the free connections.
 *
 * <p>
 * Run it with {@code scripts/jmh-pool.sh}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 4, time = 5)
@Measurement(iterations = 3, time = 10)
@Threads(4)
@Fork(1)
public class PoolBench {

	/** The most requests a client may wait for. The value of LoadGenerator. */
	private static final int MAX_IN_FLIGHT = 1000;

	/** The largest latency recorded. */
	private static final long HIGHEST_NANOS = TimeUnit.SECONDS.toNanos(60);

	/** A call gives up after skipping sends for this long. */
	private static final long LONGEST_CALL_NANOS = TimeUnit.SECONDS.toNanos(1);

	/** The time between two lines of the timeline. */
	private static final long TIMELINE_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

	@State(Scope.Benchmark)
	public static class Server {

		@Param({"mpsc-unpinned-no-affinity", "jdk"})
		public String scheduler;

		@Param({"450000"})
		public long rate;

		/** Added to the rate at each measured iteration. 0 keeps the same rate. */
		@Param({"0"})
		public long rampStep;

		@Param({"lbd-lifo", "abq", "abq-fair", "fair-lifo", "fair-fifo"})
		public String pool;

		/** The connections of one pool. */
		@Param({"8"})
		public int connections;

		/** The CPU work of the actor for each answer, in microseconds. */
		@Param({"8"})
		public int workMicros;

		final Recorder latency = new Recorder(HIGHEST_NANOS, 3);

		final Recorder takeWait = new Recorder(HIGHEST_NANOS, 3);

		final Recorder sendLag = new Recorder(HIGHEST_NANOS, 3);

		final Queue<Client> clients = new ConcurrentLinkedQueue<>();

		private final Queue<String> timeline = new ConcurrentLinkedQueue<>();

		private volatile boolean recording = true;

		/** The time between two requests of one client. Read at every request. */
		volatile long interval;

		final List<ActorRef> groups = new ArrayList<>();

		final List<Pool> pools = new ArrayList<>();

		List<Integer> clientCpus;

		private ActorSystem system;

		private int threads;

		private int warmups;

		private int measures;

		private String phase;

		private long iterationRate;

		private double startUptime;

		private long startNanos;

		private long startCpu;

		private long startClientCpu;

		private long[] answeredAtStart;

		@Setup(Level.Trial)
		public void start(BenchmarkParams params) {
			int carriers = Integer.getInteger("bench.carriers", Runtime.getRuntime().availableProcessors());
			threads = params.getThreads();
			clientCpus = ClientAffinity.available()
					? ClientAffinity.parseCpuList(System.getProperty("bench.clientCpus", ""))
					: List.of();
			if (!clientCpus.isEmpty() && clientCpus.size() != threads) {
				throw new IllegalArgumentException(
						"bench.clientCpus names " + clientCpus.size() + " CPUs, for " + threads + " JMH threads");
			}
			InetSocketAddress backend = new InetSocketAddress("127.0.0.1",
					Integer.getInteger("bench.backendPort", 8081));

			// First, before any virtual thread starts: the JVM picks its scheduler when
			// the first one starts.
			system = ActorSystem.builder().carriers(UserBench.Server.carriers(scheduler, carriers)).build();
			long workNanos = TimeUnit.MICROSECONDS.toNanos(workMicros);
			for (int i = 0; i < carriers; i++) {
				Pool connectionPool = pool(pool, connections);
				pools.add(connectionPool);
				groups.add(system.spawn("group-" + i, () -> new Group(connectionPool, connections, backend, workNanos),
						placedOn(carrier(i % system.carrierCount()))));
			}
			// Answers once every pool has its connections.
			for (ActorRef group : groups) {
				group.ask(new Ready(), Ready.class).join();
			}
			answeredAtStart = new long[carriers];
			// Runs for the whole fork, warm-up included: nothing changes when the
			// measure starts.
			Thread.ofPlatform().daemon().name("timeline").start(this::recordTimeline);
			System.out.printf(Locale.ROOT,
					"%npool bench   %s, pool %s, %d carriers, %d connections per pool,"
							+ " work %d us, %d clients on %s, up to %,d req/s%n",
					scheduler, pool, carriers, connections, workMicros, threads,
					clientCpus.isEmpty() ? "<floating>" : clientCpus, rate);
		}

		@TearDown(Level.Trial)
		public void stop() throws InterruptedException {
			recording = false;
			system.shutdown();
			system.awaitTermination(10, TimeUnit.SECONDS);
		}

		@Setup(Level.Iteration)
		public void startIteration(IterationParams params) {
			boolean warmup = params.getType() == IterationType.WARMUP;
			phase = warmup ? "warmup" : "measure";
			iterationRate = warmup ? Math.max(1, rate * ++warmups / params.getCount()) : rate + rampStep * measures++;
			interval = Math.round(1e9 * threads / iterationRate);
			// Throws away what the time between two iterations left.
			latency.getIntervalHistogram();
			takeWait.getIntervalHistogram();
			sendLag.getIntervalHistogram();
			timeline.clear();
			answeredAtStart = answeredPerGroup();
			startUptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1e3;
			startCpu = processCpu();
			startClientCpu = clientCpu();
			startNanos = System.nanoTime();
		}

		/** Runs once every client has finished its iteration. */
		@TearDown(Level.Iteration)
		public void endIteration() {
			long elapsed = System.nanoTime() - startNanos;
			long serverCpu = (processCpu() - startCpu) - (clientCpu() - startClientCpu);
			double endUptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1e3;

			long requests = 0;
			StringJoiner groupRequests = new StringJoiner(",");
			long[] answeredAtEnd = answeredPerGroup();
			for (int group = 0; group < answeredAtEnd.length; group++) {
				long answered = answeredAtEnd[group] - answeredAtStart[group];
				groupRequests.add(Long.toString(answered));
				requests += answered;
			}
			long sent = 0;
			long dropped = 0;
			long failed = 0;
			long inFlight = 0;
			for (Client client : clients) {
				sent += client.sent;
				dropped += client.dropped;
				failed += client.failed;
				inFlight += client.pending;
				client.sent = 0;
				client.dropped = 0;
				client.failed = 0;
			}
			Histogram latencies = latency.getIntervalHistogram();
			Histogram takes = takeWait.getIntervalHistogram();
			Histogram lags = sendLag.getIntervalHistogram();
			double seconds = elapsed / 1e9;
			// The rules of UserBench.
			boolean keptUp = dropped == 0 && failed == 0 && sent >= 0.99 * iterationRate * seconds
					&& micros(lags, 99) <= 1e6 * threads / iterationRate / 2;
			String names = "scheduler=" + scheduler + " pool=" + pool + " connections=" + connections;
			StringBuilder lines = new StringBuilder(System.lineSeparator());
			for (String line; (line = timeline.poll()) != null;) {
				lines.append("TIMELINE ")
						.append(names)
						.append(" rate=")
						.append(iterationRate)
						.append(" phase=")
						.append(phase)
						.append(' ')
						.append(line)
						.append(System.lineSeparator());
			}
			System.out.print(lines);
			System.out.printf(Locale.ROOT,
					"%nSTEP %s rate=%d phase=%s start_s=%.3f end_s=%.3f sent=%d dropped=%d answered=%d failed=%d"
							+ " in_flight=%d achieved=%.0f server_us_per_req=%.2f server_cores=%.2f p50_us=%.1f"
							+ " p90_us=%.1f p99_us=%.1f p999_us=%.1f max_us=%.1f lag_p99_us=%.1f take_p50_us=%.1f"
							+ " take_p99_us=%.1f take_p999_us=%.1f take_max_us=%.1f group_requests=%s kept_up=%s%n",
					names, iterationRate, phase, startUptime, endUptime, sent, dropped, latencies.getTotalCount(),
					failed, inFlight, requests / seconds, serverCpu / 1e3 / Math.max(1, requests),
					serverCpu / (double) elapsed, micros(latencies, 50), micros(latencies, 90), micros(latencies, 99),
					micros(latencies, 99.9), latencies.getMaxValue() / 1e3, micros(lags, 99), micros(takes, 50),
					micros(takes, 99), micros(takes, 99.9), takes.getMaxValue() / 1e3, groupRequests,
					keptUp ? "yes" : "no");
			// A ramp ends at the first step not kept up with. JMH cannot end a fork
			// early, so this JVM stops here. The STEP line is already printed.
			if (rampStep > 0 && phase.equals("measure") && !keptUp) {
				System.out.flush();
				Runtime.getRuntime().halt(0);
			}
		}

		/**
		 * Every 100 ms, keeps one line. Per pool: the requests waiting, the requests
		 * answered since the last line, and the free connections.
		 */
		private void recordTimeline() {
			int count = groups.size();
			long[] last = new long[count];
			while (recording) {
				LockSupport.parkNanos(TIMELINE_NANOS);
				long[] sent = new long[count];
				long[] answered = new long[count];
				for (Client client : clients) {
					for (int group = 0; group < count; group++) {
						// Answered first: a request counted as answered is then always counted as
						// sent.
						answered[group] += client.answeredPerGroup.getOpaque(group);
						sent[group] += client.sentPerGroup.getOpaque(group);
					}
				}
				StringJoiner waiting = new StringJoiner(",");
				StringJoiner done = new StringJoiner(",");
				StringJoiner free = new StringJoiner(",");
				for (int group = 0; group < count; group++) {
					waiting.add(Long.toString(sent[group] - answered[group]));
					done.add(Long.toString(answered[group] - last[group]));
					free.add(Integer.toString(pools.get(group).free()));
					last[group] = answered[group];
				}
				timeline.add("t_s=" + ManagementFactory.getRuntimeMXBean().getUptime() / 1e3 + " waiting=" + waiting
						+ " answered=" + done + " free=" + free);
			}
		}

		/** The requests answered so far, per group. */
		private long[] answeredPerGroup() {
			long[] answered = new long[groups.size()];
			for (Client client : clients) {
				for (int group = 0; group < answered.length; group++) {
					answered[group] += client.answeredPerGroup.getOpaque(group);
				}
			}
			return answered;
		}

		private long clientCpu() {
			ThreadMXBean mx = ManagementFactory.getThreadMXBean();
			if (!mx.isThreadCpuTimeSupported()) {
				return 0;
			}
			long total = 0;
			for (Client client : clients) {
				if (client.thread != null) {
					total += Math.max(0, mx.getThreadCpuTime(client.thread.threadId()));
				}
			}
			return total;
		}

		private static long processCpu() {
			return ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toNanos();
		}

		private static double micros(Histogram histogram, double percentile) {
			return histogram.getValueAtPercentile(percentile) / 1e3;
		}
	}

	/** One client: a JMH thread and its schedule. */
	@State(Scope.Thread)
	public static class Client {

		final Answers answers = new Answers();

		/** Written by this client only. Read by the timeline. */
		AtomicLongArray sentPerGroup;

		/** Written by this client only. Read by the timeline. */
		AtomicLongArray answeredPerGroup;

		Random random;

		boolean spin;

		long next;

		long pending;

		long sent;

		long dropped;

		long failed;

		/** Read by the thread that prints the iteration. */
		volatile Thread thread;

		@Setup(Level.Trial)
		public void start(Server server, ThreadParams params) {
			random = new Random(params.getThreadIndex());
			spin = !server.clientCpus.isEmpty();
			sentPerGroup = new AtomicLongArray(server.groups.size());
			answeredPerGroup = new AtomicLongArray(server.groups.size());
			server.clients.add(this);
		}

		/**
		 * Starts the schedule from now. Without this, the time between two iterations
		 * would come out as a burst of requests.
		 */
		@Setup(Level.Iteration)
		public void startIteration(Server server, ThreadParams params) {
			if (spin) {
				ClientAffinity.pinCurrentThread(server.clientCpus.get(params.getThreadIndex()));
			}
			thread = Thread.currentThread();
			next = System.nanoTime();
		}

		/**
		 * Waits for the time of the next request, and counts the answers in the
		 * meantime.
		 */
		void awaitSchedule(Server server) {
			pending -= count(server, answers.takeAll());
			long wait;
			while ((wait = next - System.nanoTime()) > 0) {
				if (!spin && wait > 50_000) {
					LockSupport.parkNanos(wait - 50_000);
				} else {
					Thread.onSpinWait();
				}
			}
		}

		private int count(Server server, Request answered) {
			int n = 0;
			for (Request request = answered; request != null; request = request.next) {
				increment(answeredPerGroup, request.group);
				if (request.failed) {
					failed++;
				} else {
					server.latency.recordValue(clamp(request.answeredAt - request.scheduled));
					server.takeWait.recordValue(clamp(request.tookAt - request.takeStartedAt));
				}
				n++;
			}
			return n;
		}
	}

	/** One op: waits for the next planned time that can be sent, and sends. */
	@Benchmark
	public void request(Server server, Client client) {
		long first = client.next;
		while (true) {
			client.awaitSchedule(server);
			long scheduled = client.next;
			client.next += server.interval;
			if (client.pending < MAX_IN_FLIGHT) {
				send(server, client, scheduled);
				return;
			}
			client.dropped++;
			// JMH can only end the iteration when this call returns.
			if (scheduled - first > LONGEST_CALL_NANOS) {
				return;
			}
		}
	}

	private static void send(Server server, Client client, long scheduled) {
		client.sent++;
		server.sendLag.recordValue(clamp(System.nanoTime() - scheduled));
		client.pending++;
		int group = client.random.nextInt(server.groups.size());
		increment(client.sentPerGroup, group);
		server.groups.get(group).tell(new Call(new Request(scheduled, group, client.answers)));
	}

	/** For a counter with one writer: no atomic instruction is needed. */
	private static void increment(AtomicLongArray counters, int index) {
		counters.setRelease(index, counters.getPlain(index) + 1);
	}

	private static long clamp(long nanos) {
		return Math.min(Math.max(0, nanos), HIGHEST_NANOS);
	}

	private static Pool pool(String name, int connections) {
		return switch (name) {
			case "lbd-lifo" -> new LinkedDequePool();
			case "abq" -> new ArrayQueuePool(connections, false);
			case "abq-fair" -> new ArrayQueuePool(connections, true);
			case "fair-lifo" -> new FairPool(true);
			case "fair-fifo" -> new FairPool(false);
			default -> throw new IllegalArgumentException(
					"pool must be lbd-lifo, abq, abq-fair, fair-lifo or fair-fifo, not '" + name + "'");
		};
	}

	/** A set of connections, shared by virtual threads. */
	interface Pool {

		/**
		 * Waits for a free connection. A pool can use {@code request} to wait, so it
		 * allocates nothing.
		 */
		Upstream take(Request request) throws InterruptedException;

		void give(Upstream connection) throws InterruptedException;

		/** The free connections now. */
		int free();
	}

	/** The pool the benchmarks used before their actors owned the connections. */
	private static final class LinkedDequePool implements Pool {

		private final LinkedBlockingDeque<Upstream> idle = new LinkedBlockingDeque<>();

		@Override
		public Upstream take(Request request) throws InterruptedException {
			return idle.takeFirst();
		}

		@Override
		public void give(Upstream connection) {
			idle.addFirst(connection);
		}

		@Override
		public int free() {
			return idle.size();
		}
	}

	private static final class ArrayQueuePool implements Pool {

		private final ArrayBlockingQueue<Upstream> idle;

		ArrayQueuePool(int connections, boolean fair) {
			this.idle = new ArrayBlockingQueue<>(connections, fair);
		}

		@Override
		public Upstream take(Request request) throws InterruptedException {
			return idle.take();
		}

		@Override
		public void give(Upstream connection) throws InterruptedException {
			idle.put(connection);
		}

		@Override
		public int free() {
			return idle.size();
		}
	}

	/**
	 * A fair pool. The thread that waits the longest gets the next connection.
	 *
	 * <p>
	 * {@link #give} puts the connection directly in the waiting thread, and wakes
	 * it. That thread does not need the lock again. So a thread that arrives later
	 * cannot take this connection first.
	 *
	 * <p>
	 * The free connections can go out in last-in first-out order or in first-in
	 * first-out order. Interrupting a waiting thread is not supported: nothing
	 * interrupts them in this benchmark.
	 */
	private static final class FairPool implements Pool {

		private final ReentrantLock lock = new ReentrantLock();

		private final ArrayDeque<Upstream> free = new ArrayDeque<>();

		private final ArrayDeque<Request> waiting = new ArrayDeque<>();

		private final boolean lifo;

		FairPool(boolean lifo) {
			this.lifo = lifo;
		}

		@Override
		public Upstream take(Request request) {
			lock.lock();
			try {
				Upstream connection = free.pollFirst();
				if (connection != null) {
					return connection;
				}
				request.waiter = Thread.currentThread();
				waiting.addLast(request);
			} finally {
				lock.unlock();
			}
			Upstream connection;
			while ((connection = request.given) == null) {
				LockSupport.park(request);
			}
			return connection;
		}

		@Override
		public void give(Upstream connection) {
			Request first;
			lock.lock();
			try {
				first = waiting.pollFirst();
				if (first == null) {
					if (lifo) {
						free.addFirst(connection);
					} else {
						free.addLast(connection);
					}
					return;
				}
			} finally {
				lock.unlock();
			}
			first.given = connection;
			LockSupport.unpark(first.waiter);
		}

		@Override
		public int free() {
			lock.lock();
			try {
				return free.size();
			} finally {
				lock.unlock();
			}
		}
	}

	/** One actor and its pool. */
	private static final class Group implements Actor {

		private final Pool pool;

		private final int connections;

		private final InetSocketAddress backend;

		private final long workNanos;

		private final List<Upstream> opened = new ArrayList<>();

		private Group(Pool pool, int connections, InetSocketAddress backend, long workNanos) {
			this.pool = pool;
			this.connections = connections;
			this.backend = backend;
			this.workNanos = workNanos;
		}

		@Override
		public void preStart(ActorContext context) throws IOException, InterruptedException {
			for (int i = 0; i < connections; i++) {
				Upstream connection = Upstream.connect(backend);
				opened.add(connection);
				pool.give(connection);
			}
		}

		@Override
		public void postStop(ActorContext context) {
			opened.forEach(Upstream::close);
		}

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) {
			switch (message) {
				case Call call -> get(call.request(), context);
				case Answered answered -> {
					work();
					answered.request().answer();
				}
				case Ready ready -> sender.tell(ready, context.self());
				default -> throw new IllegalStateException("group received unexpected message: " + message);
			}
		}

		private void get(Request request, ActorContext context) {
			ActorRef self = context.self();
			context.vThreadFactory().newThread(() -> {
				try {
					request.takeStartedAt = System.nanoTime();
					Upstream connection = pool.take(request);
					request.tookAt = System.nanoTime();
					try {
						connection.get();
					} catch (IOException | RuntimeException e) {
						// The iteration is then not kept up with. The connection may stay out of
						// step, but the run is already invalid.
						request.failed = true;
					} finally {
						pool.give(connection);
					}
				} catch (InterruptedException e) {
					return; // only at shutdown
				}
				self.tell(new Answered(request), self);
			}).start();
		}

		/** Keeps the carrier busy, like the walk of {@link UserGroupActor}. */
		private void work() {
			long end = System.nanoTime() + workNanos;
			while (System.nanoTime() < end) {
				Thread.onSpinWait();
			}
		}
	}

	private record Call(Request request) implements Message {
	}

	private record Answered(Request request) implements Message {
	}

	private record Ready() implements Message {
	}

	/** One request, from its send to its answer. */
	private static final class Request {

		private final long scheduled;

		private final int group;

		private final Answers answers;

		private long takeStartedAt;

		private long tookAt;

		private long answeredAt;

		private boolean failed;

		/** The thread waiting for a connection in {@link FairPool}. */
		private Thread waiter;

		/** The connection {@link FairPool} gives to {@link #waiter}. */
		private volatile Upstream given;

		private Request next;

		private Request(long scheduled, int group, Answers answers) {
			this.scheduled = scheduled;
			this.group = group;
			this.answers = answers;
		}

		private void answer() {
			answeredAt = System.nanoTime();
			answers.push(this);
		}
	}

	/** The answers of one client: a stack the carriers push onto. */
	private static final class Answers {

		private final AtomicReference<Request> head = new AtomicReference<>();

		void push(Request request) {
			Request top;
			do {
				top = head.get();
				request.next = top;
			} while (!head.compareAndSet(top, request));
		}

		Request takeAll() {
			return head.getAndSet(null);
		}
	}
}
