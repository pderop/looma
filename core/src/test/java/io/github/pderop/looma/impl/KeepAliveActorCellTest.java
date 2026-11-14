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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Message;

/**
 * The mailbox thread of a {@link KeepAliveActorCell}: it waits for the next
 * tell, a tell wakes it, a stop or a shutdown ends it.
 */
class KeepAliveActorCellTest {

	private record Go() implements Message {
	}

	private record Ping(int from, int seq) implements Message {
	}

	private ActorSystem system;

	@AfterEach
	void tearDown() throws InterruptedException {
		if (system != null) {
			system.shutdown();
			assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
		}
	}

	@Test
	void aKeepAliveSchedulerGetsKeepAliveCells() {
		system = KeepAliveScheduler.builder().build();
		ActorRef ref = system.spawn("a", () -> (message, sender, context) -> {
		});

		assertInstanceOf(KeepAliveActorCell.class, ((ActorRefImpl) ref).cell());
	}

	@Test
	void theDefaultSchedulerGetsPlainCells() {
		system = ActorSystem.builder().build();
		ActorRef ref = system.spawn("a", () -> (message, sender, context) -> {
		});

		assertEquals(ActorCell.class, ((ActorRefImpl) ref).cell().getClass());
	}

	/**
	 * A ping-pong: each message of b finds its mailbox empty. Without keep-alive,
	 * each one starts a new thread. A tell can land just before the thread waits: a
	 * new thread then starts, so the bound is loose.
	 */
	@Test
	void aPingPongKeepsTheSameMailboxThread() throws Exception {
		system = KeepAliveScheduler.builder().build();
		int rounds = 10_000;
		Set<Thread> threadsOfB = ConcurrentHashMap.newKeySet();
		CompletableFuture<Void> done = new CompletableFuture<>();
		ActorRef b = system.spawn("b", () -> (message, sender, context) -> {
			threadsOfB.add(Thread.currentThread());
			sender.tell(message, context.self());
		});
		ActorRef a = system.spawn("a", () -> {
			int[] count = {0};
			return (message, sender, context) -> {
				if (++count[0] == rounds) {
					done.complete(null);
				} else {
					b.tell(message, context.self());
				}
			};
		});

		b.tell(new Go(), a);
		done.get(30, TimeUnit.SECONDS);

		assertTrue(threadsOfB.size() < rounds / 10, threadsOfB.size() + " threads for " + rounds + " messages");
	}

	@Test
	void stoppingAnIdleActorEndsItsWaitingThread() throws Exception {
		system = KeepAliveScheduler.builder().build();
		CompletableFuture<Thread> thread = new CompletableFuture<>();
		CountDownLatch stopped = new CountDownLatch(1);
		ActorRef ref = system.spawn("idle", () -> new Actor() {
			@Override
			public void onReceive(Message message, ActorRef sender, ActorContext context) {
				thread.complete(Thread.currentThread());
			}

			@Override
			public void postStop(ActorContext context) {
				stopped.countDown();
			}
		});
		ref.tell(new Go());
		Thread waiting = thread.get(10, TimeUnit.SECONDS);
		waitUntil(() -> waiting.getState() == Thread.State.WAITING);

		system.stop(ref);

		assertTrue(stopped.await(10, TimeUnit.SECONDS), "postStop did not run");
		assertTrue(waiting.join(Duration.ofSeconds(10)), "the waiting thread did not end");
	}

	@Test
	void shutdownEndsEveryWaitingThread() throws Exception {
		ActorSystem shut = KeepAliveScheduler.builder().build();
		int actors = 100;
		Set<Thread> threads = ConcurrentHashMap.newKeySet();
		CountDownLatch received = new CountDownLatch(actors);
		for (int i = 0; i < actors; i++) {
			shut.spawn("a-" + i, () -> (message, sender, context) -> {
				threads.add(Thread.currentThread());
				received.countDown();
			}).tell(new Go());
		}
		assertTrue(received.await(10, TimeUnit.SECONDS));
		waitUntil(() -> threads.stream().allMatch(t -> t.getState() == Thread.State.WAITING || !t.isAlive()));

		shut.shutdown();

		assertTrue(shut.awaitTermination(10, TimeUnit.SECONDS), "the system did not terminate");
		for (Thread t : threads) {
			assertTrue(t.join(Duration.ofSeconds(10)), t + " did not end");
		}
	}

	/**
	 * Senders pause now and then, so the mailbox empties and the thread waits
	 * often: tells land during the yield, during the park, and once parked. Two
	 * messages never run at once, and the order of each sender is kept.
	 */
	@Test
	void manySendersNeverRunTwoMessagesAtOnce() throws Exception {
		system = KeepAliveScheduler.builder().build();
		int senders = 8;
		int perSender = 20_000;
		AtomicInteger running = new AtomicInteger();
		AtomicInteger overlaps = new AtomicInteger();
		AtomicInteger outOfOrder = new AtomicInteger();
		CountDownLatch all = new CountDownLatch(senders * perSender);
		int[] next = new int[senders];
		ActorRef target = system.spawn("target", () -> (message, sender, context) -> {
			if (running.incrementAndGet() != 1) {
				overlaps.incrementAndGet();
			}
			Ping ping = (Ping) message;
			if (next[ping.from()] != ping.seq()) {
				outOfOrder.incrementAndGet();
			}
			next[ping.from()] = ping.seq() + 1;
			running.decrementAndGet();
			all.countDown();
		});

		List<Thread> threads = new ArrayList<>();
		for (int s = 0; s < senders; s++) {
			int from = s;
			threads.add(Thread.ofPlatform().start(() -> {
				for (int i = 0; i < perSender; i++) {
					target.tell(new Ping(from, i));
					if ((i & 63) == 0) {
						LockSupport.parkNanos(ThreadLocalRandom.current().nextInt(20_000));
					}
				}
			}));
		}
		for (Thread t : threads) {
			t.join();
		}

		assertTrue(all.await(60, TimeUnit.SECONDS), all.getCount() + " messages not run");
		assertEquals(0, overlaps.get(), "two messages ran at once");
		assertEquals(0, outOfOrder.get(), "a sender's order was not kept");
	}

	private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.getAsBoolean()) {
			assertFalse(System.nanoTime() > end, "timed out");
			Thread.sleep(1);
		}
	}
}
