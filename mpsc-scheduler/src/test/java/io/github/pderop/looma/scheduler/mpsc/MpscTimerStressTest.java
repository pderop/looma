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
package io.github.pderop.looma.scheduler.mpsc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Message;
import io.github.pderop.looma.Placement;
import io.github.pderop.looma.SpawnOptions;
import io.github.pderop.looma.impl.TimerProbe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Actors on carrier 0 arm and cancel delayed tells to actors on carrier 1 (the
 * remote path), while those actors arm and cancel delayed tells to themselves
 * (the local path). Some timers are left to fire. The wheels fire all the time.
 * Each batch of arms is one mailbox thread: an actor asks for its next batch
 * with a delayed tell, so its mailbox thread ends and the next one binds the
 * wheel again.
 * <p>
 * At the end no timer is left, no cancelled timer was delivered, and every
 * other timer was delivered exactly once.
 */
class MpscTimerStressTest {

	private static final int ACTORS_PER_CARRIER = 4;
	private static final int ARMS_PER_KICK = 50;
	private static final long RUN_MILLIS = 2_500;
	private static final int MAX_TIMERS = 4_000_000;

	private record Kick() implements Message {
	}

	private record Tag(int id) implements Message {
	}

	private ActorSystem system;

	private final AtomicInteger nextId = new AtomicInteger();
	/** 1 when cancel returned true for the timer. */
	private final AtomicIntegerArray cancelled = new AtomicIntegerArray(MAX_TIMERS);
	private final AtomicIntegerArray deliveries = new AtomicIntegerArray(MAX_TIMERS);
	/** Batches of the actors on carrier 1 that ran on the direct path. */
	private final AtomicInteger localBatches = new AtomicInteger();

	@BeforeEach
	void setUp() {
		system = ActorSystem.builder().timerTick(Duration.ofMillis(2)).build();
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		system.shutdown();
		assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
	}

	@Test
	void remoteAndLocalArmAndCancelLeaveNoTimerAndNoLostOrDuplicateMessage() throws Exception {
		assumeTrue(system.carrierCount() >= 2, "needs two carriers");
		CountDownLatch finished = new CountDownLatch(2 * ACTORS_PER_CARRIER);
		startWheelOfCarrierOne();
		long endNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RUN_MILLIS);

		List<ActorRef> receivers = new ArrayList<>();
		for (int i = 0; i < ACTORS_PER_CARRIER; i++) {
			receivers.add(spawn("b-" + i, 1, null, endNanos, finished));
		}
		List<ActorRef> senders = new ArrayList<>();
		for (int i = 0; i < ACTORS_PER_CARRIER; i++) {
			senders.add(spawn("a-" + i, 0, receivers.get(i), endNanos, finished));
		}
		senders.forEach(ref -> ref.tell(new Kick()));
		receivers.forEach(ref -> ref.tell(new Kick()));

		assertTrue(finished.await(30, TimeUnit.SECONDS), "actors did not finish arming");
		int armed = Math.min(nextId.get(), MAX_TIMERS);
		int expected = IntStream.range(0, armed).map(id -> 1 - cancelled.get(id)).sum();
		assertTrue(expected > 0, "no timer was left to fire");
		assertTrue(localBatches.get() > 0, "no batch took the direct path");

		// Delays are short, so every timer left alive is delivered soon.
		long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < giveUp && (delivered(armed) < expected || TimerProbe.pendingTimers(system) != 0)) {
			Thread.sleep(10);
		}

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

	/** Starts the wheel of carrier 1 with one timer, and waits until it is sent. */
	private void startWheelOfCarrierOne() throws Exception {
		CountDownLatch fired = new CountDownLatch(1);
		ActorRef warm = system.spawn("warm", () -> (message, sender, context) -> fired.countDown(),
				SpawnOptions.placedOn(Placement.carrier(1)));
		warm.tell(new Kick(), 1, TimeUnit.MILLISECONDS);
		assertTrue(fired.await(10, TimeUnit.SECONDS), "the wheel of carrier 1 did not start");
	}

	/**
	 * An actor that arms in batches until the end time. With a remote target it
	 * arms on that target, else on itself.
	 */
	private ActorRef spawn(String name, int carrier, ActorRef remote, long endNanos, CountDownLatch finished) {
		return system.spawn(name, () -> (message, sender, context) -> {
			if (message instanceof Tag tag) {
				deliveries.incrementAndGet(tag.id());
				return;
			}
			ActorRef target = remote != null ? remote : context.self();
			if (remote == null && TimerProbe.mailboxThreadBindsItsCarrier()) {
				localBatches.incrementAndGet();
			}
			for (int i = 0; i < ARMS_PER_KICK; i++) {
				armOne(target);
			}
			if (System.nanoTime() < endNanos && nextId.get() < MAX_TIMERS - 2 * ACTORS_PER_CARRIER * ARMS_PER_KICK) {
				// A delayed tell, so this mailbox thread ends and the next batch binds again.
				context.self().tell(new Kick(), 0, TimeUnit.MILLISECONDS);
			} else {
				finished.countDown();
			}
		}, SpawnOptions.placedOn(Placement.carrier(carrier)));
	}

	/** Two timers out of three are cancelled, some at once, some after a delay. */
	private void armOne(ActorRef target) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		int id = nextId.getAndIncrement();
		ScheduledFuture<?> timer = target.tell(new Tag(id), random.nextInt(0, 20), TimeUnit.MILLISECONDS);
		int choice = random.nextInt(3);
		if (choice == 0) {
			return;
		}
		if (choice == 2) {
			LockSupport.parkNanos(random.nextInt(1_000, 5_000));
		}
		if (timer.cancel(false)) {
			cancelled.set(id, 1);
		}
	}
}
