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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A ring of slots, one slot per tick. Timers wait in the slot of their deadline
 * tick. A slot is a doubly linked list, so a timer can leave the wheel at once.
 * <p>
 * Only one thread uses a wheel at a time. It has no clock: the caller passes
 * {@code now} (a {@link System#nanoTime()} value) to every call. Ticks are
 * counted from the creation time, so they are never negative. A timer fires on
 * the first tick at or after its deadline, never before. A timer farther away
 * than one turn stays in its slot until the wheel comes back to it. A cancelled
 * timer that was not removed is dropped when the wheel passes its slot.
 */
final class TimerWheel {

	static final int SLOTS = 512;
	/**
	 * A due list bigger than this is dropped after the firing, to give back its
	 * memory.
	 */
	private static final int DUE_KEPT = 4096;
	private static final VarHandle SIZE;

	static {
		try {
			SIZE = MethodHandles.lookup().findVarHandle(TimerWheel.class, "size", int.class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private final long startNanos;
	private final long tickNanos;
	private final Timer[] heads = new Timer[SLOTS];
	private final Timer[] tails = new Timer[SLOTS];
	/** The last tick already processed. */
	private long currentTick;
	/**
	 * Written by the wheel's thread only. Read with {@link #size()} from anywhere.
	 */
	private int size;
	/** Due timers of one expire call. Cleared after the firing. */
	private List<Timer> due = new ArrayList<>();

	TimerWheel(long startNanos, long tickNanos) {
		if (tickNanos <= 0) {
			throw new IllegalArgumentException("tickNanos must be positive: " + tickNanos);
		}
		this.startNanos = startNanos;
		this.tickNanos = tickNanos;
	}

	/**
	 * A deadline that is already past goes to the next tick. A timer that is
	 * already in a slot is a bug of the caller.
	 */
	void add(Timer timer) {
		if (timer.slot >= 0) {
			throw new IllegalStateException("timer already in the wheel");
		}
		// Computed once here, so a pass over a slot compares longs only.
		long deadline = deadlineTick(timer);
		timer.deadlineTick = deadline;
		long tick = Math.max(deadline, currentTick + 1);
		int index = (int) (tick & (SLOTS - 1));
		Timer tail = tails[index];
		timer.prev = tail;
		timer.next = null;
		timer.slot = index;
		if (tail == null) {
			heads[index] = timer;
		} else {
			tail.next = timer;
		}
		tails[index] = timer;
		size++;
	}

	/** Takes the timer out of its slot. Does nothing if it is in no slot. */
	void remove(Timer timer) {
		int index = timer.slot;
		if (index < 0) {
			return;
		}
		if (timer.prev == null) {
			heads[index] = timer.next;
		} else {
			timer.prev.next = timer.next;
		}
		if (timer.next == null) {
			tails[index] = timer.prev;
		} else {
			timer.next.prev = timer.prev;
		}
		timer.prev = null;
		timer.next = null;
		timer.slot = -1;
		size--;
	}

	private long deadlineTick(Timer timer) {
		return Math.ceilDiv(timer.deadlineNanos - startNanos, tickNanos);
	}

	/**
	 * Fires every timer that is due at {@code now}, one call of {@code fire} each.
	 * Every tick missed since the last call is visited, so a pause is caught up.
	 * The consumer may add timers, for example to re-arm a periodic one.
	 */
	void expire(long now, Consumer<Timer> fire) {
		long targetTick = Math.floorDiv(now - startNanos, tickNanos);
		if (targetTick <= currentTick) {
			return;
		}
		long firstTick = currentTick + 1;
		long visited = Math.min(targetTick - currentTick, SLOTS);
		currentTick = targetTick;
		// The consumer may re-add timers to the wheel, never to this list.
		int fired = 0;
		try {
			for (long i = 0; i < visited; i++) {
				collectDue((int) ((firstTick + i) & (SLOTS - 1)), targetTick, due);
			}
			for (; fired < due.size(); fired++) {
				fire.accept(due.get(fired));
			}
		} catch (Throwable t) {
			// The failing timer and those after it are out of the wheel and may never
			// fire: they end cancelled, so no future waits forever. A one-shot already
			// sent stays done. Those before were fired, and a periodic one is back in
			// the wheel: it must not be touched.
			for (int i = fired; i < due.size(); i++) {
				due.get(i).discard();
			}
			throw t;
		} finally {
			if (due.size() > DUE_KEPT) {
				due = new ArrayList<>();
			} else {
				due.clear();
			}
		}
	}

	/**
	 * Moves the due timers out of the slot, and drops the cancelled ones and the
	 * periodic ones whose target is stopping, so they do not wait for their next
	 * period.
	 */
	private void collectDue(int index, long targetTick, List<Timer> due) {
		Timer timer = heads[index];
		while (timer != null) {
			Timer following = timer.next;
			if (timer.isCancelled()) {
				remove(timer);
			} else if (timer.isPeriodic() && timer.isTargetStopped()) {
				remove(timer);
				timer.discard();
			} else if (timer.deadlineTick <= targetTick) {
				remove(timer);
				due.add(timer);
			}
			timer = following;
		}
	}

	/** Nanoseconds from {@code now} to the next tick boundary. */
	long nextTickNanos(long now) {
		long elapsed = now - startNanos;
		return (Math.floorDiv(elapsed, tickNanos) + 1) * tickNanos - elapsed;
	}

	/** True when no timer is left, cancelled ones not yet removed included. */
	boolean isEmpty() {
		return size == 0;
	}

	/**
	 * The number of timers in the wheel. A thread other than the wheel's may see an
	 * older value.
	 */
	int size() {
		return (int) SIZE.getOpaque(this);
	}

	/** Cancels every timer and empties the wheel. */
	void cancelAll() {
		for (int index = 0; index < SLOTS; index++) {
			Timer timer = heads[index];
			while (timer != null) {
				Timer following = timer.next;
				timer.prev = null;
				timer.next = null;
				timer.slot = -1;
				timer.discard();
				timer = following;
			}
			heads[index] = null;
			tails[index] = null;
		}
		size = 0;
	}
}
