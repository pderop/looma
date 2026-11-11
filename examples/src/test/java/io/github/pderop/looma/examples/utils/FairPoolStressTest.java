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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** FairPool under load: many threads, few objects, and interrupts. */
class FairPoolStressTest {

	private static final int OBJECTS = 8;

	private final FairPool<Integer> pool = new FairPool<>();

	/**
	 * Virtual and platform threads take and give back a few objects for a few
	 * seconds. Another thread interrupts them at random, while they wait or while
	 * they hold an object. No object is held twice, none is lost, and none is
	 * duplicated.
	 */
	@Test
	@Timeout(60)
	void objectsSurviveLoadAndInterrupts() throws Exception {
		AtomicIntegerArray holders = new AtomicIntegerArray(OBJECTS);
		for (int i = 0; i < OBJECTS; i++) {
			pool.give(i);
		}
		AtomicBoolean stop = new AtomicBoolean();
		LongAdder taken = new LongAdder();
		LongAdder interrupted = new LongAdder();
		ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
		Runnable work = () -> {
			try {
				ThreadLocalRandom random = ThreadLocalRandom.current();
				while (!stop.get()) {
					int item;
					try {
						item = pool.take(new FairPool.Waiter<>());
					} catch (InterruptedException e) {
						interrupted.increment();
						continue;
					}
					try {
						if (holders.incrementAndGet(item) != 1) {
							throw new AssertionError("object " + item + " held twice");
						}
						taken.increment();
						hold(random);
						holders.decrementAndGet(item);
					} finally {
						pool.give(item);
					}
				}
			} catch (Throwable e) {
				errors.add(e);
			}
		};
		List<Thread> workers = new ArrayList<>();
		for (int i = 0; i < 64; i++) {
			workers.add(Thread.ofVirtual().unstarted(work));
		}
		for (int i = 0; i < 8; i++) {
			workers.add(Thread.ofPlatform().unstarted(work));
		}
		Thread interrupter = Thread.ofPlatform().unstarted(() -> {
			ThreadLocalRandom random = ThreadLocalRandom.current();
			while (!stop.get()) {
				workers.get(random.nextInt(workers.size())).interrupt();
				LockSupport.parkNanos(20_000);
			}
		});
		workers.forEach(Thread::start);
		interrupter.start();
		Thread.sleep(3_000);
		stop.set(true);
		interrupter.join();
		for (Thread worker : workers) {
			assertTrue(worker.join(Duration.ofSeconds(10)), "a worker is stuck: " + worker);
		}

		assertTrue(errors.isEmpty(), () -> "first error: " + errors.peek());
		assertTrue(taken.sum() > 0, "no object was taken");
		assertTrue(interrupted.sum() > 0, "no take was interrupted");
		for (int i = 0; i < OBJECTS; i++) {
			assertEquals(0, holders.get(i), "object " + i + " still held");
		}
		Set<Integer> back = new HashSet<>();
		for (int i = 0; i < OBJECTS; i++) {
			back.add(pool.take(new FairPool.Waiter<>()));
		}
		assertEquals(OBJECTS, back.size(), "an object came back twice: " + back);
		assertPoolEmpty();
	}

	/**
	 * Many threads wait in a row. Every third one is interrupted and leaves. The
	 * others get the object one after the other, in the order they came.
	 */
	@Test
	@Timeout(60)
	void waitersAreServedInOrderWhenSomeLeave() throws Exception {
		pool.give(0);
		int held = pool.take(new FairPool.Waiter<>());
		int count = 300;
		List<Integer> served = Collections.synchronizedList(new ArrayList<>());
		List<Thread> waiters = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			int index = i;
			Thread waiter = Thread.ofVirtual().start(() -> {
				try {
					int item = pool.take(new FairPool.Waiter<>());
					served.add(index);
					pool.give(item);
				} catch (InterruptedException e) {
					// left the row
				}
			});
			waitUntilWaiting(waiter);
			waiters.add(waiter);
		}
		List<Integer> expected = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			if (i % 3 == 0) {
				waiters.get(i).interrupt();
				assertTrue(waiters.get(i).join(Duration.ofSeconds(10)));
			} else {
				expected.add(i);
			}
		}
		pool.give(held);
		for (Thread waiter : waiters) {
			assertTrue(waiter.join(Duration.ofSeconds(10)), "a waiter is stuck: " + waiter);
		}
		assertEquals(expected, served);
		assertEquals(0, pool.take(new FairPool.Waiter<>()));
		assertPoolEmpty();
	}

	/** Holds an object for a short random time: not at all, a yield, or a spin. */
	private static void hold(ThreadLocalRandom random) {
		switch (random.nextInt(3)) {
			case 0 -> {
			}
			case 1 -> Thread.yield();
			default -> {
				long end = System.nanoTime() + random.nextInt(2_000);
				while (System.nanoTime() < end) {
					Thread.onSpinWait();
				}
			}
		}
	}

	/**
	 * A new take waits: there is no object left. Its thread is then interrupted.
	 */
	private void assertPoolEmpty() throws Exception {
		CompletableFuture<Integer> result = new CompletableFuture<>();
		Thread taker = Thread.ofVirtual().start(() -> {
			try {
				result.complete(pool.take(new FairPool.Waiter<>()));
			} catch (InterruptedException e) {
				result.completeExceptionally(e);
			}
		});
		waitUntilWaiting(taker);
		Thread.sleep(50);
		assertFalse(result.isDone(), "the pool still had an object");
		taker.interrupt();
		ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
		assertInstanceOf(InterruptedException.class, failure.getCause());
	}

	private static void waitUntilWaiting(Thread thread) throws InterruptedException {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (thread.getState() != Thread.State.WAITING) {
			assertTrue(System.nanoTime() < end, "the thread never waited: " + thread);
			Thread.sleep(1);
		}
	}
}
