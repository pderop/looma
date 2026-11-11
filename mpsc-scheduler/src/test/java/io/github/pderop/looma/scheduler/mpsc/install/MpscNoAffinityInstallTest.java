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
package io.github.pderop.looma.scheduler.mpsc.install;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Carriers;
import io.github.pderop.looma.Message;
import io.github.pderop.looma.impl.TimerProbe;
import io.github.pderop.looma.spi.ActorScheduler;

import static io.github.pderop.looma.scheduler.mpsc.install.LinuxThreads.TASKS;
import static io.github.pderop.looma.scheduler.mpsc.install.LinuxThreads.cpusAllowed;
import static io.github.pderop.looma.scheduler.mpsc.install.LinuxThreads.name;
import static io.github.pderop.looma.scheduler.mpsc.install.LinuxThreads.tasks;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code Carriers.mpsc(n).affinity(false)}: the MPSC scheduler, with no actor
 * kept on its carrier. One fresh JVM for this class.
 *
 * <p>
 * Every test asks for {@link #CARRIERS} first: the first one to run installs
 * the pool, and the others find it.
 */
class MpscNoAffinityInstallTest {

	private static final Carriers.Mpsc CARRIERS = Carriers.mpsc(3).affinity(false);

	private static final int ACTORS = 6;

	private static final int ARMS_PER_KICK = 50;

	private static final long RUN_MILLIS = 1_500;

	private static final int MAX_TIMERS = 2_000_000;

	private record Kick() implements Message {
	}

	private record Tag(int id) implements Message {
	}

	private ActorSystem system;

	private final AtomicInteger nextId = new AtomicInteger();

	/** 1 when cancel returned true for the timer. */
	private final AtomicIntegerArray cancelled = new AtomicIntegerArray(MAX_TIMERS);

	private final AtomicIntegerArray deliveries = new AtomicIntegerArray(MAX_TIMERS);

	/** Batches whose mailbox thread bound the wheel of its carrier. */
	private final AtomicInteger boundBatches = new AtomicInteger();

	@AfterEach
	void shutdown() throws InterruptedException {
		if (system != null) {
			system.shutdown();
			assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
		}
	}

	@Test
	void installsThePoolAskedFor() {
		system = ActorSystem.builder().carriers(CARRIERS).build();

		assertEquals(3, system.carrierCount());
		assertEquals("MpscActorScheduler", system.scheduler().getClass().getSimpleName());
		assertEquals("true", System.getProperty("jdk.virtualThreadScheduler.useMpsc"));
		assertEquals("true", System.getProperty("jdk.virtualThreadScheduler.mpscSteal"));
		assertEquals("3", System.getProperty("jdk.virtualThreadScheduler.parallelism"));
		assertFalse(system.scheduler().mailboxThreadsStayOnCarrier());
	}

	/** A mailbox thread is made like an offloaded one: with no affinity. */
	@Test
	void mailboxThreadsHaveNoAffinity() {
		system = ActorSystem.builder().carriers(CARRIERS).build();
		ActorScheduler scheduler = system.scheduler();

		for (int index = 0; index < 3; index++) {
			assertSame(scheduler.vThreadFactory(index), scheduler.mailboxThreadFactory(index),
					"index " + index + " has a factory of its own");
		}
	}

	/**
	 * Without carriers(...), build() finds the pool installed, without affinity.
	 */
	@Test
	void aSystemWithoutCarriersFindsThePool() {
		ActorSystem.builder().carriers(CARRIERS).build().shutdown();

		system = ActorSystem.builder().build();

		assertEquals(3, system.carrierCount());
		assertEquals("MpscActorScheduler", system.scheduler().getClass().getSimpleName());
		assertFalse(system.scheduler().mailboxThreadsStayOnCarrier());
	}

	@Test
	void aPoolWithAffinityIsRefused() {
		ActorSystem.builder().carriers(CARRIERS).build().shutdown();

		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> ActorSystem.builder().carriers(Carriers.mpsc(3)).build());

		assertTrue(e.getMessage().contains("already installed"), e.getMessage());
	}

	@Test
	void pinsEachCarrierToItsOwnCpu() {
		assumeTrue(Files.isDirectory(TASKS), "not Linux");
		assumeTrue(Runtime.getRuntime().availableProcessors() >= 3, "fewer CPUs than carriers");
		system = ActorSystem.builder().carriers(CARRIERS).build();

		// Linux keeps 15 characters of a thread name.
		Set<String> cpus = tasks().filter(task -> name(task).startsWith("mpsc-carrier"))
				.map(task -> cpusAllowed(task).substring("Cpus_allowed_list:".length()).trim())
				.collect(Collectors.toSet());

		assertEquals(3, cpus.size(), "the carriers are not on three CPUs: " + cpus);
		cpus.forEach(cpu -> assertTrue(cpu.matches("[0-9]+"), "a carrier is not pinned to one CPU: " + cpu));
	}

	/**
	 * Actors arm delayed tells, to themselves and to another actor, and cancel two
	 * out of three. At the end no timer is left, no cancelled timer was delivered,
	 * every other timer was delivered once, and no mailbox thread bound a wheel.
	 */
	@Test
	void timersAreDeliveredOnceAndNoMailboxThreadBindsAWheel() throws Exception {
		system = ActorSystem.builder().carriers(CARRIERS).timerTick(Duration.ofMillis(2)).build();
		CountDownLatch finished = new CountDownLatch(ACTORS);
		long endNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RUN_MILLIS);

		List<ActorRef> actors = new ArrayList<>();
		for (int i = 0; i < ACTORS; i++) {
			int peer = (i + 1) % ACTORS;
			actors.add(system.spawn("a-" + i, () -> (message, sender, context) -> {
				if (message instanceof Tag tag) {
					deliveries.incrementAndGet(tag.id());
					return;
				}
				if (TimerProbe.mailboxThreadBindsItsCarrier(context)) {
					boundBatches.incrementAndGet();
				}
				for (int k = 0; k < ARMS_PER_KICK; k++) {
					armOne(k % 2 == 0 ? context.self() : actors.get(peer));
				}
				if (System.nanoTime() < endNanos && nextId.get() < MAX_TIMERS - ACTORS * ARMS_PER_KICK) {
					context.self().tell(new Kick(), 0, TimeUnit.MILLISECONDS);
				} else {
					finished.countDown();
				}
			}));
		}
		actors.forEach(ref -> ref.tell(new Kick()));

		assertTrue(finished.await(30, TimeUnit.SECONDS), "actors did not finish arming");
		int armed = Math.min(nextId.get(), MAX_TIMERS);
		int expected = IntStream.range(0, armed).map(id -> 1 - cancelled.get(id)).sum();
		assertTrue(expected > 0, "no timer was left to fire");

		long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < giveUp && (delivered(armed) < expected || TimerProbe.pendingTimers(system) != 0)) {
			Thread.sleep(10);
		}

		assertEquals(0, boundBatches.get(), "a mailbox thread bound a wheel");
		assertEquals(0, TimerProbe.pendingTimers(system), "timers left in the wheels");
		for (int id = 0; id < armed; id++) {
			int wanted = 1 - cancelled.get(id);
			assertEquals(wanted, deliveries.get(id),
					(wanted == 0 ? "cancelled timer delivered, id " : "timer not delivered once, id ") + id);
		}
	}

	private int delivered(int armed) {
		return IntStream.range(0, armed).map(deliveries::get).sum();
	}

	private void armOne(ActorRef target) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		int id = nextId.getAndIncrement();
		ScheduledFuture<?> timer = target.tell(new Tag(id), random.nextInt(0, 20), TimeUnit.MILLISECONDS);
		if (random.nextInt(3) != 0 && timer.cancel(false)) {
			cancelled.set(id, 1);
		}
	}
}
