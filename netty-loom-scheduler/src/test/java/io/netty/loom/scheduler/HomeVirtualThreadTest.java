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
package io.netty.loom.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Looma: virtual threads from {@link EventLoopScheduler#homeVirtualThreadFactory()}
 * are never run by another carrier, even with work stealing on and their home
 * carrier backed up — while virtual threads from
 * {@link EventLoopScheduler#virtualThreadFactory()} in the same backlog still
 * get stolen. Only meaningful under the {@code workstealing-test} execution.
 */
class HomeVirtualThreadTest {

	private static final int TASKS_PER_BURST = 500;

	private static final int MAX_ROUNDS = 10;

	/** Times each home task gives its carrier up and comes back. */
	private static final int YIELDS = 5;

	@Test
	void homeThreadsStayHomeWhileOthersAreStolen() throws InterruptedException {
		assumeTrue(EventLoopScheduler.WORK_STEALING_ENABLED, "run only under the workstealing-test execution");
		EventLoopSchedulerGroup group = EventLoopSchedulerGroup.instance();
		assumeTrue(group.size() >= 2, "need at least 2 carriers");

		EventLoopScheduler busy = group.scheduler(0);
		AtomicInteger homeRuns = new AtomicInteger();
		AtomicInteger homeAway = new AtomicInteger();
		AtomicInteger stolen = new AtomicInteger();

		warmUp(group);
		// Retried for the same reason as EventLoopSchedulerWorkStealingTest: whether a
		// burst backs the queue up long enough for a sibling to steal is timing-dependent.
		for (int round = 0; round < MAX_ROUNDS && stolen.get() == 0; round++) {
			runBurst(busy, homeRuns, homeAway, stolen);
		}

		assertTrue(homeRuns.get() > 0, "the home tasks should have run");
		assertEquals(0, homeAway.get(), "a home virtual thread ran on a carrier other than its home");
		assertTrue(stolen.get() > 0, "expected the stealable tasks in the same backlog to be stolen at least once");
	}

	private static void warmUp(EventLoopSchedulerGroup group) throws InterruptedException {
		CountDownLatch warm = new CountDownLatch(group.size());
		for (int i = 0; i < group.size(); i++) {
			group.scheduler(i).virtualThreadFactory().newThread(warm::countDown).start();
		}
		assertTrue(warm.await(30, TimeUnit.SECONDS), "every carrier should have run its warm-up task");
	}

	/**
	 * Floods {@code busy} with runnable tasks, alternately home and stealable, all
	 * spawned from a virtual thread on {@code busy} so they pile up on it. Each
	 * home task checks the carrier running it, gives the carrier up a few times and
	 * checks again on every return.
	 */
	private static void runBurst(EventLoopScheduler busy, AtomicInteger homeRuns, AtomicInteger homeAway,
			AtomicInteger stolen) throws InterruptedException {
		CountDownLatch done = new CountDownLatch(TASKS_PER_BURST);
		busy.virtualThreadFactory().newThread(() -> {
			for (int i = 0; i < TASKS_PER_BURST; i++) {
				if (i % 2 == 0) {
					busy.homeVirtualThreadFactory().newThread(() -> {
						for (int y = 0; y <= YIELDS; y++) {
							spin();
							homeRuns.incrementAndGet();
							if (EventLoopScheduler.currentRunningScheduler() != busy) {
								homeAway.incrementAndGet();
							}
							Thread.yield();
						}
						done.countDown();
					}).start();
				} else {
					busy.virtualThreadFactory().newThread(() -> {
						spin();
						if (EventLoopScheduler.currentRunningScheduler() != busy) {
							stolen.incrementAndGet();
						}
						done.countDown();
					}).start();
				}
			}
		}).start();
		assertTrue(done.await(60, TimeUnit.SECONDS), "all spawned tasks should have completed");
	}

	/** Runnable rather than parked, so that the carrier's queues actually back up. */
	private static void spin() {
		long deadlineNs = System.nanoTime() + TimeUnit.MICROSECONDS.toNanos(200);
		while (System.nanoTime() < deadlineNs) {
			Thread.onSpinWait();
		}
	}
}
