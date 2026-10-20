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

import static io.github.pderop.looma.Placement.carrier;
import static io.github.pderop.looma.SpawnOptions.placedOn;

/**
 * A service that recommends users. Run it with {@code scripts/bench.sh};
 * {@code docs/benchmark.md} explains it.
 *
 * <ul>
 * <li>{@link FrontendServer} sends {@code -Dbench.rate} requests per second to
 * {@link #handleRequest}.
 * <li>{@code handleRequest} sends each request to the {@link UserGroup} that
 * owns the user. There is one group per carrier.
 * <li>The group GETs {@link BackendServer}, which runs in another process. Then
 * it walks its users and answers.
 * </ul>
 *
 * {@code -Dbench.scheduler} picks the scheduler. The other properties are
 * {@code bench.carriers} (one group per carrier), {@code bench.clientCpus},
 * {@code bench.backendPort}, {@code bench.warmupSeconds},
 * {@code bench.measureSeconds}, {@code bench.depth} and
 * {@code bench.usersPerGroup}.
 */
public final class Main {

	/** The memory one user takes, measured. Only printed. */
	private static final int BYTES_PER_USER = 82;

	private static final int WARMUP_SECONDS = Integer.getInteger("bench.warmupSeconds", 5);

	private static final int MEASURE_SECONDS = Integer.getInteger("bench.measureSeconds", 20);

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
	CompletableFuture<UserGroup.Recommended> handleRequest(int userId) {
		return groups.get(userId % groups.size()).ask(new UserGroup.Recommend(userId), UserGroup.Recommended.class);
	}

	public static void main(String[] args) throws Exception {
		int groups = Integer.getInteger("bench.carriers", Runtime.getRuntime().availableProcessors());
		long rate = Long.getLong("bench.rate", 0);
		if (rate <= 0) {
			throw new IllegalArgumentException("bench.rate must be a positive number of requests per second");
		}
		List<Integer> clientCpus = ClientAffinity.available()
				? ClientAffinity.parseCpuList(System.getProperty("bench.clientCpus", ""))
				: List.of();
		InetSocketAddress backend = new InetSocketAddress("127.0.0.1", Integer.getInteger("bench.backendPort", 8081));

		// First, before any virtual thread starts: the JVM picks its scheduler when
		// the first one starts.
		ActorSystem system = ActorSystem.builder().carriers(carriers(groups)).build();

		List<ActorRef> groupRefs = new ArrayList<>();
		for (int i = 0; i < groups; i++) {
			int index = i;
			groupRefs.add(system.spawn("group-" + index, () -> new UserGroup(index, groups, backend),
					placedOn(carrier(index % system.carrierCount()))));
		}
		FrontendServer frontend = new FrontendServer(new Main(groupRefs)::handleRequest,
				groups * UserGroup.USERS_PER_GROUP, rate, clientCpus);

		long mapKiB = (long) UserGroup.USERS_PER_GROUP * BYTES_PER_USER / 1024;
		// The carriers asked for. The JDK builtin gets them as its parallelism, but its
		// carrierCount() is 1: an actor cannot be placed on one of its workers.
		System.out.printf(Locale.ROOT, "scheduler    %s, %d carriers%n", system.scheduler().getClass().getName(),
				groups);
		System.out.printf(Locale.ROOT, "groups       %d, %d users each (~%d KiB), depth %d%n", groups,
				UserGroup.USERS_PER_GROUP, mapKiB, UserGroup.DEPTH);
		System.out.printf(Locale.ROOT, "load         %,d req/s, %d connection(s) on CPUs %s%n", rate,
				frontend.connections(), clientCpus.isEmpty() ? "<floating>" : clientCpus);
		System.out.printf(Locale.ROOT, "backend      %s:%d%n", backend.getHostString(), backend.getPort());
		System.out.printf(Locale.ROOT, "window       %ds warm-up, %ds measured%n%n", WARMUP_SECONDS, MEASURE_SECONDS);

		frontend.start();
		// Take one snapshot during the warm-up, so the JIT has compiled this code
		// before the measure starts.
		snapshot(groupRefs, true);
		frontend.cpuNanos();
		long chunksBefore = -1;
		if (STACK_CHUNKS) {
			TimeUnit.MILLISECONDS.sleep(WARMUP_SECONDS * 500L);
			chunksBefore = liveStackChunks();
			TimeUnit.MILLISECONDS.sleep(WARMUP_SECONDS * 500L);
		} else {
			TimeUnit.SECONDS.sleep(WARMUP_SECONDS);
		}

		snapshot(groupRefs, true);
		// scripts/bench.sh starts perf from this line.
		System.out.printf(Locale.ROOT, "measure      started, %ds%n", MEASURE_SECONDS);
		System.out.flush();
		frontend.startMeasuring();
		long start = System.nanoTime();
		Duration startCpu = processCpu();
		long startClientCpu = frontend.cpuNanos();

		TimeUnit.SECONDS.sleep(MEASURE_SECONDS);

		List<UserGroup.Stats> stats = snapshot(groupRefs, false);
		frontend.stopMeasuring();
		long elapsed = System.nanoTime() - start;
		long cpu = processCpu().minus(startCpu).toNanos();
		long clientCpu = frontend.cpuNanos() - startClientCpu;
		long chunksAfter = STACK_CHUNKS ? liveStackChunks() : -1;

		frontend.stop();
		printReport(stats, elapsed, cpu, clientCpu);
		frontend.printReport(elapsed);
		if (STACK_CHUNKS) {
			System.out.printf(Locale.ROOT, "stackchunks  %d live during the warm-up, %d after the measure%n",
					chunksBefore, chunksAfter);
		}

		system.shutdown();
		system.awaitTermination(10, TimeUnit.SECONDS);
	}

	/**
	 * The scheduler named by {@code -Dbench.scheduler}, with {@code count}
	 * carriers.
	 */
	private static Carriers carriers(int count) {
		String name = System.getProperty("bench.scheduler", "hybrid");
		return switch (name) {
			case "stealing-local" -> Carriers.affine(count).pinned().workStealing(Carriers.WorkStealing.CLUSTER_LOCAL);
			case "hybrid" ->
				Carriers.affine(count).pinned().workStealing(Carriers.WorkStealing.GLOBAL).actorsStayHome();
			case "jdk" -> Carriers.builtin(count);
			default -> throw new IllegalArgumentException(
					"bench.scheduler must be stealing-local, hybrid or jdk, not '" + name + "'");
		};
	}

	/** The counters of every group, reset if {@code reset} is true. */
	private static List<UserGroup.Stats> snapshot(List<ActorRef> groups, boolean reset) {
		List<UserGroup.Stats> stats = new ArrayList<>();
		for (ActorRef group : groups) {
			stats.add(group.ask(new UserGroup.Snapshot(reset), UserGroup.Stats.class).join());
		}
		return stats;
	}

	/**
	 * The live StackChunks, from a class histogram: what {@code jcmd
	 * GC.class_histogram} prints, asked from inside the JVM.
	 */
	private static long liveStackChunks() throws Exception {
		String histogram = (String) ManagementFactory.getPlatformMBeanServer().invoke(
				new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram", new Object[]{null},
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
	private static void printReport(List<UserGroup.Stats> stats, long elapsed, long cpu, long clientCpu) {
		long requests = 0;
		long failures = 0;
		long traced = 0;
		long migrated = 0;
		for (UserGroup.Stats group : stats) {
			requests += group.requests();
			failures += group.upstreamFailures();
			traced += group.tracedMessages();
			migrated += group.migratedMessages();
		}
		long serverCpu = cpu - clientCpu;
		System.out.printf(Locale.ROOT, "throughput   %,.0f req/s%n", requests / (elapsed / 1e9));
		System.out.printf(Locale.ROOT, "cpu          %.2f us/req (%.2f cores busy) -- server: process minus clients%n",
				serverCpu / 1e3 / requests, serverCpu / (double) elapsed);
		System.out.printf(Locale.ROOT, "client cpu   %.2f us/req (%.2f cores busy)%n", clientCpu / 1e3 / requests,
				clientCpu / (double) elapsed);
		System.out.printf(Locale.ROOT,
				"migrations   %.1f%% of %,d sampled messages ran on another carrier than the one before%n",
				traced == 0 ? 0 : 100.0 * migrated / traced, traced);
		if (failures > 0) {
			System.out.printf(Locale.ROOT, "*** %,d requests got no backend response -- not a measurement ***%n",
					failures);
		}
	}
}
