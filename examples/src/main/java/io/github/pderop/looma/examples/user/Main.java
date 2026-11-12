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

import java.net.InetSocketAddress;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.management.ObjectName;

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Carriers;
import io.github.pderop.looma.examples.utils.BackendCpu;
import io.github.pderop.looma.examples.utils.ClientAffinity;
import io.github.pderop.looma.examples.utils.LoadGenerator;

import static io.github.pderop.looma.Placement.carrier;
import static io.github.pderop.looma.SpawnOptions.placedOn;

/**
 * A service that recommends users. Run it with {@code scripts/bench-user.sh};
 * {@code docs/benchmark-user.md} explains it.
 *
 * <ul>
 * <li>{@link LoadGenerator} sends {@code -Dbench.rate} requests per second to
 * {@link #handleRequest}.
 * <li>{@code handleRequest} sends each request to the {@link UserGroupActor}
 * that owns the user. There is one group per carrier.
 * <li>The group GETs {@link BackendServer}, which runs in another process. Then
 * it walks its users and answers.
 * </ul>
 *
 * {@code -Dbench.scheduler} picks the scheduler. The other properties are
 * {@code bench.carriers} (one group per carrier), {@code bench.clientCpus},
 * {@code bench.backendPort}, {@code bench.warmupSeconds},
 * {@code bench.measureSeconds}, {@code bench.depth},
 * {@code bench.usersPerGroup}, {@code bench.connections}, and
 * {@code bench.backendPid} with {@code bench.clkTck} to print the backend's CPU
 * (Linux only).
 *
 * <p>
 * With {@code -Dbench.step}, the run is a ramp instead of a measure: after the
 * warm-up, the rate starts at {@code bench.rate} and goes up by
 * {@code bench.step} at each step, until a step is not kept up with, or above
 * {@code bench.maxRate}. Each step is measured for {@code bench.stepSeconds}.
 */
public final class Main {

	/** The memory one user takes, measured. Only printed. */
	private static final int BYTES_PER_USER = 82;

	private static final int WARMUP_SECONDS = Integer.getInteger("bench.warmupSeconds", 10);

	private static final int MEASURE_SECONDS = Integer.getInteger("bench.measureSeconds", 20);

	/** How long each step of the ramp is measured. */
	private static final int STEP_SECONDS = Integer.getInteger("bench.stepSeconds", 10);

	/** How long the rate takes to go up to the next step, unmeasured. */
	private static final int SETTLE_SECONDS = 3;

	/** How long the late answers of a step have, before its report. */
	private static final int LATE_ANSWER_SECONDS = 2;

	/**
	 * With {@code -Dbench.stackChunks=true}, counts the live StackChunks during the
	 * warm-up and after the measure. Each count is a full GC, so none falls in the
	 * measure.
	 */
	private static final boolean STACK_CHUNKS = Boolean.getBoolean("bench.stackChunks");

	private final List<ActorRef> groups;

	private Main(List<ActorRef> groups) {
		this.groups = groups;
	}

	/** Every request comes here, and goes to group {@code userId % groups}. */
	CompletableFuture<UserGroupActor.Recommended> handleRequest(int userId) {
		return groups.get(userId % groups.size())
				.ask(new UserGroupActor.Recommend(userId),
						UserGroupActor.Recommended.class);
	}

	public static void main(String[] args) throws Exception {
		int groups = Integer.getInteger("bench.carriers", Runtime.getRuntime().availableProcessors());
		long rate = Long.getLong("bench.rate", 0);
		if (rate <= 0) {
			throw new IllegalArgumentException("bench.rate must be a positive number of requests per second");
		}
		long step = Long.getLong("bench.step", 0);
		long maxRate = Long.getLong("bench.maxRate", 0);
		if (step < 0 || maxRate < 0) {
			throw new IllegalArgumentException("bench.step and bench.maxRate cannot be negative");
		}
		List<Integer> clientCpus = ClientAffinity.available()
				? ClientAffinity.parseCpuList(System.getProperty("bench.clientCpus", ""))
				: List.of();
		InetSocketAddress backend = new InetSocketAddress("127.0.0.1", Integer.getInteger("bench.backendPort", 8081));
		BackendCpu backendCpu = new BackendCpu(Long.getLong("bench.backendPid", 0), Long.getLong("bench.clkTck", 100));

		// First, before any virtual thread starts: the JVM picks its scheduler when
		// the first one starts.
		Carriers chosen = carriers(groups);
		ActorSystem system = ActorSystem.builder().carriers(chosen).build();

		List<ActorRef> groupRefs = new ArrayList<>();
		for (int i = 0; i < groups; i++) {
			int index = i;
			groupRefs.add(system.spawn("group-" + index, () -> new UserGroupActor(index, groups, backend),
					placedOn(carrier(index % system.carrierCount()))));
		}
		// Each request picks a user at random: every user answers.
		Main main = new Main(groupRefs);
		int users = groups * UserGroupActor.USERS_PER_GROUP;
		LoadGenerator<UserGroupActor.Recommended> load = new LoadGenerator<>(
				random -> main.handleRequest(random.nextInt(users)), reply -> true, clientCpus);
		load.setRate(rate);

		long mapKiB = (long) UserGroupActor.USERS_PER_GROUP * BYTES_PER_USER / 1024;
		// The carriers asked for, with their settings. The JDK builtin gets them as its
		// parallelism, but its carrierCount() is 1: an actor cannot be placed on one of
		// its workers.
		System.out.printf(Locale.ROOT, "scheduler    %s, %s%n", system.scheduler().getClass().getName(), chosen);
		System.out.printf(Locale.ROOT, "poller mode  %s%n", System.getProperty("jdk.pollerMode", "not set"));
		System.out.printf(Locale.ROOT, "groups       %d, %d users each (~%d KiB), depth %d%n", groups,
				UserGroupActor.USERS_PER_GROUP, mapKiB, UserGroupActor.DEPTH);
		System.out.printf(Locale.ROOT, "load         %,d req/s, %d connection(s) on CPUs %s%n", rate,
				load.connections(), clientCpus.isEmpty() ? "<floating>" : clientCpus);
		System.out.printf(Locale.ROOT, "backend      %s:%d, %d connections per group%n", backend.getHostString(),
				backend.getPort(), UserGroupActor.CONNECTIONS);
		System.out.printf(Locale.ROOT, "window       %ds warm-up, %ds measured%n%n", WARMUP_SECONDS, MEASURE_SECONDS);

		load.start();
		// Take one snapshot during the warm-up, so the JIT has compiled this code
		// before the measure starts.
		snapshot(groupRefs, true);
		// The same for the CPU time of the clients: the first read loads its code.
		load.cpuNanos();
		long chunksBefore = -1;
		if (STACK_CHUNKS) {
			TimeUnit.MILLISECONDS.sleep(WARMUP_SECONDS * 500L);
			chunksBefore = liveStackChunks();
			TimeUnit.MILLISECONDS.sleep(WARMUP_SECONDS * 500L);
		} else {
			TimeUnit.SECONDS.sleep(WARMUP_SECONDS);
		}

		if (step > 0) {
			ramp(groupRefs, load, backendCpu, rate, step, maxRate);
			load.stop();
		} else {
			snapshot(groupRefs, true);
			// scripts/bench-user.sh starts perf from this line.
			System.out.printf(Locale.ROOT, "measure      started, %ds%n", MEASURE_SECONDS);
			System.out.flush();
			LoadGenerator.Window window = load.startMeasuring();
			long start = System.nanoTime();
			Duration startCpu = processCpu();
			long startClientCpu = load.cpuNanos();
			long startBackend = backendCpu.ticks();

			TimeUnit.SECONDS.sleep(MEASURE_SECONDS);

			List<UserGroupActor.Stats> stats = snapshot(groupRefs, false);
			load.stopMeasuring();
			long elapsed = System.nanoTime() - start;
			long cpu = processCpu().minus(startCpu).toNanos();
			long clientCpu = load.cpuNanos() - startClientCpu;
			long backendTicks = backendCpu.ticksSince(startBackend);
			long chunksAfter = STACK_CHUNKS ? liveStackChunks() : -1;

			load.stop();
			printReport(stats, elapsed, cpu, clientCpu);
			backendCpu.print(backendTicks, elapsed);
			load.printReport(window, rate, elapsed);
			if (STACK_CHUNKS) {
				System.out.printf(Locale.ROOT, "stackchunks  %d live during the warm-up, %d after the measure%n",
						chunksBefore, chunksAfter);
			}
		}

		system.shutdown();
		system.awaitTermination(10, TimeUnit.SECONDS);
	}

	/**
	 * The ramp: the rate starts at {@code rate} and goes up by {@code step} at each
	 * step, until a step is not kept up with, or above {@code maxRate} (0: no
	 * limit). The last line says the highest rate kept up with.
	 */
	private static void ramp(List<ActorRef> groups, LoadGenerator<?> load, BackendCpu backendCpu, long rate, long step,
			long maxRate) throws Exception {
		System.out.printf(Locale.ROOT,
				"ramp         from %,d req/s, %,d more at each step%s; at each step %ds measured and %ds to go up"
						+ " to it%n%n",
				rate, step, maxRate > 0 ? String.format(Locale.ROOT, ", up to %,d", maxRate) : "", STEP_SECONDS,
				SETTLE_SECONDS);
		long held = 0;
		for (long next = rate; maxRate == 0 || next <= maxRate; next += step) {
			if (next != rate) {
				rampTo(load, next - step, next, SETTLE_SECONDS);
			}
			System.out.printf(Locale.ROOT, "step         %,d req/s, %ds measured%n", next, STEP_SECONDS);
			boolean keptUp = step(groups, load, backendCpu, next);
			System.out.println();
			if (!keptUp) {
				break;
			}
			held = next;
		}
		System.out.printf(Locale.ROOT, "ramp         kept up with %s%n",
				held > 0 ? String.format(Locale.ROOT, "%,d req/s", held) : "none");
		System.out.flush();
	}

	/**
	 * One step of the ramp: measures {@link #STEP_SECONDS} at {@code rate} while
	 * the clients go on, waits for the late answers, and prints the report. Returns
	 * whether the rate was kept up with.
	 */
	private static boolean step(List<ActorRef> groups, LoadGenerator<?> load, BackendCpu backendCpu, long rate)
			throws Exception {
		snapshot(groups, true);
		LoadGenerator.Window window = load.startMeasuring();
		long start = System.nanoTime();
		Duration startCpu = processCpu();
		long startClientCpu = load.cpuNanos();
		long startBackend = backendCpu.ticks();

		TimeUnit.SECONDS.sleep(STEP_SECONDS);

		List<UserGroupActor.Stats> stats = snapshot(groups, false);
		load.stopMeasuring();
		long elapsed = System.nanoTime() - start;
		long cpu = processCpu().minus(startCpu).toNanos();
		long clientCpu = load.cpuNanos() - startClientCpu;
		long backendTicks = backendCpu.ticksSince(startBackend);

		TimeUnit.SECONDS.sleep(LATE_ANSWER_SECONDS);
		boolean answered = printReport(stats, elapsed, cpu, clientCpu);
		backendCpu.print(backendTicks, elapsed);
		boolean keptUp = load.printReport(window, rate, elapsed) && answered;
		System.out.flush();
		return keptUp;
	}

	/**
	 * Takes the rate from {@code from} to {@code to} over {@code seconds}, a little
	 * every 100 ms, and ends at {@code to}.
	 */
	private static void rampTo(LoadGenerator<?> load, long from, long to, int seconds) throws InterruptedException {
		int ticks = Math.max(1, seconds * 10);
		for (int i = 1; i <= ticks; i++) {
			load.setRate(from + (to - from) * i / ticks);
			TimeUnit.MILLISECONDS.sleep(100);
		}
	}

	/**
	 * The scheduler named by {@code -Dbench.scheduler}, with {@code count}
	 * carriers.
	 */
	private static Carriers carriers(int count) {
		String name = System.getProperty("bench.scheduler", "mpsc");
		return switch (name) {
			case "mpsc" -> Carriers.mpsc(count);
			case "mpsc-unpinned" -> Carriers.mpsc(count).pinned(false);
			case "mpsc-no-affinity" -> Carriers.mpsc(count).affinity(false);
			case "mpsc-unpinned-no-affinity" -> Carriers.mpsc(count).pinned(false).affinity(false);
			case "jdk" -> Carriers.builtin(count);
			default -> throw new IllegalArgumentException("bench.scheduler must be mpsc, mpsc-unpinned,"
					+ " mpsc-no-affinity, mpsc-unpinned-no-affinity or jdk, not '" + name + "'");
		};
	}

	/** The counters of every group, reset if {@code reset} is true. */
	private static List<UserGroupActor.Stats> snapshot(List<ActorRef> groups, boolean reset) {
		List<UserGroupActor.Stats> stats = new ArrayList<>();
		for (ActorRef group : groups) {
			stats.add(group.ask(new UserGroupActor.Snapshot(reset), UserGroupActor.Stats.class).join());
		}
		return stats;
	}

	/**
	 * The live StackChunks, from a class histogram: what {@code jcmd
	 * GC.class_histogram} prints, asked from inside the JVM.
	 */
	private static long liveStackChunks() throws Exception {
		String histogram = (String) ManagementFactory.getPlatformMBeanServer()
				.invoke(
						new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
						new Object[]{null},
						new String[]{String[].class.getName()});
		for (String line : histogram.split("\n")) {
			String[] columns = line.trim().split("\\s+");
			if (columns.length >= 4 && columns[3].equals("jdk.internal.vm.StackChunk")) {
				return Long.parseLong(columns[1]);
			}
		}
		return 0;
	}

	private static Duration processCpu() {
		return ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO);
	}

	/**
	 * The server's CPU is the process's CPU minus the client threads' CPU: the
	 * client threads run in this JVM, but they are the load, not the server.
	 */
	private static boolean printReport(List<UserGroupActor.Stats> stats, long elapsed, long cpu, long clientCpu) {
		long requests = 0;
		long failures = 0;
		long traced = 0;
		long migrated = 0;
		for (UserGroupActor.Stats group : stats) {
			requests += group.requests();
			failures += group.upstreamFailures();
			traced += group.tracedMessages();
			migrated += group.migratedMessages();
		}
		long serverCpu = cpu - clientCpu;
		// At least one request, so a step with no answer prints numbers, not NaN.
		long perRequest = Math.max(1, requests);
		System.out.printf(Locale.ROOT, "throughput   %,.0f req/s%n", requests / (elapsed / 1e9));
		System.out.printf(Locale.ROOT, "cpu          %.2f us/req (%.2f cores busy) -- server: process minus clients%n",
				serverCpu / 1e3 / perRequest, serverCpu / (double) elapsed);
		System.out.printf(Locale.ROOT, "client cpu   %.2f us/req (%.2f cores busy)%n", clientCpu / 1e3 / perRequest,
				clientCpu / (double) elapsed);
		System.out.printf(Locale.ROOT,
				"migrations   %.1f%% of %,d sampled messages ran on another carrier than the one before%n",
				traced == 0 ? 0 : 100.0 * migrated / traced, traced);
		if (failures > 0) {
			System.out.printf(Locale.ROOT, "*** %,d requests got no backend response -- not a measurement ***%n",
					failures);
		}
		return failures == 0;
	}
}
