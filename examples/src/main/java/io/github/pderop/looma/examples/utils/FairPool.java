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
package io.github.pderop.looma.examples.utils;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A fair pool of objects, shared by threads. The thread that waits the longest
 * gets the next object.
 *
 * <p>
 * {@link #give} puts the object directly in the waiting thread, and wakes it.
 * That thread does not need the lock again. So a thread that arrives later
 * cannot take this object first.
 *
 * <p>
 * The free objects go out in last-in first-out order: the object given back
 * last goes first.
 *
 * @param <T>
 *            the objects in the pool
 */
public final class FairPool<T> {

	/**
	 * What a thread waits with. A caller that already has one object per call can
	 * extend this class: nothing is then allocated to wait. Use a waiter for one
	 * call only.
	 *
	 * @param <T>
	 *            the objects in the pool
	 */
	public static class Waiter<T> {

		private Thread thread;

		private volatile T given;
	}

	private final ReentrantLock lock = new ReentrantLock();

	/** The free objects. The first is the last given back. */
	private final ArrayDeque<T> free = new ArrayDeque<>();

	/** The threads that wait, oldest first. */
	private final ArrayDeque<Waiter<T>> waiting = new ArrayDeque<>();

	/**
	 * Returns a free object, or waits for one.
	 *
	 * @throws InterruptedException
	 *             if the thread is interrupted before it gets an object, or while
	 *             it waits for the lock. An interrupt that comes later stays set
	 */
	public T take(Waiter<T> waiter) throws InterruptedException {
		lock.lockInterruptibly();
		try {
			T item = free.pollFirst();
			if (item != null) {
				return item;
			}
			waiter.thread = Thread.currentThread();
			waiting.addLast(waiter);
		} finally {
			lock.unlock();
		}
		T item;
		while ((item = waiter.given) == null) {
			LockSupport.park(this);
			if (Thread.interrupted()) {
				abandon(waiter);
				throw new InterruptedException();
			}
		}
		return item;
	}

	/**
	 * Gives an object back. The thread that waits the longest gets it. The object
	 * must not be null: a waiting thread would never see it.
	 */
	public void give(T item) {
		Objects.requireNonNull(item);
		Waiter<T> first;
		lock.lock();
		try {
			first = waiting.pollFirst();
			if (first == null) {
				free.addFirst(item);
				return;
			}
		} finally {
			lock.unlock();
		}
		first.given = item;
		LockSupport.unpark(first.thread);
	}

	/**
	 * Stops waiting. If {@link #give} already chose this waiter, its object is
	 * about to arrive: the thread waits for it, then gives it back to the pool.
	 * {@link #give} wakes the thread once the object is there.
	 */
	private void abandon(Waiter<T> waiter) {
		boolean removed;
		lock.lock();
		try {
			removed = waiting.remove(waiter);
		} finally {
			lock.unlock();
		}
		if (removed) {
			return;
		}
		T item;
		while ((item = waiter.given) == null) {
			LockSupport.park(this);
			// A second interrupt would make park return at once. The exception
			// thrown next reports it too.
			Thread.interrupted();
		}
		give(item);
	}
}
