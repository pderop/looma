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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.spi.ActorScheduler;

import static io.github.pderop.looma.Placement.carrier;
import static io.github.pderop.looma.SpawnOptions.placedOn;

/**
 * A service that recommends users, run under two schedulers. Same jar, same
 * code, same cores — only the JVM flags differ. Run it with
 * {@code scripts/run-cache-bench.sh}.
 *
 * <h2>The workload</h2>
 *
 * One {@link UserGroup} actor per core, each owning a plain {@link HashMap} of
 * the users it serves. Requests for user {@code u} go to group
 * {@code u % groups} — a sticky load balancer or a consistent hash already
 * routes them that way — so a group's map is reached by one thread and needs no
 * locking.
 *
 * <p>
 * A request walks the friend graph: read the user, go to a friend, then to a
 * friend of <em>theirs</em>, {@code cache.depth} times, scoring each one.
 * Nothing can tell the CPU which user to fetch next before the current one has
 * arrived. That dependency is not arranged for the benchmark — it is what
 * following a graph costs — and it is what makes the same walk fast out of this
 * core's cache and slow out of another's.
 *
 * <h2>What the two runs compare</h2>
 *
 * An actor that keeps resuming on the same carrier finds its map in its core's
 * L2. An actor resumed on whichever worker happens to be free finds it in
 * another core's, and every line it writes must be taken away from that core
 * first. The application is identical; what differs is whether the scheduler
 * respects the locality it already has.
 *
 * <p>
 * <b>Two sizes decide whether that is measurable, and only one is obvious.</b>
 * One group's map must fit in a core's L2, or affinity has nothing to keep
 * resident. All the maps together must <em>not</em> fit, or a migrated actor
 * finds its own cached wherever it lands and the two schedulers measure the
 * same thing. The banner prints both figures; check them against
 * {@code lscpu -C}.
 *
 * <h2>Reading the report</h2>
 *
 * <ul>
 * <li><b>cpu per request</b> is the claim — read it with <b>cores busy</b> next
 * to it. With one request in flight per group nothing is CPU-limited, so
 * <b>throughput</b> partly reports wake-up latency, and a scheduler that merely
 * spins instead of parking leads on it without doing more work.
 * <li><b>migrations</b> is the mechanism, counted rather than inferred.
 * <li><b>{@link UserGroup#DEPTH} set to 0</b> touches no user at all: the
 * control that can falsify the whole thing.
 * </ul>
 *
 * <p>
 * The sizes that matter — {@link UserGroup#DEPTH} and
 * {@link UserGroup#USERS_PER_GROUP} — are constants of {@link UserGroup}, each
 * with its derivation next to it. The only two knobs are {@code cache.groups},
 * which the script sets from the cpuset, and {@code cache.migrationSample}.
 */
public final class Main {

	/**
	 * Live bytes one user costs, measured rather than guessed: 32 for the
	 * {@link User}, 24 for its map node, 16 for its boxed key, ~16 for the table
	 * slot. Weighing the real store gives 81.5 to 84.1 depending on where the
	 * power-of-two table lands. Printed, never computed with.
	 */
	private static final int BYTES_PER_USER = 82;

	/**
	 * Client threads per group, and not a knob: one request in flight per group is
	 * what empties the mailbox between two messages, which is what makes the actor
	 * park — and that park is the moment a scheduler chooses the core it comes back
	 * on. Raise it and the mailbox never empties, the actor never migrates, and the
	 * mechanism being measured disappears.
	 */
	private static final int CLIENTS_PER_GROUP = 1;

	/**
	 * Seconds of load before the counters are zeroed, and seconds measured after
	 * that. Long enough for the JIT to have settled and for the maps to be resident
	 * wherever they are going to be; not knobs, because a run compared against
	 * another run has to have been measured the same way.
	 */
	private static final int WARMUP_SECONDS = 5;

	private static final int MEASURE_SECONDS = 20;

	private Main() {
	}

	public static void main(String[] args) throws Exception {
		int processors = Runtime.getRuntime().availableProcessors();

		ActorSystem system = ActorSystem.builder().build();
		ActorScheduler scheduler = system.scheduler();

		// One group per CPU of the cpuset. The script overrides it, giving every
		// scheduler the same number -- which is what makes the runs comparable.
		int groups = Integer.getInteger("cache.groups", processors);
		if (groups < 1) {
			throw new IllegalArgumentException("cache.groups must be at least 1, not " + groups);
		}

		printConfiguration(scheduler, processors, groups);

		List<ActorRef> groupRefs = spawnGroups(system, scheduler, groups);
		AtomicBoolean running = new AtomicBoolean(true);
		List<Thread> clients = startClients(groupRefs, running);

		System.out.printf("warming up for %ds...%n", WARMUP_SECONDS);
		TimeUnit.SECONDS.sleep(WARMUP_SECONDS);

		snapshot(groupRefs, true); // opens the window: every counter is zero from here
		long startNanos = System.nanoTime();
		Duration startCpu = processCpu();

		TimeUnit.SECONDS.sleep(MEASURE_SECONDS);

		List<UserGroup.Stats> stats = snapshot(groupRefs, false);
		long elapsedNanos = System.nanoTime() - startNanos;
		Duration cpu = processCpu().minus(startCpu);

		running.set(false);
		for (Thread client : clients) {
			client.join();
		}

		printReport(stats, elapsedNanos, cpu, clients.size());

		system.shutdown();
		system.awaitTermination(10, TimeUnit.SECONDS);
	}

	/**
	 * One group per core, each on a carrier of its own. An explicit placement
	 * rather than the round-robin default, whose cursor is shared with every other
	 * root spawn in the process.
	 */
	private static List<ActorRef> spawnGroups(ActorSystem system, ActorScheduler scheduler, int groups) {
		List<ActorRef> refs = new ArrayList<>();
		for (int i = 0; i < groups; i++) {
			int index = i;
			refs.add(system.spawn("group-" + index, () -> new UserGroup(index, groups, new CarrierTracker(scheduler)),
					placedOn(carrier(Math.floorMod(index, system.carrierCount())))));
		}
		return refs;
	}

	/**
	 * One closed loop per client thread, each pinned to one group and drawing users
	 * from it — stickiness, as a real deployment has it.
	 *
	 * <p>
	 * One request in flight per client, so every request leaves the mailbox empty
	 * behind it. That empty mailbox is the park where a scheduler decides which
	 * core this actor comes back on, i.e. the moment the comparison turns on.
	 */
	private static List<Thread> startClients(List<ActorRef> groups, AtomicBoolean running) {
		List<Thread> clients = new ArrayList<>();
		for (int group = 0; group < groups.size(); group++) {
			for (int c = 0; c < CLIENTS_PER_GROUP; c++) {
				ActorRef ref = groups.get(group);
				int index = group;
				Random random = new Random(group * 31L + c);
				clients.add(Thread.ofPlatform().name("client-" + group + "-" + c).start(() -> {
					while (running.get()) {
						Integer user = UserGroup.userId(index, groups.size(),
								random.nextInt(UserGroup.USERS_PER_GROUP));
						ref.ask(new UserGroup.Recommend(user), UserGroup.Recommended.class).join();
					}
				}));
			}
		}
		return clients;
	}

	/**
	 * Waits for every answer, so the window opens at a point all groups agree on.
	 */
	private static List<UserGroup.Stats> snapshot(List<ActorRef> groups, boolean reset) {
		List<UserGroup.Stats> stats = new ArrayList<>();
		for (ActorRef group : groups) {
			stats.add(group.ask(new UserGroup.Snapshot(reset), UserGroup.Stats.class).join());
		}
		return stats;
	}

	/**
	 * Process CPU, user plus system. Asked of {@link ProcessHandle} so the demo
	 * needs no extra module; absent on a platform that will not report it, in which
	 * case the report simply omits the line.
	 */
	private static Duration processCpu() {
		return ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO);
	}

	private static void printConfiguration(ActorScheduler scheduler, int processors, int groups) {
		// The class name is what says which scheduler actually got installed: a missing
		// --enable-preview leaves the builtin one in place without saying so.
		System.out.printf(Locale.ROOT, "scheduler=%s carriers=%d availableProcessors=%d%n",
				scheduler.getClass().getName(), scheduler.carrierCount(), processors);
		long mapKiB = (long) UserGroup.USERS_PER_GROUP * BYTES_PER_USER / 1024;
		System.out.printf(Locale.ROOT,
				"groups=%d users=%d (%d per group) footprint=~%d KiB per group, ~%.1f MiB total%n", groups,
				(long) groups * UserGroup.USERS_PER_GROUP, UserGroup.USERS_PER_GROUP, mapKiB, mapKiB * groups / 1024.0);
		System.out.println("           (one group's map must fit in a core's L2; all of them together must not)");
		System.out.printf(Locale.ROOT, "depth=%d clients=%d (closed loop, 1 request in flight each)%n", UserGroup.DEPTH,
				groups * CLIENTS_PER_GROUP);
		System.out.printf(Locale.ROOT, "warmup=%ds measure=%ds migrationSample=%s%n", WARMUP_SECONDS, MEASURE_SECONDS,
				CarrierTracker.describe());
		if (UserGroup.DEPTH == 0) {
			System.out.println("*** dispatch-only control: no user is touched ***");
		}
		System.out.printf(Locale.ROOT, "vtParallelism=%s vtMaxPoolSize=%s%n",
				System.getProperty("jdk.virtualThreadScheduler.parallelism", "<default>"),
				System.getProperty("jdk.virtualThreadScheduler.maxPoolSize", "<default>"));
		System.out.println();
	}

	private static void printReport(List<UserGroup.Stats> stats, long elapsedNanos, Duration cpu, int clients) {
		long requests = 0;
		long traced = 0;
		long migrated = 0;
		for (UserGroup.Stats group : stats) {
			requests += group.requests();
			traced += group.tracedResumes();
			migrated += group.migratedResumes();
		}

		double seconds = elapsedNanos / 1e9;
		System.out.println();
		System.out.printf(Locale.ROOT, "throughput   %,.0f req/s (%,d requests in %.1fs)%n", requests / seconds,
				requests, seconds);
		System.out.printf(Locale.ROOT, "latency      %.2f us/req (closed loop, %d clients)%n",
				seconds * 1e6 * clients / requests, clients);
		if (!cpu.isZero()) {
			System.out.printf(Locale.ROOT, "cpu          %.2f us/req (%.2f cores busy)%n",
					cpu.toNanos() / 1e3 / requests, cpu.toNanos() / (double) elapsedNanos);
		}
		if (traced > 0) {
			System.out.printf(Locale.ROOT, "migrations   %.1f%% of %,d sampled parks resumed on another carrier%n",
					100.0 * migrated / traced, traced);
		}
	}
}
