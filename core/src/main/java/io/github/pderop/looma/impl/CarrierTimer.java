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
import java.util.function.Supplier;

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;
import io.github.pderop.looma.spi.MpscQueue;

/**
 * The timers of one carrier: a {@link TimerWheel}, two queues, and one virtual
 * thread that runs the wheel.
 * <p>
 * Most callers of {@link #schedule} put the timer into the inbox, and the wheel
 * thread moves it into the wheel. A mailbox thread that has bound this carrier,
 * and runs on it, adds the timer to the wheel directly. That is safe only if
 * the mailbox threads and the wheel thread of a carrier run on one OS thread,
 * one at a time. The scheduler promises this with
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
	 * The timers of the carrier the calling mailbox thread runs on. A mailbox
	 * thread binds it for its whole run, only on a scheduler that keeps mailbox
	 * threads on their carrier, and only once the wheel of that carrier has
	 * started. A thread forked in a {@code StructuredTaskScope} inherits it, and
	 * may run elsewhere: so the binding counts only for the mailbox thread itself,
	 * see {@link #mailboxThread}.
	 */
	private static final ScopedValue<CarrierTimer> CALLER = ScopedValue.newInstance();

	private final ThreadFactory rawFactory;
	private final MpscQueue<Timer> inbox;
	private final MpscQueue<Timer> cancelled;
	private final TimerWheel wheel;
	private final long tickNanos;
	/** The system this wheel belongs to, or null in tests. */
	private final Object system;
	/** The name of the wheel thread. */
	private final String name;
	private final AtomicInteger state = new AtomicInteger(NEW);
	/** True from the first refused offer until the next accepted one. */
	private final AtomicBoolean fullWarned = new AtomicBoolean();
	private final LongAdder refused = new LongAdder();
	/** True while the wheel thread is about to park with no timer to wait for. */
	private volatile boolean sleeping;
	private volatile Thread wheelThread;
	/**
	 * The mailbox thread that bound this carrier last, or null. Only mailbox
	 * threads of this carrier write it, and they run on one OS thread. A thread
	 * compares it to itself: only the mailbox thread that wrote it can match, never
	 * a forked thread. When two mailbox threads interleave, the first one no longer
	 * matches, and takes the queues.
	 */
	private Thread mailboxThread;

	/** A wheel of no system, for tests. */
	CarrierTimer(ThreadFactory rawFactory, MpscQueue<Timer> inbox, MpscQueue<Timer> cancelled, long tickNanos) {
		this(rawFactory, inbox, cancelled, tickNanos, null, "looma-timer");
	}

	CarrierTimer(ThreadFactory rawFactory, MpscQueue<Timer> inbox, MpscQueue<Timer> cancelled, long tickNanos,
			Object system, String name) {
		if (tickNanos <= 0) {
			throw new IllegalArgumentException("tickNanos must be positive: " + tickNanos);
		}
		this.rawFactory = rawFactory;
		this.inbox = inbox;
		this.cancelled = cancelled;
		this.tickNanos = tickNanos;
		this.system = system;
		this.name = name;
		this.wheel = new TimerWheel(System.nanoTime(), tickNanos);
	}

	/**
	 * Runs the messages of a mailbox thread of {@code timer}'s carrier, marked as
	 * local to that wheel.
	 */
	static void runOnCarrier(CarrierTimer timer, Runnable messages) {
		Thread self = Thread.currentThread();
		timer.mailboxThread = self;
		try {
			ScopedValue.where(CALLER, timer).run(messages);
		} finally {
			if (timer.mailboxThread == self) {
				timer.mailboxThread = null;
			}
		}
	}

	/**
	 * The timers of the carrier the calling mailbox thread runs on, or null. Null
	 * also for a thread that inherited the binding, and for a mailbox thread
	 * another mailbox thread has overtaken.
	 */
	static CarrierTimer callerCarrier() {
		if (!CALLER.isBound()) {
			return null;
		}
		CarrierTimer timer = CALLER.get();
		return timer.mailboxThread == Thread.currentThread() ? timer : null;
	}

	/** True if this wheel belongs to {@code system}. */
	boolean belongsTo(Object system) {
		return this.system == system;
	}

	/**
	 * True once a timer has started the wheel thread, until stop. Before that, a
	 * mailbox thread does not bind its carrier: the first timer of the carrier
	 * takes the inbox. Without timers a mailbox thread pays this one volatile read.
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
				warn(() -> "timer inbox full: " + refused.sum() + " timers cancelled so far", null);
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

	/** The number of timers refused because the inbox was full. */
	long refusedCount() {
		return refused.sum();
	}

	/** The number of timers in the wheel. For tests, from any thread. */
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

	/**
	 * Only the thread that moves NEW to RUNNING starts the wheel thread. If the
	 * thread cannot start, no thread would ever drain the queues: the carrier ends
	 * as after stop, and its timers are cancelled. Never throws.
	 */
	private void start() {
		if (!state.compareAndSet(NEW, RUNNING)) {
			return;
		}
		try {
			Thread thread = rawFactory.newThread(this::run);
			thread.setName(name);
			wheelThread = thread;
			thread.start();
		} catch (Throwable e) {
			state.set(STOPPED);
			for (Timer timer = inbox.poll(); timer != null; timer = inbox.poll()) {
				timer.discard();
			}
			warn(() -> "the timer thread " + name + " could not start: its timers are cancelled", e);
		}
	}

	/**
	 * A round that throws must not end the thread, or no timer would fire again.
	 */
	private void run() {
		while (state.get() != STOPPED) {
			try {
				step(System.nanoTime());
			} catch (Throwable e) {
				warn(() -> "a round of the timer thread " + name + " failed", e);
			}
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

	/** A tell that throws, an Error too, is logged: the next timers still fire. */
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
			warn(() -> "timer tell to " + target.path() + " failed", e);
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

	/** Logs a warning. A logger that throws must not stop the wheel either. */
	private static void warn(Supplier<String> message, Throwable error) {
		try {
			LOG.log(Level.WARNING, message, error);
		} catch (Throwable ignored) {
			// Nothing else to do: the timers must go on.
		}
	}
}
