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
package io.github.pderop.looma.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;
import io.github.pderop.looma.spi.MpscQueue;

class CarrierTimerTest {

	private static final long TICK = TimeUnit.MILLISECONDS.toNanos(1);
	private static final Message MESSAGE = new Message() {
	};

	private final AtomicInteger threadsStarted = new AtomicInteger();
	private final ThreadFactory countingFactory = task -> {
		threadsStarted.incrementAndGet();
		return Thread.ofVirtual().unstarted(task);
	};

	private static final class CountingRef implements ActorRef {
		final AtomicInteger tells = new AtomicInteger();
		volatile boolean failing;
		volatile boolean failingWithError;

		@Override
		public String name() {
			return "target";
		}

		@Override
		public String path() {
			return "/target";
		}

		@Override
		public void tell(Message message, ActorRef sender) {
			if (failing) {
				throw new IllegalStateException("boom");
			}
			if (failingWithError) {
				throw new AssertionError("boom");
			}
			tells.incrementAndGet();
		}

		@Override
		public <R extends Message> CompletableFuture<R> ask(Message message, Class<R> replyType) {
			throw new UnsupportedOperationException();
		}
	}

	private static MpscQueue<Timer> unboundedInbox() {
		return ConcurrentLinkedMpscQueue.<Timer>factory().create();
	}

	private static CarrierTimer newCarrierTimer(ThreadFactory factory) {
		return new CarrierTimer(factory, unboundedInbox(), unboundedInbox(), TICK);
	}

	private static Timer timerIn(CarrierTimer owner, ActorRef target, long delayMillis, long periodMillis) {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
		return new Timer(target, MESSAGE, null, deadline, TimeUnit.MILLISECONDS.toNanos(periodMillis), () -> false,
				owner);
	}

	private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
		long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean() && System.nanoTime() < limit) {
			Thread.sleep(1);
		}
		assertTrue(condition.getAsBoolean(), "condition not reached in time");
	}

	@Test
	void noThreadBeforeTheFirstTimer() throws Exception {
		CarrierTimer carrierTimer = newCarrierTimer(countingFactory);
		assertEquals(0, threadsStarted.get());

		CountingRef target = new CountingRef();
		Timer timer = timerIn(carrierTimer, target, 1, 0);
		carrierTimer.schedule(timer);
		timer.get(5, TimeUnit.SECONDS);

		assertEquals(1, threadsStarted.get());
		assertEquals(1, target.tells.get());
		assertFalse(timer.isCancelled());
		carrierTimer.stop();
	}

	@Test
	void oneThreadServesManyTimers() throws Exception {
		CarrierTimer carrierTimer = newCarrierTimer(countingFactory);
		CountingRef target = new CountingRef();
		var timers = IntStream.range(0, 20).mapToObj(i -> timerIn(carrierTimer, target, 1 + i, 0)).toList();
		timers.forEach(carrierTimer::schedule);
		for (Timer timer : timers) {
			timer.get(5, TimeUnit.SECONDS);
		}
		assertEquals(20, target.tells.get());
		assertEquals(1, threadsStarted.get());
		carrierTimer.stop();
	}

	/**
	 * A tell that throws an Error must not end the wheel thread: a timer armed
	 * afterwards still fires.
	 */
	@Test
	void anErrorInATellDoesNotStopTheWheel() throws Exception {
		CarrierTimer carrierTimer = newCarrierTimer(Thread.ofVirtual().factory());
		CountingRef broken = new CountingRef();
		broken.failingWithError = true;
		Timer first = timerIn(carrierTimer, broken, 1, 0);
		carrierTimer.schedule(first);
		first.get(5, TimeUnit.SECONDS);

		CountingRef target = new CountingRef();
		Timer second = timerIn(carrierTimer, target, 1, 0);
		carrierTimer.schedule(second);
		second.get(5, TimeUnit.SECONDS);

		assertEquals(1, target.tells.get());
		carrierTimer.stop();
	}

	@Test
	void scheduleAfterStopIsCancelled() {
		CarrierTimer carrierTimer = newCarrierTimer(countingFactory);
		carrierTimer.stop();
		CountingRef target = new CountingRef();
		Timer timer = timerIn(carrierTimer, target, 1, 0);
		carrierTimer.schedule(timer);

		assertTrue(timer.isCancelled());
		assertEquals(0, target.tells.get());
		assertEquals(0, threadsStarted.get());
	}

	@Test
	void stopBeforeAnyStartStartsNoThread() {
		CarrierTimer carrierTimer = newCarrierTimer(countingFactory);
		carrierTimer.stop();
		carrierTimer.stop();
		assertEquals(0, threadsStarted.get());
	}

	@Test
	void stopCancelsWaitingTimers() throws Exception {
		CarrierTimer carrierTimer = newCarrierTimer(countingFactory);
		CountingRef target = new CountingRef();
		Timer far = timerIn(carrierTimer, target, 60_000, 0);
		carrierTimer.schedule(far);
		Thread.sleep(20);
		carrierTimer.stop();

		awaitTrue(far::isCancelled);
		assertEquals(0, target.tells.get());
	}

	@Test
	void scheduleRacingStopIsNeverLost() throws Exception {
		for (int round = 0; round < 200; round++) {
			CarrierTimer carrierTimer = newCarrierTimer(Thread.ofVirtual().factory());
			CountingRef target = new CountingRef();
			// Far deadline: a timer can only end cancelled or still waiting.
			Timer timer = timerIn(carrierTimer, target, 60_000, 0);
			CountDownLatch go = new CountDownLatch(1);
			Thread scheduler = Thread.ofPlatform().start(() -> {
				await(go);
				carrierTimer.schedule(timer);
			});
			Thread stopper = Thread.ofPlatform().start(() -> {
				await(go);
				carrierTimer.stop();
			});
			go.countDown();
			scheduler.join();
			stopper.join();

			awaitTrue(timer::isCancelled);
			assertEquals(0, target.tells.get());
		}
	}

	@Test
	void tellThatThrowsDoesNotKillTheWheel() throws Exception {
		CarrierTimer carrierTimer = newCarrierTimer(countingFactory);
		CountingRef broken = new CountingRef();
		broken.failing = true;
		CountingRef healthy = new CountingRef();
		carrierTimer.schedule(timerIn(carrierTimer, broken, 1, 0));
		Timer next = timerIn(carrierTimer, healthy, 5, 0);
		carrierTimer.schedule(next);

		next.get(5, TimeUnit.SECONDS);
		assertEquals(1, healthy.tells.get());
		carrierTimer.stop();
	}

	@Test
	void periodicTimerIsRearmedEachPeriod() throws Exception {
		CarrierTimer carrierTimer = newCarrierTimer(countingFactory);
		CountingRef target = new CountingRef();
		Timer periodic = timerIn(carrierTimer, target, 1, 2);
		carrierTimer.schedule(periodic);

		awaitTrue(() -> target.tells.get() >= 5);
		assertFalse(periodic.isDone());

		periodic.cancel(false);
		Thread.sleep(20);
		int afterCancel = target.tells.get();
		Thread.sleep(30);
		assertEquals(afterCancel, target.tells.get());
		carrierTimer.stop();
	}

	// ---- Local and remote arm and cancel, driven by hand with step() ----

	/** A thread that never runs: the test calls step() itself. */
	private static final ThreadFactory IDLE = task -> Thread.ofVirtual().unstarted(() -> {
	});

	private static long afterOneSecond() {
		return System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
	}

	@Test
	void aLocalArmGoesStraightIntoTheWheel() {
		MpscQueue<Timer> inbox = unboundedInbox();
		CarrierTimer carrierTimer = new CarrierTimer(IDLE, inbox, unboundedInbox(), TICK);
		Timer timer = timerIn(carrierTimer, new CountingRef(), 60_000, 0);

		CarrierTimer.runOnCarrier(carrierTimer, () -> {
			carrierTimer.schedule(timer);
		});

		assertEquals(1, carrierTimer.pending());
		assertTrue(inbox.isEmpty());
	}

	@Test
	void aRemoteArmGoesThroughTheInbox() {
		MpscQueue<Timer> inbox = unboundedInbox();
		CarrierTimer carrierTimer = new CarrierTimer(IDLE, inbox, unboundedInbox(), TICK);
		carrierTimer.schedule(timerIn(carrierTimer, new CountingRef(), 60_000, 0));

		assertEquals(0, carrierTimer.pending());
		assertFalse(inbox.isEmpty());
		carrierTimer.step(System.nanoTime());
		assertEquals(1, carrierTimer.pending());
	}

	/**
	 * Two mailbox threads of one carrier interleave: the first one parks, the
	 * second one binds and ends. The first one no longer matches, so its arm takes
	 * the inbox. A forked thread, which never binds, is in the same case.
	 */
	@Test
	void aMailboxThreadOvertakenByAnotherUsesTheQueues() throws Exception {
		MpscQueue<Timer> inbox = unboundedInbox();
		CarrierTimer carrierTimer = new CarrierTimer(IDLE, inbox, unboundedInbox(), TICK);
		Timer timer = timerIn(carrierTimer, new CountingRef(), 60_000, 0);
		CountDownLatch bound = new CountDownLatch(1);
		CountDownLatch overtaken = new CountDownLatch(1);
		CompletableFuture<Boolean> seen = new CompletableFuture<>();

		Thread first = Thread.ofVirtual().start(() -> CarrierTimer.runOnCarrier(carrierTimer, () -> {
			bound.countDown();
			try {
				overtaken.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			seen.complete(carrierTimer.isCaller());
			carrierTimer.schedule(timer);
		}));
		bound.await();
		Thread.ofVirtual().start(() -> CarrierTimer.runOnCarrier(carrierTimer, () -> {
		})).join();
		overtaken.countDown();
		first.join();

		assertFalse(seen.get(5, TimeUnit.SECONDS));
		assertEquals(0, carrierTimer.pending());
		assertFalse(inbox.isEmpty());
	}

	@Test
	void aLocalCancelRemovesTheTimerAtOnce() {
		CarrierTimer carrierTimer = newCarrierTimer(IDLE);
		Timer timer = timerIn(carrierTimer, new CountingRef(), 60_000, 0);
		CarrierTimer.runOnCarrier(carrierTimer, () -> {
			carrierTimer.schedule(timer);
			assertEquals(1, carrierTimer.pending());
			assertTrue(timer.cancel(false));
			assertEquals(0, carrierTimer.pending());
		});
		assertTrue(timer.isCancelled());
	}

	@Test
	void aRemoteCancelRemovesTheTimerAtTheNextTick() {
		CarrierTimer carrierTimer = newCarrierTimer(IDLE);
		Timer timer = timerIn(carrierTimer, new CountingRef(), 60_000, 0);
		carrierTimer.schedule(timer);
		carrierTimer.step(System.nanoTime());
		assertEquals(1, carrierTimer.pending());

		assertTrue(timer.cancel(false));
		assertEquals(1, carrierTimer.pending());
		carrierTimer.step(System.nanoTime());
		assertEquals(0, carrierTimer.pending());
	}

	@Test
	void aTimerCancelledInTheInboxIsNeverInserted() {
		CarrierTimer carrierTimer = newCarrierTimer(IDLE);
		CountingRef target = new CountingRef();
		Timer timer = timerIn(carrierTimer, target, 1, 0);
		carrierTimer.schedule(timer);
		assertTrue(timer.cancel(false));

		carrierTimer.step(afterOneSecond());
		assertEquals(0, carrierTimer.pending());
		assertEquals(0, target.tells.get());
	}

	@Test
	void aCancelledTimerKeepsNoReferenceToItsMessage() throws Exception {
		CarrierTimer carrierTimer = newCarrierTimer(IDLE);
		Message message = new Message() {
		};
		Timer timer = new Timer(new CountingRef(), message, null, afterOneSecond(), 0, () -> false, carrierTimer);
		carrierTimer.schedule(timer);
		carrierTimer.step(System.nanoTime());
		WeakReference<Message> weak = new WeakReference<>(message);
		message = null;

		timer.cancel(false);
		assertNull(timer.message);
		assertNull(timer.target);
		long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (weak.get() != null && System.nanoTime() < limit) {
			System.gc();
			Thread.sleep(10);
		}
		assertNull(weak.get(), "the message is still referenced");
		assertEquals(1, carrierTimer.pending());
	}

	@Test
	void nothingStaysAfterManyLocalArmAndCancel() {
		CarrierTimer carrierTimer = newCarrierTimer(IDLE);
		CountingRef target = new CountingRef();
		CarrierTimer.runOnCarrier(carrierTimer, () -> {
			for (int i = 0; i < 10_000; i++) {
				timerIn(carrierTimer, target, 30_000, 0).cancel(false);
			}
			for (int i = 0; i < 10_000; i++) {
				Timer timer = timerIn(carrierTimer, target, 30_000, 0);
				carrierTimer.schedule(timer);
				timer.cancel(false);
			}
		});
		assertEquals(0, carrierTimer.pending());
	}

	@Test
	void nothingStaysAfterManyRemoteArmAndCancel() {
		CarrierTimer carrierTimer = newCarrierTimer(IDLE);
		CountingRef target = new CountingRef();
		for (int i = 0; i < 10_000; i++) {
			Timer timer = timerIn(carrierTimer, target, 30_000, 0);
			carrierTimer.schedule(timer);
			timer.cancel(false);
			if (i % 100 == 0) {
				carrierTimer.step(System.nanoTime());
			}
		}
		carrierTimer.step(System.nanoTime());
		assertEquals(0, carrierTimer.pending());
	}

	@Test
	void aLocalArmWakesAWheelThreadThatSleepsWithNoTimer() throws Exception {
		CarrierTimer carrierTimer = newCarrierTimer(Thread.ofVirtual().factory());
		CountingRef target = new CountingRef();
		Timer first = timerIn(carrierTimer, target, 1, 0);
		carrierTimer.schedule(first);
		first.get(5, TimeUnit.SECONDS);
		// The wheel is empty now, so the thread parks with no timeout.
		Thread.sleep(100);

		Timer second = timerIn(carrierTimer, target, 5, 0);
		CarrierTimer.runOnCarrier(carrierTimer, () -> {
			carrierTimer.schedule(second);
		});
		second.get(5, TimeUnit.SECONDS);
		assertEquals(2, target.tells.get());
		carrierTimer.stop();
	}

	@Test
	void aTimerDueAfterStopIsCancelledNotSent() {
		CarrierTimer carrierTimer = newCarrierTimer(IDLE);
		CountingRef target = new CountingRef();
		Timer timer = timerIn(carrierTimer, target, 1, 0);
		carrierTimer.schedule(timer);
		carrierTimer.stop();

		carrierTimer.step(afterOneSecond());
		assertTrue(timer.isCancelled());
		assertEquals(0, target.tells.get());
	}

	/**
	 * A thread that cannot start ends the carrier: its timers are cancelled, and
	 * schedule does not throw.
	 */
	@Test
	void aWheelThreadThatCannotStartCancelsTheTimers() {
		ThreadFactory refusing = task -> {
			throw new IllegalStateException("no thread");
		};
		MpscQueue<Timer> inbox = unboundedInbox();
		CarrierTimer carrierTimer = new CarrierTimer(refusing, inbox, unboundedInbox(), TICK);
		Timer first = timerIn(carrierTimer, new CountingRef(), 60_000, 0);
		carrierTimer.schedule(first);
		Timer second = timerIn(carrierTimer, new CountingRef(), 60_000, 0);
		carrierTimer.schedule(second);

		assertTrue(first.isCancelled());
		assertTrue(second.isCancelled());
		assertTrue(inbox.isEmpty());
	}

	/**
	 * A round that throws does not end the wheel thread: a timer armed afterwards
	 * still fires.
	 */
	@Test
	void aRoundThatThrowsDoesNotStopTheWheel() throws Exception {
		AtomicBoolean failNextPoll = new AtomicBoolean(true);
		ConcurrentLinkedQueue<Timer> queue = new ConcurrentLinkedQueue<>();
		MpscQueue<Timer> failingOnce = new MpscQueue<>() {
			@Override
			public void offer(Timer item) {
				queue.offer(item);
			}

			@Override
			public Timer poll() {
				if (failNextPoll.compareAndSet(true, false)) {
					throw new OutOfMemoryError("test");
				}
				return queue.poll();
			}

			@Override
			public boolean isEmpty() {
				return queue.isEmpty();
			}
		};
		CarrierTimer carrierTimer = new CarrierTimer(Thread.ofVirtual().factory(), failingOnce, unboundedInbox(), TICK);
		CountingRef target = new CountingRef();
		Timer timer = timerIn(carrierTimer, target, 1, 0);
		carrierTimer.schedule(timer);
		timer.get(5, TimeUnit.SECONDS);

		assertEquals(1, target.tells.get());
		carrierTimer.stop();
	}

	/** On its own carrier, an arm after stop is cancelled at once. */
	@Test
	void aLocalArmAfterStopIsCancelled() {
		CarrierTimer carrierTimer = newCarrierTimer(IDLE);
		Timer started = timerIn(carrierTimer, new CountingRef(), 60_000, 0);
		carrierTimer.schedule(started);
		carrierTimer.stop();
		Timer late = timerIn(carrierTimer, new CountingRef(), 60_000, 0);

		CarrierTimer.runOnCarrier(carrierTimer, () -> carrierTimer.schedule(late));

		assertTrue(late.isCancelled());
	}

	/**
	 * Periodic timers on a running wheel, cancelled from several threads at once:
	 * each one stops, at most one message after its cancel, and none is left.
	 */
	@Test
	void periodicTimersCancelledFromManyThreadsAllStop() throws Exception {
		CarrierTimer carrierTimer = newCarrierTimer(Thread.ofVirtual().factory());
		var targets = IntStream.range(0, 200).mapToObj(i -> new CountingRef()).toList();
		var timers = targets.stream().map(target -> timerIn(carrierTimer, target, 1, 1)).toList();
		timers.forEach(carrierTimer::schedule);
		Thread.sleep(20);

		var cancellers = IntStream.range(0, 4)
				.mapToObj(part -> Thread.ofVirtual()
						.start(() -> IntStream
								.range(0, timers.size())
								.filter(i -> i % 4 == part)
								.forEach(i -> timers.get(i).cancel(false))))
				.toList();
		for (Thread canceller : cancellers) {
			canceller.join();
		}
		var afterCancel = targets.stream().mapToInt(target -> target.tells.get()).toArray();
		Thread.sleep(30);

		assertTrue(timers.stream().allMatch(Timer::isCancelled));
		assertTrue(IntStream.range(0, targets.size()).allMatch(i -> targets.get(i).tells.get() <= afterCancel[i] + 1),
				"a periodic timer kept sending after its cancel");
		awaitTrue(() -> carrierTimer.pending() == 0);
		carrierTimer.stop();
	}

	/**
	 * A periodic timer whose target is stopping leaves the wheel when its slot
	 * comes up, before its period.
	 */
	@Test
	void aPeriodicTimerOfAStoppedTargetLeavesBeforeItsPeriod() throws Exception {
		CarrierTimer carrierTimer = newCarrierTimer(Thread.ofVirtual().factory());
		AtomicBoolean stopped = new AtomicBoolean();
		long deadline = System.nanoTime() + TimeUnit.HOURS.toNanos(1);
		Timer timer = new Timer(new CountingRef(), MESSAGE, null, deadline, TimeUnit.HOURS.toNanos(1), stopped::get,
				carrierTimer);
		carrierTimer.schedule(timer);
		awaitTrue(() -> carrierTimer.pending() == 1);
		stopped.set(true);

		// The wheel passes every slot within one turn: 512 ticks of 1 ms.
		awaitTrue(() -> carrierTimer.pending() == 0);
		assertTrue(timer.isCancelled());
		carrierTimer.stop();
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
