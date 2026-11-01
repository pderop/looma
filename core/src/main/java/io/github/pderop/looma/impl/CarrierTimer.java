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

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;
import io.github.pderop.looma.spi.MpscQueue;

/**
 * The timers of one carrier: a {@link TimerWheel}, two queues, and one virtual
 * thread that runs the wheel.
 * <p>
 * Most callers of {@link #schedule} put the timer into the inbox, and the wheel
 * thread moves it into the wheel. A burst that has bound this carrier, and runs
 * on it, adds the timer to the wheel directly. That is safe only if the bursts
 * and the wheel thread of a carrier run on one OS thread, one at a time. The
 * scheduler promises this with
 * {@code ActorScheduler.mailboxThreadsStayOnCarrier()}. A wheel call never
 * blocks, so it never lets another thread in.
 * <p>
 * Cancel works the same way. On this carrier the timer leaves the wheel at
 * once. From anywhere else it goes to the cancelled queue, and the wheel thread
 * removes it at the next tick.
 * <p>
 * The thread starts at the first timer, so a carrier without timers has no
 * thread. The thread is made by a raw factory, so it is not counted when the
 * system waits for termination.
 * <p>
 * After {@link #stop()} every timer, waiting or new, ends cancelled.
 */
final class CarrierTimer {

	private static final Logger LOG = System.getLogger(CarrierTimer.class.getName());

	private static final int NEW = 0;
	private static final int RUNNING = 1;
	private static final int STOPPED = 2;

	/**
	 * The timers of the carrier the calling actor burst runs on. A burst binds it
	 * for its whole run, only on a scheduler that keeps bursts on their carrier,
	 * and only once the wheel of that carrier has started. A thread forked in a
	 * {@code StructuredTaskScope} inherits it, and may run elsewhere: so the
	 * binding counts only for the thread of the burst, see {@link #burstThread}.
	 */
	private static final ScopedValue<CarrierTimer> CALLER = ScopedValue.newInstance();

	private final ThreadFactory rawFactory;
	private final MpscQueue<Timer> inbox;
	private final MpscQueue<Timer> cancelled;
	private final TimerWheel wheel;
	private final long tickNanos;
	/** The system this wheel belongs to, or null in tests. */
	private final Object system;
	private final AtomicInteger state = new AtomicInteger(NEW);
	/** True from the first refused offer until the next accepted one. */
	private final AtomicBoolean fullWarned = new AtomicBoolean();
	private final LongAdder refused = new LongAdder();
	private final AtomicInteger warnings = new AtomicInteger();
	/** True while the wheel thread is about to park with no timer to wait for. */
	private volatile boolean sleeping;
	private volatile Thread wheelThread;
	/**
	 * The thread of the burst that bound this carrier last, or null. Only bursts of
	 * this carrier write it, and they run on one OS thread. A thread compares it to
	 * itself: only the burst that wrote it can match, never a forked thread. When
	 * two bursts interleave, the first one no longer matches, and takes the queues.
	 */
	private Thread burstThread;

	/** A wheel of no system, for tests. */
	CarrierTimer(ThreadFactory rawFactory, MpscQueue<Timer> inbox, MpscQueue<Timer> cancelled, long tickNanos) {
		this(rawFactory, inbox, cancelled, tickNanos, null);
	}

	CarrierTimer(ThreadFactory rawFactory, MpscQueue<Timer> inbox, MpscQueue<Timer> cancelled, long tickNanos,
			Object system) {
		if (tickNanos <= 0) {
			throw new IllegalArgumentException("tickNanos must be positive: " + tickNanos);
		}
		this.rawFactory = rawFactory;
		this.inbox = inbox;
		this.cancelled = cancelled;
		this.tickNanos = tickNanos;
		this.system = system;
		this.wheel = new TimerWheel(System.nanoTime(), tickNanos);
	}

	/** Runs a burst that runs on {@code timer}'s carrier. */
	static void runOnCarrier(CarrierTimer timer, Runnable burst) {
		Thread self = Thread.currentThread();
		timer.burstThread = self;
		try {
			ScopedValue.where(CALLER, timer).run(burst);
		} finally {
			if (timer.burstThread == self) {
				timer.burstThread = null;
			}
		}
	}

	/**
	 * The timers of the carrier the calling burst runs on, or null. Null also for a
	 * thread that inherited the binding, and for a burst another burst has
	 * overtaken.
	 */
	static CarrierTimer callerCarrier() {
		if (!CALLER.isBound()) {
			return null;
		}
		CarrierTimer timer = CALLER.get();
		return timer.burstThread == Thread.currentThread() ? timer : null;
	}

	/** True if this wheel belongs to {@code system}. */
	boolean belongsTo(Object system) {
		return this.system == system;
	}

	/**
	 * True once a timer has started the wheel thread, until stop. Before that, a
	 * burst does not bind its carrier: the first timer of the carrier takes the
	 * inbox. Without timers a burst pays this one volatile read.
	 */
	boolean isStarted() {
		return state.get() == RUNNING;
	}

	/** Never throws. A timer that cannot be kept is cancelled. */
	void schedule(Timer timer) {
		if (state.get() == STOPPED) {
			timer.discard();
		} else if (callerCarrier() == this) {
			scheduleLocal(timer);
		} else {
			scheduleRemote(timer);
		}
	}

	private void scheduleLocal(Timer timer) {
		start();
		// Once the state is STOPPED the wheel thread is gone, or it will cancel
		// everything the wheel holds before it can run again on this carrier.
		if (state.get() == STOPPED) {
			timer.discard();
			return;
		}
		if (timer.markScheduled()) {
			wheel.add(timer);
		}
		if (sleeping) {
			LockSupport.unpark(wheelThread);
		}
	}

	private void scheduleRemote(Timer timer) {
		if (!inbox.offer(timer)) {
			refused.increment();
			if (fullWarned.compareAndSet(false, true)) {
				warnings.incrementAndGet();
				LOG.log(Level.WARNING, () -> "timer inbox full: " + refused.sum() + " timers cancelled so far");
			}
			timer.discard();
			return;
		}
		if (fullWarned.get()) {
			fullWarned.set(false);
		}
		start();
		// A stop between the first check and start() leaves no thread to drain the
		// inbox, so check again. Cancelling twice is harmless.
		if (state.get() == STOPPED) {
			timer.discard();
			return;
		}
		if (sleeping) {
			LockSupport.unpark(wheelThread);
		}
	}

	/**
	 * Called when a scheduled timer was cancelled. On this carrier it leaves the
	 * wheel now. Elsewhere it is queued, and if the queue is full the wheel drops
	 * it when its slot comes up.
	 */
	void timerCancelled(Timer timer) {
		if (callerCarrier() == this) {
			wheel.remove(timer);
		} else {
			cancelled.offer(timer);
		}
	}

	/** The number of warnings logged for a full inbox. */
	int warningCount() {
		return warnings.get();
	}

	/** The number of timers refused because the inbox was full. */
	long refusedCount() {
		return refused.sum();
	}

	/** The number of timers in the wheel. For tests. */
	int pending() {
		return wheel.size();
	}

	/** Cancels every timer. If nothing was ever scheduled, no thread starts. */
	void stop() {
		int previous = state.getAndSet(STOPPED);
		if (previous == RUNNING) {
			LockSupport.unpark(wheelThread);
		}
	}

	/** Only the thread that moves NEW to RUNNING starts the wheel thread. */
	private void start() {
		if (state.compareAndSet(NEW, RUNNING)) {
			Thread thread = rawFactory.newThread(this::run);
			wheelThread = thread;
			thread.start();
		}
	}

	private void run() {
		while (state.get() != STOPPED) {
			step(System.nanoTime());
			if (!wheel.isEmpty()) {
				LockSupport.parkNanos(wheel.nextTickNanos(System.nanoTime()));
			} else if (inbox.isEmpty()) {
				sleeping = true;
				if (inbox.isEmpty() && wheel.isEmpty() && state.get() != STOPPED) {
					LockSupport.park();
				}
				sleeping = false;
			}
		}
		wheel.cancelAll();
		for (Timer timer = inbox.poll(); timer != null; timer = inbox.poll()) {
			timer.discard();
		}
		while (cancelled.poll() != null) {
			// Nothing to do: the wheel is empty.
		}
	}

	/**
	 * One round of the wheel thread: take in new timers, drop cancelled ones, fire.
	 */
	void step(long now) {
		drainInbox();
		drainCancelled();
		wheel.expire(now, this::fire);
	}

	private void drainInbox() {
		for (Timer timer = inbox.poll(); timer != null; timer = inbox.poll()) {
			if (timer.markScheduled()) {
				wheel.add(timer);
			}
		}
	}

	private void drainCancelled() {
		for (Timer timer = cancelled.poll(); timer != null; timer = cancelled.poll()) {
			wheel.remove(timer);
		}
	}

	/**
	 * A tell that throws must not stop the wheel: an Error too, or the thread would
	 * end and no timer of this carrier would ever fire again.
	 */
	private void fire(Timer timer) {
		ActorRef sender = timer.sender;
		ActorRef target = timer.target;
		Message message = timer.message;
		if (target == null || message == null) {
			return;
		}
		if (state.get() == STOPPED || (timer.isPeriodic() && timer.isTargetStopped())) {
			timer.discard();
			return;
		}
		if (!timer.isPeriodic() && !timer.markSent()) {
			return;
		}
		try {
			if (timer.isPeriodic() && timer.isCancelled()) {
				return;
			}
			target.tell(message, sender);
		} catch (Throwable e) {
			LOG.log(Level.WARNING, "timer tell to " + target.path() + " failed", e);
		} finally {
			if (!timer.isPeriodic()) {
				timer.completeSent();
			}
		}
		if (timer.isPeriodic() && !timer.isCancelled()) {
			timer.rearm();
			wheel.add(timer);
		}
	}
}
