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
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;

/**
 * One delayed or periodic tell, waiting in a {@link TimerWheel}.
 * <p>
 * It is also the future given back to the caller. The future is done when a
 * one-shot timer starts to be sent, or when any timer is cancelled. Get waits
 * until the tell has returned, or until the timer is cancelled.
 * <p>
 * The state goes from NEW (not yet in a wheel) to SCHEDULED (in a wheel), then
 * to SENT (one-shot only, the tell is running) and DONE (the tell has
 * returned), or to CANCELLED. A cancelled timer drops its message and target at
 * once. The links and the slot belong to the wheel, which is used by one
 * thread.
 * <p>
 * Cancel creates no object and no exception. The exception of {@link #get()} is
 * created in get. The waiting latch is created by the first caller of get that
 * has to wait.
 */
final class Timer implements ScheduledFuture<Void> {

	static final int NEW = 0;
	static final int SCHEDULED = 1;
	static final int SENT = 2;
	static final int CANCELLED = 3;
	static final int DONE = 4;

	private static final VarHandle STATE;
	private static final VarHandle WAITER;

	static {
		try {
			MethodHandles.Lookup lookup = MethodHandles.lookup();
			STATE = lookup.findVarHandle(Timer.class, "state", int.class);
			WAITER = lookup.findVarHandle(Timer.class, "waiter", CountDownLatch.class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	/** Null once the timer is cancelled, or a one-shot sent. */
	volatile ActorRef target;
	/** Null once the timer is cancelled, or a one-shot sent. */
	volatile Message message;
	/** May be null. Null once the timer is cancelled, or a one-shot sent. */
	volatile ActorRef sender;
	/** Zero means the timer fires once. */
	final long periodNanos;
	/** Absolute time on the {@link System#nanoTime()} clock. */
	volatile long deadlineNanos;
	/** A periodic timer cancels itself when this says the target is gone. */
	private volatile BooleanSupplier targetStopped;
	/** The carrier timer that keeps this timer. Null in wheel-only tests. */
	private final CarrierTimer owner;

	@SuppressWarnings("unused") // Used through STATE.
	private volatile int state = NEW;
	/** Null until a caller of get has to wait. */
	@SuppressWarnings("unused") // Used through WAITER.
	private volatile CountDownLatch waiter;

	// Owned by the wheel.
	Timer prev;
	Timer next;
	/** The slot this timer waits in, or -1 when it is in no wheel. */
	int slot = -1;
	/** The tick of the deadline, set when the timer goes into a wheel. */
	long deadlineTick;

	Timer(ActorRef target, Message message, ActorRef sender, long deadlineNanos, long periodNanos) {
		this(target, message, sender, deadlineNanos, periodNanos, () -> false, null);
	}

	Timer(ActorRef target, Message message, ActorRef sender, long deadlineNanos, long periodNanos,
			BooleanSupplier targetStopped, CarrierTimer owner) {
		this.target = Objects.requireNonNull(target, "target");
		this.message = Objects.requireNonNull(message, "message");
		this.sender = sender;
		this.deadlineNanos = deadlineNanos;
		this.periodNanos = periodNanos;
		this.targetStopped = Objects.requireNonNull(targetStopped, "targetStopped");
		this.owner = owner;
	}

	boolean isTargetStopped() {
		BooleanSupplier stopped = targetStopped;
		return stopped != null && stopped.getAsBoolean();
	}

	boolean isPeriodic() {
		return periodNanos > 0;
	}

	/**
	 * Moves the deadline one period ahead. The owner then adds the timer to the
	 * wheel again.
	 */
	void rearm() {
		deadlineNanos += periodNanos;
	}

	/** NEW to SCHEDULED. False when the timer was cancelled meanwhile. */
	boolean markScheduled() {
		return STATE.compareAndSet(this, NEW, SCHEDULED);
	}

	/**
	 * SCHEDULED to SENT. False when the timer was cancelled first, and then nothing
	 * must be sent. After a true result the caller tells the message and calls
	 * {@link #completeSent()}.
	 */
	boolean markSent() {
		if (!STATE.compareAndSet(this, SCHEDULED, SENT)) {
			return false;
		}
		release();
		return true;
	}

	/** Completes the future once the message is handed over or dropped. */
	void completeSent() {
		state = DONE;
		wakeWaiter();
	}

	private void wakeWaiter() {
		CountDownLatch latch = waiter;
		if (latch != null) {
			latch.countDown();
		}
	}

	/**
	 * Cancels without telling the owner. For the thread that already holds the
	 * timer, or when the owner is stopped.
	 */
	void discard() {
		int current;
		do {
			current = state;
			if (current == SENT || current == CANCELLED || current == DONE) {
				return;
			}
		} while (!STATE.compareAndSet(this, current, CANCELLED));
		release();
		wakeWaiter();
	}

	/**
	 * Cancels the timer. {@code true} if it is cancelled, also when it already was.
	 * {@code false} once the message has been sent.
	 */
	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		while (true) {
			int current = state;
			if (current == SENT || current == DONE) {
				return false;
			}
			if (current == CANCELLED) {
				return true;
			}
			if (STATE.compareAndSet(this, current, CANCELLED)) {
				release();
				wakeWaiter();
				if (current == SCHEDULED && owner != null) {
					owner.timerCancelled(this);
				}
				return true;
			}
		}
	}

	private void release() {
		message = null;
		target = null;
		sender = null;
		targetStopped = null;
	}

	@Override
	public boolean isCancelled() {
		return state == CANCELLED;
	}

	@Override
	public boolean isDone() {
		return state >= SENT;
	}

	/**
	 * True once get can return: the tell has returned, or the timer is cancelled.
	 */
	private boolean isFinished() {
		int current = state;
		return current == DONE || current == CANCELLED;
	}

	@Override
	public Void get() throws InterruptedException, ExecutionException {
		if (!isFinished()) {
			waiterLatch().await();
		}
		return result();
	}

	@Override
	public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		if (!isFinished() && !waiterLatch().await(timeout, unit)) {
			throw new TimeoutException();
		}
		return result();
	}

	/**
	 * Gives the latch, created by the first caller. The state is set before the
	 * latch is read to wake it, so a caller that sees "not done" after publishing
	 * the latch is sure to be woken. The latch is already open when the timer is
	 * done.
	 */
	private CountDownLatch waiterLatch() {
		CountDownLatch latch = waiter;
		if (latch == null) {
			CountDownLatch created = new CountDownLatch(1);
			latch = (CountDownLatch) WAITER.compareAndExchange(this, null, created);
			if (latch == null) {
				latch = created;
			}
			if (isFinished()) {
				latch.countDown();
			}
		}
		return latch;
	}

	private Void result() {
		if (state == CANCELLED) {
			throw new CancellationException();
		}
		return null;
	}

	@Override
	public long getDelay(TimeUnit unit) {
		return unit.convert(deadlineNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
	}

	@Override
	public int compareTo(Delayed other) {
		return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
	}
}
