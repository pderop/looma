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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;

class TimerWheelTest {

	private static final long TICK = TimeUnit.MILLISECONDS.toNanos(10);
	private static final long START = 1_000_000L;

	private static final ActorRef TARGET = new ActorRef() {
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
		}

		@Override
		public <R extends Message> CompletableFuture<R> ask(Message message, Class<R> replyType) {
			throw new UnsupportedOperationException();
		}
	};
	private static final Message MESSAGE = new Message() {
	};

	private final TimerWheel wheel = new TimerWheel(START, TICK);
	private final List<Timer> fired = new ArrayList<>();

	private static Timer timerAt(long deadline) {
		return new Timer(TARGET, MESSAGE, null, deadline, 0);
	}

	private Timer addAfterTicks(double ticks) {
		Timer timer = timerAt(START + (long) (ticks * TICK));
		wheel.add(timer);
		return timer;
	}

	private void expireAtTick(long tick) {
		wheel.expire(START + tick * TICK, fired::add);
	}

	@Test
	void firesAtItsTickAndNotBefore() {
		Timer timer = addAfterTicks(5);
		expireAtTick(4);
		assertTrue(fired.isEmpty());
		wheel.expire(START + 5 * TICK - 1, fired::add);
		assertTrue(fired.isEmpty());
		expireAtTick(5);
		assertEquals(List.of(timer), fired);
		assertTrue(wheel.isEmpty());
	}

	@Test
	void deadlineBetweenTicksFiresAtTheNextTick() {
		Timer timer = addAfterTicks(5.2);
		expireAtTick(5);
		assertTrue(fired.isEmpty());
		expireAtTick(6);
		assertEquals(List.of(timer), fired);
	}

	@Test
	void pastDeadlineFiresAtTheNextTick() {
		expireAtTick(3);
		Timer timer = addAfterTicks(1);
		assertTrue(fired.isEmpty());
		expireAtTick(4);
		assertEquals(List.of(timer), fired);
	}

	@Test
	void delayLongerThanOneTurnWaitsForItsTurn() {
		Timer timer = addAfterTicks(TimerWheel.SLOTS + 7);
		Timer other = addAfterTicks(7);
		expireAtTick(7);
		assertEquals(List.of(other), fired);
		expireAtTick(TimerWheel.SLOTS + 6);
		assertEquals(List.of(other), fired);
		expireAtTick(TimerWheel.SLOTS + 7);
		assertEquals(List.of(other, timer), fired);
		assertTrue(wheel.isEmpty());
	}

	@Test
	void cancelledTimerNeverFiresAndIsDropped() {
		Timer timer = addAfterTicks(3);
		assertTrue(timer.cancel(false));
		assertFalse(wheel.isEmpty());
		expireAtTick(3);
		assertTrue(fired.isEmpty());
		assertTrue(wheel.isEmpty());
	}

	@Test
	void severalTimersInOneSlotFireWhenEachIsDue() {
		Timer first = addAfterTicks(4);
		Timer second = addAfterTicks(4);
		Timer nextTurn = addAfterTicks(4 + TimerWheel.SLOTS);
		expireAtTick(4);
		assertEquals(List.of(first, second), fired);
		assertFalse(wheel.isEmpty());
		expireAtTick(4 + TimerWheel.SLOTS);
		assertEquals(List.of(first, second, nextTurn), fired);
	}

	@Test
	void pauseOfSeveralTicksIsCaughtUp() {
		Timer a = addAfterTicks(2);
		Timer b = addAfterTicks(5);
		Timer c = addAfterTicks(9);
		Timer late = addAfterTicks(20);
		expireAtTick(10);
		assertEquals(List.of(a, b, c), fired);
		expireAtTick(20);
		assertEquals(List.of(a, b, c, late), fired);
	}

	@Test
	void pauseOfMoreThanOneTurnFiresEveryDueTimer() {
		Timer a = addAfterTicks(3);
		Timer b = addAfterTicks(TimerWheel.SLOTS + 100);
		Timer later = addAfterTicks(3 * TimerWheel.SLOTS);
		expireAtTick(2 * TimerWheel.SLOTS);
		assertEquals(List.of(a, b), fired);
		expireAtTick(3 * TimerWheel.SLOTS);
		assertEquals(List.of(a, b, later), fired);
	}

	@Test
	void consumerCanAddTheTimerAgain() {
		Timer timer = new Timer(TARGET, MESSAGE, null, START + 2 * TICK, 2 * TICK);
		wheel.add(timer);
		for (long tick = 1; tick <= 6; tick++) {
			wheel.expire(START + tick * TICK, due -> {
				fired.add(due);
				due.rearm();
				wheel.add(due);
			});
		}
		assertEquals(3, fired.size());
		assertFalse(wheel.isEmpty());
	}

	@Test
	void nextTickNanosIsTheTimeToTheNextBoundary() {
		assertEquals(TICK, wheel.nextTickNanos(START));
		assertEquals(TICK - 3, wheel.nextTickNanos(START + 3));
		assertEquals(TICK, wheel.nextTickNanos(START + TICK));
	}

	@Test
	void cancelAllCancelsTimersAndEmptiesTheWheel() {
		Timer a = addAfterTicks(2);
		Timer b = addAfterTicks(TimerWheel.SLOTS + 2);
		wheel.cancelAll();
		assertTrue(wheel.isEmpty());
		assertTrue(a.isCancelled());
		assertTrue(b.isCancelled());
		expireAtTick(2 * TimerWheel.SLOTS);
		assertTrue(fired.isEmpty());
	}

	@Test
	void timerCancelAfterSentReturnsFalse() {
		Timer timer = addAfterTicks(1);
		assertTrue(timer.markScheduled());
		assertTrue(timer.markSent());
		assertTrue(timer.isDone());
		assertFalse(timer.cancel(false));
		timer.completeSent();
		assertTrue(timer.isDone());
		assertFalse(timer.isCancelled());
	}

	@Test
	void timerCancelIsIdempotent() {
		Timer timer = addAfterTicks(1);
		assertTrue(timer.cancel(false));
		assertTrue(timer.cancel(false));
		assertTrue(timer.isCancelled());
	}

	@Test
	void removeTakesTheTimerOutAtOnce() {
		Timer first = addAfterTicks(4);
		Timer middle = addAfterTicks(4);
		Timer last = addAfterTicks(4);
		assertEquals(3, wheel.size());

		wheel.remove(middle);
		assertEquals(2, wheel.size());
		assertEquals(-1, middle.slot);
		assertNull(middle.prev);
		assertNull(middle.next);

		expireAtTick(4);
		assertEquals(List.of(first, last), fired);
		assertTrue(wheel.isEmpty());
	}

	@Test
	void removeOfTheOnlyTimerLeavesTheSlotEmpty() {
		Timer timer = addAfterTicks(7);
		wheel.remove(timer);
		assertTrue(wheel.isEmpty());

		Timer other = addAfterTicks(7);
		expireAtTick(7);
		assertEquals(List.of(other), fired);
	}

	@Test
	void removeOfTheFirstAndLastKeepsTheOthersInOrder() {
		Timer a = addAfterTicks(3);
		Timer b = addAfterTicks(3);
		Timer c = addAfterTicks(3);
		wheel.remove(a);
		wheel.remove(c);
		expireAtTick(3);
		assertEquals(List.of(b), fired);
	}

	@Test
	void removeTwiceOrRemoveOfAnUnknownTimerDoesNothing() {
		Timer timer = addAfterTicks(2);
		Timer other = addAfterTicks(2);
		wheel.remove(timer);
		wheel.remove(timer);
		wheel.remove(timerAt(START));
		assertEquals(1, wheel.size());
		expireAtTick(2);
		assertEquals(List.of(other), fired);
	}

	@Test
	void aTimerCanBeAddedAgainAfterRemove() {
		Timer timer = addAfterTicks(2);
		wheel.remove(timer);
		wheel.add(timer);
		expireAtTick(2);
		assertEquals(List.of(timer), fired);
	}

	/**
	 * A timer fires at the first tick boundary at or after its deadline: not at the
	 * boundary before, and not one boundary later.
	 */
	@Test
	void aTimerFiresAtTheFirstBoundaryAtOrAfterItsDeadline() {
		long[] offsetsAfterBoundaryFour = {0, 1, TICK / 2, TICK - 1, TICK};
		long[] expectedTick = {4, 5, 5, 5, 5};
		for (int i = 0; i < offsetsAfterBoundaryFour.length; i++) {
			TimerWheel fresh = new TimerWheel(START, TICK);
			List<Timer> out = new ArrayList<>();
			// The wheel has already passed tick 3 when the timer is added.
			fresh.expire(START + 3 * TICK, out::add);
			Timer timer = timerAt(START + 4 * TICK + offsetsAfterBoundaryFour[i]);
			fresh.add(timer);

			fresh.expire(START + expectedTick[i] * TICK - 1, out::add);
			assertTrue(out.isEmpty(), "fired before tick " + expectedTick[i] + " for offset " + i);
			fresh.expire(START + expectedTick[i] * TICK, out::add);
			assertEquals(List.of(timer), out, "not fired at tick " + expectedTick[i] + " for offset " + i);
		}
	}

	@Test
	void aDeadlineBetweenTwoBoundariesFiresAtTheLaterOneEvenWhenAddedJustAfterAnExpire() {
		expireAtTick(6);
		// Added right after the tick-6 boundary; the deadline is in the middle of tick
		// 6 to 7.
		Timer timer = timerAt(START + 6 * TICK + TICK / 2);
		wheel.add(timer);
		wheel.expire(START + 7 * TICK - 1, fired::add);
		assertTrue(fired.isEmpty());
		wheel.expire(START + 7 * TICK, fired::add);
		assertEquals(List.of(timer), fired);
	}

	@Test
	void anErrorInTheMiddleOfABatchCancelsOnlyTheTimersNotFiredYet() {
		Timer periodic = new Timer(TARGET, MESSAGE, null, START + TICK, TICK);
		Timer failing = timerAt(START + TICK);
		Timer after = timerAt(START + TICK);
		wheel.add(periodic);
		wheel.add(failing);
		wheel.add(after);
		Error boom = new AssertionError("boom");
		Error thrown = null;
		try {
			wheel.expire(START + TICK, timer -> {
				if (timer == failing) {
					throw boom;
				}
				if (timer.isPeriodic()) {
					timer.rearm();
					wheel.add(timer);
				}
			});
		} catch (Error e) {
			thrown = e;
		}
		assertTrue(thrown == boom);
		assertFalse(periodic.isCancelled(), "a periodic timer fired before the error must keep running");
		assertTrue(after.isCancelled(), "a timer after the error was not fired: it ends cancelled");
		List<Timer> next = new ArrayList<>();
		wheel.expire(START + 2 * TICK, next::add);
		assertTrue(next.contains(periodic), "the periodic timer fires again");
	}

	@Test
	void aTimerAlreadyInTheWheelCannotBeAddedTwice() {
		Timer timer = timerAt(START + 3 * TICK);
		wheel.add(timer);
		boolean refused = false;
		try {
			wheel.add(timer);
		} catch (IllegalStateException e) {
			refused = true;
		}
		assertTrue(refused);
		assertEquals(1, wheel.size());
	}

	@Test
	void aBigBatchDoesNotStopTheWheel() {
		for (int i = 0; i < 10_000; i++) {
			wheel.add(timerAt(START + TICK));
		}
		List<Timer> fired = new ArrayList<>();
		wheel.expire(START + TICK, fired::add);
		assertEquals(10_000, fired.size());
		wheel.add(timerAt(START + 2 * TICK));
		List<Timer> next = new ArrayList<>();
		wheel.expire(START + 2 * TICK, next::add);
		assertEquals(1, next.size());
		assertTrue(wheel.isEmpty());
	}
}
