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
package io.github.pderop.looma.examples.servlet;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Random;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

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

import io.github.pderop.looma.examples.utils.ClientAffinity;
import io.github.pderop.looma.examples.utils.LoadGenerator;

/**
 * The servlet benchmark under JMH, at a fixed rate, to run it with the JMH
 * profilers.
 *
 * <p>
 * The backend runs in its own JVM, started first ({@link ServletBackend}). The
 * fork starts the containers as {@link Main} does.
 *
 * <p>
 * Each JMH thread is a client. One op is one request sent. So the profilers
 * that count per op give a count per request. The score is the requests sent
 * per second.
 *
 * <p>
 * The clients follow a fixed schedule, as {@link LoadGenerator} does. A latency
 * starts at the planned send time. A client waits for at most
 * {@link #MAX_IN_FLIGHT} requests. Beyond that, it skips sends and counts them.
 * The run never stops when the server falls behind.
 *
 * <p>
 * During the warm-up iterations, the rate goes up to {@code rate}, one step per
 * iteration. Each iteration prints one line that starts with {@code STEP}. It
 * holds the scheduler and the fraud parameters, the latencies, the CPU, the
 * skipped sends, and the share of the sampled messages of the fraud detectors
 * that ran on another carrier than the message before ({@code migrations_pct}).
 * Each iteration also prints lines that start with {@code TIMELINE}, one per
 * 100 ms: per container, the requests waiting and the requests answered.
 *
 * <p>
 * The properties are those of {@link Main}: {@code servlet.carriers},
 * {@code servlet.clientCpus} (one CPU per JMH thread),
 * {@code servlet.backendPort}, {@code bench.connections} and
 * {@code servlet.sessions}. The fraud detectors take {@code -p fraudKiB} and
 * {@code -p fraudFeatures}.
 *
 * <p>
 * Run it with {@code scripts/jmh-servlet.sh}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 4, time = 5)
@Measurement(iterations = 3, time = 10)
@Threads(4)
@Fork(1)
public class ServletBench {

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

		@Param({"300000"})
		public long rate;

		/** Added to the rate at each measured iteration. 0 keeps the same rate. */
		@Param({"0"})
		public long rampStep;

		/** The table of a fraud detector, in KiB: a power of two. */
		@Param({"1024"})
		public int fraudKiB;

		/** The records a fraud check touches. 0 for none. */
		@Param({"256"})
		public int fraudFeatures;

		final Recorder latency = new Recorder(HIGHEST_NANOS, 3);

		final Recorder sendLag = new Recorder(HIGHEST_NANOS, 3);

		final Queue<Client> clients = new ConcurrentLinkedQueue<>();

		/** The timeline lines of the current iteration. */
		private final Queue<String> timeline = new ConcurrentLinkedQueue<>();

		private volatile boolean recording = true;

		/** The time between two requests of one client. Read at every request. */
		volatile long interval;

		Main.Running running;

		int containers;

		int sessions;

		List<Integer> clientCpus;

		private int threads;

		private int warmups;

		private int measures;

		private String phase;

		private long iterationRate;

		private double startUptime;

		private long startNanos;

		private long startCpu;

		private long startClientCpu;

		@Setup(Level.Trial)
		public void start(BenchmarkParams params) throws Exception {
			containers = Integer.getInteger("servlet.carriers", Runtime.getRuntime().availableProcessors());
			sessions = Integer.getInteger("servlet.sessions", 100_000);
			int connections = Integer.getInteger("bench.connections", 8);
			threads = params.getThreads();
			clientCpus = ClientAffinity.available()
					? ClientAffinity.parseCpuList(System.getProperty("servlet.clientCpus", ""))
					: List.of();
			if (!clientCpus.isEmpty() && clientCpus.size() != threads) {
				throw new IllegalArgumentException(
						"servlet.clientCpus names " + clientCpus.size() + " CPUs, for " + threads + " JMH threads");
			}
			InetSocketAddress backend = new InetSocketAddress("127.0.0.1",
					Integer.getInteger("servlet.backendPort", 8081));
			// Answers once every container has its connections.
			running = Main.start(backend, containers, scheduler, connections,
					new FraudDetectorActor.Settings(fraudKiB, fraudFeatures));
			// Runs for the whole fork, warm-up included: nothing changes when the
			// measure starts.
			Thread.ofPlatform().daemon().name("timeline").start(this::recordTimeline);
			System.out.printf(Locale.ROOT,
					"%nservlet bench   %s, %d containers, %d sessions, %d connections per container, fraud table %d KiB"
							+ " with %d features, %d clients on %s, up to %,d req/s%n",
					scheduler, containers, sessions, connections, fraudKiB, fraudFeatures, threads,
					clientCpus.isEmpty() ? "<floating>" : clientCpus, rate);
		}

		@TearDown(Level.Trial)
		public void stop() throws InterruptedException {
			recording = false;
			running.close();
		}

		@Setup(Level.Iteration)
		public void startIteration(IterationParams params) throws Exception {
			// The counts of the fraud detectors start again with the iteration.
			running.fraudStats(true);
			boolean warmup = params.getType() == IterationType.WARMUP;
			phase = warmup ? "warmup" : "measure";
			iterationRate = warmup ? Math.max(1, rate * ++warmups / params.getCount()) : rate + rampStep * measures++;
			interval = Math.round(1e9 * threads / iterationRate);
			// Throws away what the time between two iterations left.
			latency.getIntervalHistogram();
			sendLag.getIntervalHistogram();
			timeline.clear();
			startUptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1e3;
			startCpu = processCpu();
			startClientCpu = clientCpu();
			startNanos = System.nanoTime();
		}

		/** Runs once every client has finished its iteration. */
		@TearDown(Level.Iteration)
		public void endIteration() throws Exception {
			long elapsed = System.nanoTime() - startNanos;
			long serverCpu = (processCpu() - startCpu) - (clientCpu() - startClientCpu);
			double endUptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1e3;
			Messages.FraudStats fraud = running.fraudStats(false);

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
			Histogram lags = sendLag.getIntervalHistogram();
			long answered = latencies.getTotalCount() + failed;
			double seconds = elapsed / 1e9;
			// The rules of LoadGenerator. They only fill the column kept_up.
			boolean keptUp = dropped == 0 && failed == 0 && sent >= 0.99 * iterationRate * seconds
					&& micros(lags, 99) <= 1e6 * threads / iterationRate / 2;
			StringBuilder lines = new StringBuilder(System.lineSeparator());
			for (String line; (line = timeline.poll()) != null;) {
				lines.append("TIMELINE scheduler=")
						.append(scheduler)
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
					"%nSTEP scheduler=%s fraud_kib=%d fraud_features=%d rate=%d phase=%s start_s=%.3f end_s=%.3f"
							+ " sent=%d dropped=%d answered=%d failed=%d in_flight=%d achieved=%.0f"
							+ " server_us_per_req=%.2f server_cores=%.2f migrations_pct=%.1f p50_us=%.1f p90_us=%.1f"
							+ " p99_us=%.1f p999_us=%.1f max_us=%.1f lag_p99_us=%.1f kept_up=%s%n",
					scheduler, fraudKiB, fraudFeatures, iterationRate, phase, startUptime, endUptime, sent, dropped,
					answered, failed, inFlight,
					answered / seconds, serverCpu / 1e3 / Math.max(1, answered), serverCpu / (double) elapsed,
					// -1: a detector did not answer in time.
					fraud == null ? -1 : fraud.traced() == 0 ? 0 : 100.0 * fraud.migrated() / fraud.traced(),
					micros(latencies, 50), micros(latencies, 90), micros(latencies, 99), micros(latencies, 99.9),
					latencies.getMaxValue() / 1e3, micros(lags, 99), keptUp ? "yes" : "no");
			// A ramp ends at the first step not kept up with. JMH cannot end a fork
			// early, so this JVM stops here. The STEP line is already printed.
			if (rampStep > 0 && phase.equals("measure") && !keptUp) {
				System.out.flush();
				Runtime.getRuntime().halt(0);
			}
		}

		/**
		 * Every 100 ms, keeps one line: the requests waiting per container, and the
		 * requests answered per container since the last line.
		 */
		private void recordTimeline() {
			long[] last = new long[containers];
			while (recording) {
				LockSupport.parkNanos(TIMELINE_NANOS);
				long[] sent = new long[containers];
				long[] answered = new long[containers];
				for (Client client : clients) {
					for (int container = 0; container < containers; container++) {
						// Answered first: a request counted as answered is then always counted as
						// sent.
						answered[container] += client.answeredPerContainer.getOpaque(container);
						sent[container] += client.sentPerContainer.getOpaque(container);
					}
				}
				StringJoiner waiting = new StringJoiner(",");
				StringJoiner done = new StringJoiner(",");
				for (int container = 0; container < containers; container++) {
					waiting.add(Long.toString(sent[container] - answered[container]));
					done.add(Long.toString(answered[container] - last[container]));
					last[container] = answered[container];
				}
				timeline.add("t_s=" + ManagementFactory.getRuntimeMXBean().getUptime() / 1e3 + " waiting=" + waiting
						+ " answered=" + done);
			}
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
		AtomicLongArray sentPerContainer;

		/** Written by this client only. Read by the timeline. */
		AtomicLongArray answeredPerContainer;

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
			sentPerContainer = new AtomicLongArray(server.containers);
			answeredPerContainer = new AtomicLongArray(server.containers);
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
				increment(answeredPerContainer, request.container);
				if (request.failed) {
					failed++;
				} else {
					server.latency.recordValue(clamp(request.answeredAt - request.scheduled));
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
		Answers answers = client.answers;
		HttpRequest httpRequest = next(client.random, server.sessions);
		int container = (int) (httpRequest.sid() % server.containers);
		increment(client.sentPerContainer, container);
		Request request = new Request(scheduled, container);
		// Runs on the carrier of the actor that answers: the time, and a push.
		server.running.handleRequest(httpRequest).whenComplete((reply, error) -> {
			request.answeredAt = System.nanoTime();
			request.failed = error != null || reply.status() != 200;
			answers.push(request);
		});
	}

	/**
	 * A random session and a random product: half GET /profile, which views the
	 * product, half POST /cart, which adds one to three of it. The requests of
	 * LoadGenerator.
	 */
	private static HttpRequest next(Random random, int sessions) {
		long sid = random.nextInt(sessions);
		int product = random.nextInt(Products.COUNT);
		if (random.nextBoolean()) {
			return new HttpRequest("GET", "/profile?viewed=" + product, sid, null);
		}
		byte[] form = ("product=" + product + "&qty=" + (1 + random.nextInt(3))).getBytes(StandardCharsets.US_ASCII);
		return new HttpRequest("POST", "/cart", sid, form);
	}

	/** For a counter with one writer: no atomic instruction is needed. */
	private static void increment(AtomicLongArray counters, int index) {
		counters.setRelease(index, counters.getPlain(index) + 1);
	}

	private static long clamp(long nanos) {
		return Math.min(Math.max(0, nanos), HIGHEST_NANOS);
	}

	/** One request, from its send to its answer. */
	private static final class Request {

		private final long scheduled;

		private final int container;

		/** Written on the carrier, before the push; read after the take. */
		private long answeredAt;

		private boolean failed;

		private Request next;

		private Request(long scheduled, int container) {
			this.scheduled = scheduled;
			this.container = container;
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
