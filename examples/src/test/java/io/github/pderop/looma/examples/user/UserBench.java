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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
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

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Carriers;
import io.github.pderop.looma.examples.utils.ClientAffinity;
import io.github.pderop.looma.examples.utils.LoadGenerator;

import static io.github.pderop.looma.Placement.carrier;
import static io.github.pderop.looma.SpawnOptions.placedOn;

/**
 * The user benchmark under JMH, at a fixed rate, to run it with the JMH
 * profilers.
 *
 * <p>
 * The backend runs in its own JVM, started first ({@link BackendServer}). The
 * fork starts one {@link UserGroupActor} per carrier, as {@link Main} does.
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
 * holds the latencies, the CPU and the skipped sends. Each iteration also
 * prints lines that start with {@code TIMELINE}, one per 100 ms: per group, the
 * requests waiting and the requests answered.
 *
 * <p>
 * The properties are those of {@link Main}: {@code bench.carriers},
 * {@code bench.clientCpus} (one CPU per JMH thread), {@code bench.backendPort},
 * {@code bench.depth} and {@code bench.usersPerGroup}.
 *
 * <p>
 * Run it with {@code scripts/jmh-user.sh}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 4, time = 5)
@Measurement(iterations = 3, time = 10)
@Threads(4)
@Fork(1)
public class UserBench {

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

		@Param({"570000"})
		public long rate;

		/** Added to the rate at each measured iteration. 0 keeps the same rate. */
		@Param({"0"})
		public long rampStep;

		final Recorder latency = new Recorder(HIGHEST_NANOS, 3);

		final Recorder sendLag = new Recorder(HIGHEST_NANOS, 3);

		final Queue<Client> clients = new ConcurrentLinkedQueue<>();

		/** The timeline lines of the current iteration. */
		private final Queue<String> timeline = new ConcurrentLinkedQueue<>();

		private volatile boolean recording = true;

		/** The time between two requests of one client. Read at every request. */
		volatile long interval;

		List<ActorRef> groups;

		int users;

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
			users = carriers * UserGroupActor.USERS_PER_GROUP;

			// First, before any virtual thread starts: the JVM picks its scheduler when
			// the first one starts.
			system = ActorSystem.builder().carriers(carriers(scheduler, carriers)).build();
			groups = new ArrayList<>();
			for (int i = 0; i < carriers; i++) {
				int index = i;
				groups.add(system.spawn("group-" + index, () -> new UserGroupActor(index, carriers, backend),
						placedOn(carrier(index % system.carrierCount()))));
			}
			// Answers once every group has its users and its connections.
			snapshot(true);
			// Runs for the whole fork, warm-up included: nothing changes when the
			// measure starts.
			Thread.ofPlatform().daemon().name("timeline").start(this::recordTimeline);
			System.out.printf(Locale.ROOT,
					"%nuser bench   %s, %d carriers, %d users per group, depth %d, %d clients on %s, up to %,d req/s%n",
					scheduler, carriers, UserGroupActor.USERS_PER_GROUP, UserGroupActor.DEPTH, threads,
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
			sendLag.getIntervalHistogram();
			timeline.clear();
			snapshot(true);
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
			long upstreamFailures = 0;
			long traced = 0;
			long migrated = 0;
			StringJoiner groupRequests = new StringJoiner(",");
			for (UserGroupActor.Stats group : snapshot(false)) {
				groupRequests.add(Long.toString(group.requests()));
				requests += group.requests();
				upstreamFailures += group.upstreamFailures();
				traced += group.tracedMessages();
				migrated += group.migratedMessages();
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
			Histogram lags = sendLag.getIntervalHistogram();
			double seconds = elapsed / 1e9;
			// The rules of LoadGenerator. They only fill the column kept_up.
			boolean keptUp = dropped == 0 && failed == 0 && upstreamFailures == 0
					&& sent >= 0.99 * iterationRate * seconds && micros(lags, 99) <= 1e6 * threads / iterationRate / 2;
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
					"%nSTEP scheduler=%s rate=%d phase=%s start_s=%.3f end_s=%.3f sent=%d dropped=%d answered=%d"
							+ " failed=%d upstream_failed=%d in_flight=%d achieved=%.0f server_us_per_req=%.2f"
							+ " server_cores=%.2f migrations_pct=%.1f p50_us=%.1f p90_us=%.1f p99_us=%.1f"
							+ " p999_us=%.1f max_us=%.1f lag_p99_us=%.1f group_requests=%s kept_up=%s%n",
					scheduler, iterationRate, phase, startUptime, endUptime, sent, dropped, latencies.getTotalCount(),
					failed, upstreamFailures, inFlight, requests / seconds, serverCpu / 1e3 / Math.max(1, requests),
					serverCpu / (double) elapsed, traced == 0 ? 0 : 100.0 * migrated / traced, micros(latencies, 50),
					micros(latencies, 90), micros(latencies, 99), micros(latencies, 99.9),
					latencies.getMaxValue() / 1e3, micros(lags, 99), groupRequests, keptUp ? "yes" : "no");
			// A ramp ends at the first step not kept up with. JMH cannot end a fork
			// early, so this JVM stops here. The STEP line is already printed.
			if (rampStep > 0 && phase.equals("measure") && !keptUp) {
				System.out.flush();
				Runtime.getRuntime().halt(0);
			}
		}

		/**
		 * Every 100 ms, keeps one line: the requests waiting per group, and the
		 * requests answered per group since the last line.
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
				for (int group = 0; group < count; group++) {
					waiting.add(Long.toString(sent[group] - answered[group]));
					done.add(Long.toString(answered[group] - last[group]));
					last[group] = answered[group];
				}
				timeline.add("t_s=" + ManagementFactory.getRuntimeMXBean().getUptime() / 1e3 + " waiting=" + waiting
						+ " answered=" + done);
			}
		}

		private List<UserGroupActor.Stats> snapshot(boolean reset) {
			List<UserGroupActor.Stats> stats = new ArrayList<>();
			for (ActorRef group : groups) {
				stats.add(group.ask(new UserGroupActor.Snapshot(reset), UserGroupActor.Stats.class).join());
			}
			return stats;
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

		static Carriers carriers(String name, int count) {
			return switch (name) {
				case "mpsc" -> Carriers.mpsc(count);
				case "mpsc-unpinned" -> Carriers.mpsc(count).pinned(false);
				case "mpsc-no-affinity" -> Carriers.mpsc(count).affinity(false);
				case "mpsc-unpinned-no-affinity" -> Carriers.mpsc(count).pinned(false).affinity(false);
				case "jdk" -> Carriers.builtin(count);
				default -> throw new IllegalArgumentException("scheduler must be mpsc, mpsc-unpinned, mpsc-no-affinity,"
						+ " mpsc-unpinned-no-affinity or jdk, not '" + name + "'");
			};
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
		int userId = client.random.nextInt(server.users);
		int group = userId % server.groups.size();
		increment(client.sentPerGroup, group);
		Request request = new Request(scheduled, group);
		// Runs on the carrier of the actor that answers: the time, and a push.
		server.groups.get(group)
				.ask(new UserGroupActor.Recommend(userId), UserGroupActor.Recommended.class)
				.whenComplete((reply, error) -> {
					request.answeredAt = System.nanoTime();
					request.failed = error != null;
					answers.push(request);
				});
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

		private final int group;

		/** Written on the carrier, before the push; read after the take. */
		private long answeredAt;

		private boolean failed;

		private Request next;

		private Request(long scheduled, int group) {
			this.scheduled = scheduled;
			this.group = group;
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
