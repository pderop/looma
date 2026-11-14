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

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.SupervisionStrategy;

/**
 * An {@link ActorCell} whose mailbox thread stays alive. Spawned instead of an
 * {@code ActorCell} when the scheduler's {@code keepMailboxThreadsAlive()} is
 * true.
 *
 * <p>
 * When its mailbox is empty, the mailbox thread puts itself in
 * {@link #idleThread}, yields once, then parks. The {@code tell} that takes the
 * count from 0 to 1 takes it from there and unparks it, instead of starting a
 * new thread. A {@code tell} that lands during the yield takes it too: the
 * thread then sees it is no longer in {@link #idleThread}, and does not park.
 *
 * <p>
 * Only one thread can be in {@link #idleThread}. A {@code tell} can start a new
 * thread just before the old one puts itself there. The old one then waits, and
 * the new one ends when its mailbox is empty. Either way, a thread runs
 * envelopes only after a {@code tell} took the count from 0, so two threads
 * never run at once.
 *
 * <p>
 * The wait has no time limit. A stop wakes the thread: it enqueues an envelope.
 * Once the cell is no longer {@link ActorCell.State#RUNNING}, or the system
 * shuts down, the thread ends instead of waiting.
 */
final class KeepAliveActorCell extends ActorCell {

	/** The mailbox thread waiting for the next {@code tell}, or null. */
	private final AtomicReference<Thread> idleThread = new AtomicReference<>();

	private KeepAliveActorCell(String name, String path, ActorCell parent, Supplier<Actor> factory,
			SupervisionStrategy explicitStrategy, ActorDispatcher dispatcher, ActorRuntime runtime) {
		super(name, path, parent, factory, explicitStrategy, dispatcher, runtime);
	}

	/**
	 * Creates the cell. A static method, not a constructor call in
	 * {@code ActorCell}: the JVM then has no reason to load this class when no cell
	 * of this kind is ever created. See {@code ActorCell.createCell}.
	 */
	static ActorCell create(String name, String path, ActorCell parent, Supplier<Actor> factory,
			SupervisionStrategy explicitStrategy, ActorDispatcher dispatcher, ActorRuntime runtime) {
		return new KeepAliveActorCell(name, path, parent, factory, explicitStrategy, dispatcher, runtime);
	}

	/** Wakes the waiting mailbox thread, or starts a new one if none waits. */
	@Override
	void wakeMailboxThread() {
		Thread idle = idleThread.getAndSet(null);
		if (idle != null) {
			LockSupport.unpark(idle);
		} else {
			startMailboxThread();
		}
	}

	/**
	 * Waits until a {@code tell} takes this mailbox thread. Returns {@code false}
	 * at once, and the thread ends, if the cell is not running, the system shuts
	 * down, or another thread already waits.
	 */
	@Override
	boolean awaitNextTell() {
		if (state() != State.RUNNING || runtime().isShuttingDown()) {
			return false;
		}
		Thread me = Thread.currentThread();
		if (!idleThread.compareAndSet(null, me)) {
			return false;
		}
		// A stop may have ended between the first check and now: nothing would wake
		// this thread. Look again, now that a stop would find it.
		if (state() != State.RUNNING || runtime().isShuttingDown()) {
			// If a tell took it meanwhile, it must run.
			return !idleThread.compareAndSet(me, null);
		}
		// Still in idleThread: a tell during the yield takes this thread.
		Thread.yield();
		while (idleThread.get() == me) {
			LockSupport.park(this);
			Thread.interrupted();
		}
		return true;
	}
}
