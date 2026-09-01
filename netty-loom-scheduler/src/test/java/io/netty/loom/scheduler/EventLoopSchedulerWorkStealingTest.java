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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Proves the push/pull {@code nSearching} work-stealing protocol of
 * {@link EventLoopScheduler} actually moves queued work off an overloaded
 * carrier onto an idle sibling — the engine {@code looma} places actors
 * over, exercised here directly, bypassing the actor abstraction.
 *
 * <p>
 * Only meaningful with work stealing enabled — see the
 * {@code workstealing-test} Surefire execution in
 * {@code netty-loom-scheduler/pom.xml}, the only one this test runs under.
 */
class EventLoopSchedulerWorkStealingTest {

	private static final int TASKS_PER_BURST = 500;

	/** Bursts attempted before giving up — see the comment in the test below. */
	private static final int MAX_ROUNDS = 10;

	@Test
	void anIdleCarrierStealsQueuedWorkFromAnOverloadedSibling() throws InterruptedException {
		assumeTrue(EventLoopScheduler.WORK_STEALING_ENABLED, "run only under the workstealing-test execution");

		EventLoopSchedulerGroup group = EventLoopSchedulerGroup.instance();
		assumeTrue(group.size() >= 2, "need at least 2 carriers");

		EventLoopScheduler busy = group.scheduler(0);
		AtomicInteger stolen = new AtomicInteger();

		warmUp(group);

		/*
		 * Retried rather than asserted on a single burst: whether a given burst backs
		 * busy's run queue up long enough for a sibling to probe it is timing-dependent
		 * -- the carrier can simply drain the tasks about as fast as they are submitted,
		 * and a single-burst version of this test failed roughly one run in six on an
		 * otherwise busy machine. The property under test is that stealing happens at
		 * all, not that it happens on the first burst.
		 */
		for (int round = 0; round < MAX_ROUNDS && stolen.get() == 0; round++) {
			runBurst(busy, stolen);
		}

		assertTrue(stolen.get() > 0,
				"expected an idle sibling carrier to steal at least one queued task within " + MAX_ROUNDS + " bursts");
	}

	/**
	 * Runs one trivial task on every carrier in the group, so that no sibling is
	 * still sitting in its very first park when the burst below starts. A carrier
	 * that has never had work is the one state from which stealing was observed
	 * never to kick in.
	 */
	private static void warmUp(EventLoopSchedulerGroup group) throws InterruptedException {
		CountDownLatch warm = new CountDownLatch(group.size());

		for (int i = 0; i < group.size(); i++) {
			group.scheduler(i).virtualThreadFactory().newThread(warm::countDown).start();
		}

		assertTrue(warm.await(30, TimeUnit.SECONDS), "every carrier should have run its warm-up task");
	}

	/**
	 * Floods {@code busy}'s run queue with short, <b>runnable</b> tasks and counts
	 * how many of them ended up running on some other carrier.
	 */
	private static void runBurst(EventLoopScheduler busy, AtomicInteger stolen) throws InterruptedException {
		CountDownLatch done = new CountDownLatch(TASKS_PER_BURST);

		/*
		 * The submitting thread is itself a virtual thread on busy, so every spawn below
		 * is an internal submission that stays local instead of being distributed: the
		 * tasks pile up behind the submitter on busy's own run queue, which is exactly
		 * the backlog a sibling is supposed to steal from. Submitting from the JUnit
		 * platform thread instead lets busy drain each task as it arrives.
		 */
		busy.virtualThreadFactory().newThread(() -> {
			for (int i = 0; i < TASKS_PER_BURST; i++) {
				busy.virtualThreadFactory().newThread(() -> {
					// A *runnable* (not parked) virtual thread is what actually occupies a
					// carrier thread and lets its run queue back up — a park would just free
					// the carrier immediately, defeating the point of this test.
					long deadlineNs = System.nanoTime() + TimeUnit.MICROSECONDS.toNanos(200);
					while (System.nanoTime() < deadlineNs) {
						Thread.onSpinWait();
					}
					// Any carrier other than busy having run this task means the task was
					// stolen. Counting only steals by one designated sibling
					// (group.scheduler(1)) would miss a task stolen by carrier 2 or 3, which is
					// a steal just the same.
					if (EventLoopScheduler.currentRunningScheduler() != busy) {
						stolen.incrementAndGet();
					}
					done.countDown();
				}).start();
			}
		}).start();

		assertTrue(done.await(30, TimeUnit.SECONDS), "all spawned tasks should have completed");
	}
}
