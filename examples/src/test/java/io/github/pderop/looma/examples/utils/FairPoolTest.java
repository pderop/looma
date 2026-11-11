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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;

class FairPoolTest {

	private final FairPool<Integer> pool = new FairPool<>();

	@Test
	void theObjectGivenBackLastGoesFirst() throws Exception {
		pool.give(1);
		pool.give(2);
		assertEquals(2, pool.take(new FairPool.Waiter<>()));
		assertEquals(1, pool.take(new FairPool.Waiter<>()));
	}

	@Test
	void theOldestWaiterGetsTheNextObject() throws Exception {
		Taker first = new Taker();
		Taker second = new Taker();
		pool.give(1);
		assertEquals(1, first.result.get(10, TimeUnit.SECONDS));
		pool.give(2);
		assertEquals(2, second.result.get(10, TimeUnit.SECONDS));
	}

	@Test
	void anInterruptedWaiterLeavesItsPlace() throws Exception {
		Taker interrupted = new Taker();
		Taker next = new Taker();
		interrupted.thread.interrupt();
		ExecutionException failure = assertThrows(ExecutionException.class,
				() -> interrupted.result.get(10, TimeUnit.SECONDS));
		assertInstanceOf(InterruptedException.class, failure.getCause());
		pool.give(1);
		assertEquals(1, next.result.get(10, TimeUnit.SECONDS));
	}

	@Test
	void anInterruptedThreadTakesNothing() throws Exception {
		pool.give(1);
		Thread.currentThread().interrupt();
		assertThrows(InterruptedException.class, () -> pool.take(new FairPool.Waiter<>()));
		assertEquals(1, pool.take(new FairPool.Waiter<>()));
	}

	/**
	 * The object is given to a waiter, and the waiter is interrupted right after.
	 * The waiter either keeps the object, or stops and gives it back. The object is
	 * never lost. Many rounds, so that both endings happen.
	 */
	@Test
	void anObjectGivenToAnInterruptedWaiterIsNotLost() throws Exception {
		for (int round = 0; round < 200; round++) {
			Taker taker = new Taker();
			pool.give(round);
			taker.thread.interrupt();
			try {
				assertEquals(round, taker.result.get(10, TimeUnit.SECONDS));
			} catch (ExecutionException e) {
				assertInstanceOf(InterruptedException.class, e.getCause());
				int expected = round;
				assertEquals(expected,
						assertTimeoutPreemptively(Duration.ofSeconds(10), () -> pool.take(new FairPool.Waiter<>())));
			}
		}
	}

	/**
	 * Many threads take and give back a few objects. An object is never held by two
	 * threads at once, and all of them are back in the pool at the end.
	 */
	@Test
	void manyThreadsNeverShareAnObject() throws Exception {
		int objects = 4;
		int threads = 32;
		int loops = 5_000;
		AtomicIntegerArray holders = new AtomicIntegerArray(objects);
		for (int i = 0; i < objects; i++) {
			pool.give(i);
		}
		List<CompletableFuture<Void>> done = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			CompletableFuture<Void> end = new CompletableFuture<>();
			done.add(end);
			Thread.ofVirtual().start(() -> {
				try {
					for (int i = 0; i < loops; i++) {
						int item = pool.take(new FairPool.Waiter<>());
						if (holders.incrementAndGet(item) != 1) {
							throw new AssertionError("object " + item + " held twice");
						}
						Thread.yield();
						holders.decrementAndGet(item);
						pool.give(item);
					}
					end.complete(null);
				} catch (Throwable e) {
					end.completeExceptionally(e);
				}
			});
		}
		CompletableFuture.allOf(done.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
		Set<Integer> back = new HashSet<>();
		for (int i = 0; i < objects; i++) {
			back.add(pool.take(new FairPool.Waiter<>()));
		}
		assertEquals(Set.of(0, 1, 2, 3), back);
	}

	/**
	 * give is cut in two by hand: it chooses the waiter, then later hands it the
	 * object. In between, the waiter is interrupted twice. It must wait quietly for
	 * the object, not spin, then give it back and throw.
	 */
	@Test
	void aSecondInterruptWhileTheObjectIsOnItsWayDoesNotSpin() throws Exception {
		Taker taker = new Taker();
		// First half of give: the waiter is chosen, under the lock.
		ReentrantLock lock = field(pool, "lock");
		ArrayDeque<FairPool.Waiter<Integer>> waiting = field(pool, "waiting");
		FairPool.Waiter<Integer> chosen;
		lock.lock();
		try {
			chosen = waiting.pollFirst();
		} finally {
			lock.unlock();
		}
		// First interrupt: the waiter finds it was chosen, and waits for the object.
		taker.thread.interrupt();
		waitUntilWaiting(taker.thread);
		// Second interrupt: the waiter must wait again, not spin.
		taker.thread.interrupt();
		Thread.sleep(50);
		waitUntilWaiting(taker.thread);
		assertFalse(taker.result.isDone());
		// Second half of give: the object arrives.
		Field given = FairPool.Waiter.class.getDeclaredField("given");
		given.setAccessible(true);
		given.set(chosen, 7);
		LockSupport.unpark(taker.thread);

		ExecutionException failure = assertThrows(ExecutionException.class,
				() -> taker.result.get(10, TimeUnit.SECONDS));
		assertInstanceOf(InterruptedException.class, failure.getCause());
		assertEquals(7, pool.take(new FairPool.Waiter<>()));
	}

	@SuppressWarnings("unchecked")
	private static <V> V field(Object owner, String name) throws ReflectiveOperationException {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return (V) field.get(owner);
	}

	private static void waitUntilWaiting(Thread thread) throws InterruptedException {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (thread.getState() != Thread.State.WAITING) {
			if (System.nanoTime() > end) {
				fail("the thread does not wait, it is " + thread.getState());
			}
			Thread.sleep(1);
		}
	}

	/** A thread waiting in the pool. Back from the constructor, it waits. */
	private final class Taker {

		final CompletableFuture<Integer> result = new CompletableFuture<>();

		final Thread thread = Thread.ofVirtual().start(() -> {
			try {
				result.complete(pool.take(new FairPool.Waiter<>()));
			} catch (InterruptedException e) {
				result.completeExceptionally(e);
			}
		});

		Taker() throws InterruptedException {
			while (thread.getState() != Thread.State.WAITING) {
				Thread.sleep(1);
			}
		}
	}
}
