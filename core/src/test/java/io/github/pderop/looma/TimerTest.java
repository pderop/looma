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
package io.github.pderop.looma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delayed and periodic {@code tell}: the message arrives after the delay and
 * never before, and a timer can be cancelled until it has gone.
 *
 * <p>
 * The system uses a 5 ms tick. The upper bounds are generous, since a message
 * may be a tick late, and a loaded machine may add more.
 */
public abstract class TimerTest {

	private record Tick(int n) implements Message {
	}

	/** Asks the actor to try a delayed tell on the sender it received. */
	private record TryDelayedOnSender() implements Message {
	}

	private record Reply() implements Message {
	}

	/** The builder of the scheduler under test, without the tick. */
	protected abstract ActorSystem.Builder newBuilder();

	private ActorSystem system;

	/** Messages seen by the {@link Collector} actors, with their arrival time. */
	private final BlockingQueue<Message> received = new LinkedBlockingQueue<>();
	private final BlockingQueue<Long> arrivals = new LinkedBlockingQueue<>();
	private final CompletableFuture<Throwable> senderTimerFailure = new CompletableFuture<>();

	@BeforeEach
	void setUp() {
		system = newBuilder().timerTick(Duration.ofMillis(5)).build();
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		system.shutdown();
		assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
	}

	/** Exposes the system to subclasses that need it. */
	protected ActorSystem system() {
		return system;
	}

	protected ActorRef spawnCollector(String name) {
		return system.spawn(name, Collector::new);
	}

	protected ActorRef spawnCollector(String name, SpawnOptions options) {
		return system.spawn(name, Collector::new, options);
	}

	protected BlockingQueue<Message> received() {
		return received;
	}

	// ================================================================
	// One-shot
	// ================================================================

	@Test
	void aDelayedTellArrivesAfterTheDelayNotBefore() throws Exception {
		ActorRef target = spawnCollector("target");

		long start = System.nanoTime();
		target.tell(new Tick(1), 100, TimeUnit.MILLISECONDS);

		assertEquals(new Tick(1), received.poll(10, TimeUnit.SECONDS));
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(arrivals.take() - start);
		assertTrue(elapsedMillis >= 100, "arrived early: " + elapsedMillis + " ms");
		assertTrue(elapsedMillis < 5_000, "arrived far too late: " + elapsedMillis + " ms");
	}

	@Test
	void aZeroOrNegativeDelaySendsAtTheNextTick() throws Exception {
		ActorRef target = spawnCollector("target");

		target.tell(new Tick(1), 0, TimeUnit.MILLISECONDS);
		target.tell(new Tick(2), -50, TimeUnit.MILLISECONDS);

		assertNotNull(received.poll(10, TimeUnit.SECONDS));
		assertNotNull(received.poll(10, TimeUnit.SECONDS));
	}

	@Test
	void cancelBeforeTheSendPreventsItAndReturnsTrue() throws Exception {
		ActorRef target = spawnCollector("target");

		ScheduledFuture<?> future = target.tell(new Tick(1), 300, TimeUnit.MILLISECONDS);

		assertTrue(future.cancel(false));
		assertTrue(future.isCancelled());
		assertTrue(future.isDone());
		assertNull(received.poll(700, TimeUnit.MILLISECONDS));
		assertThrows(CancellationException.class, () -> future.get());
	}

	@Test
	void cancelTwiceIsHarmless() {
		ActorRef target = spawnCollector("target");

		ScheduledFuture<?> future = target.tell(new Tick(1), 10, TimeUnit.SECONDS);

		assertTrue(future.cancel(false));
		// As FutureTask: only the call that cancels returns true.
		assertFalse(future.cancel(false));
		assertTrue(future.isCancelled());
	}

	@Test
	void cancelAfterTheSendReturnsFalse() throws Exception {
		ActorRef target = spawnCollector("target");

		ScheduledFuture<?> future = target.tell(new Tick(1), 10, TimeUnit.MILLISECONDS);
		assertNotNull(received.poll(10, TimeUnit.SECONDS));
		waitUntil(future::isDone);

		assertFalse(future.cancel(false));
		assertFalse(future.isCancelled());
	}

	@Test
	void getReturnsOnceTheMessageHasBeenSent() throws Exception {
		ActorRef target = spawnCollector("target");

		ScheduledFuture<?> future = target.tell(new Tick(1), 50, TimeUnit.MILLISECONDS);

		future.get(10, TimeUnit.SECONDS);
		assertTrue(future.isDone());
		assertFalse(future.isCancelled());
		assertNotNull(received.poll(10, TimeUnit.SECONDS));
	}

	@Test
	void theFutureReportsTheRemainingDelay() {
		ActorRef target = spawnCollector("target");

		ScheduledFuture<?> future = target.tell(new Tick(1), 10, TimeUnit.SECONDS);

		long remaining = future.getDelay(TimeUnit.MILLISECONDS);
		assertTrue(remaining > 0 && remaining <= 10_000, "remaining: " + remaining);
		future.cancel(false);
	}

	@Test
	void aDelayedTellToAStoppedActorIsDroppedQuietly() throws Exception {
		ActorRef target = spawnCollector("target");
		ScheduledFuture<?> future = target.tell(new Tick(1), 100, TimeUnit.MILLISECONDS);

		system.stop(target);
		waitUntil(() -> system.findActor("/target").isEmpty());

		future.get(10, TimeUnit.SECONDS);
		assertNull(received.poll(200, TimeUnit.MILLISECONDS));
	}

	@Test
	void aDelayedTellToAPoolIsDelivered() throws Exception {
		ActorRef pool = spawnCollector("pool", SpawnOptions.pooled(3));

		pool.tell(new Tick(1), 20, TimeUnit.MILLISECONDS);

		assertEquals(new Tick(1), received.poll(10, TimeUnit.SECONDS));
	}

	// ================================================================
	// Periodic
	// ================================================================

	@Test
	void aFixedRateGivesAboutOneMessagePerPeriodThenStopsAfterCancel() throws Exception {
		ActorRef target = spawnCollector("target");

		ScheduledFuture<?> future = target.tellAtFixedRate(new Tick(1), null, 50, 50, TimeUnit.MILLISECONDS);
		Thread.sleep(1_075);
		assertTrue(future.cancel(false));
		// Let a message that was already on its way land.
		Thread.sleep(100);
		int count = received.size();

		// 1075 ms of 50 ms periods is about 21 (tolerant: the box may be loaded).
		assertTrue(count >= 8 && count <= 24, "count: " + count);
		assertTrue(future.isCancelled());
		Thread.sleep(300);
		assertEquals(count, received.size(), "messages kept coming after cancel");
	}

	@Test
	void aFixedRateMessageNeverGoesBeforeItsInitialDelay() throws Exception {
		ActorRef target = spawnCollector("target");

		long start = System.nanoTime();
		ScheduledFuture<?> future = target.tellAtFixedRate(new Tick(1), null, 100, 1_000, TimeUnit.MILLISECONDS);

		assertNotNull(received.poll(10, TimeUnit.SECONDS));
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(arrivals.take() - start);
		future.cancel(false);
		assertTrue(elapsedMillis >= 100, "arrived early: " + elapsedMillis + " ms");
	}

	@Test
	void aFixedRateNeverCompletesOnItsOwn() throws Exception {
		ActorRef target = spawnCollector("target");

		ScheduledFuture<?> future = target.tellAtFixedRate(new Tick(1), null, 10, 10, TimeUnit.MILLISECONDS);
		assertNotNull(received.poll(10, TimeUnit.SECONDS));

		assertThrows(TimeoutException.class, () -> future.get(200, TimeUnit.MILLISECONDS));
		assertFalse(future.isDone());
		future.cancel(false);
	}

	@Test
	void aPeriodicTellToAStoppedActorCancelsItself() throws Exception {
		ActorRef target = spawnCollector("target");
		ScheduledFuture<?> future = target.tellAtFixedRate(new Tick(1), null, 10, 10, TimeUnit.MILLISECONDS);
		assertNotNull(received.poll(10, TimeUnit.SECONDS));

		system.stop(target);

		waitUntil(future::isCancelled);
		assertTrue(future.isDone());
	}

	@Test
	void aPeriodicTellToAPoolCancelsItselfOnceAllMembersStopped() throws Exception {
		ActorRef pool = spawnCollector("pool", SpawnOptions.pooled(3));
		ScheduledFuture<?> future = pool.tellAtFixedRate(new Tick(1), null, 10, 10, TimeUnit.MILLISECONDS);
		assertNotNull(received.poll(10, TimeUnit.SECONDS));

		system.stop(pool);

		waitUntil(future::isCancelled);
		assertTrue(future.isDone());
	}

	// ================================================================
	// Arm and cancel on the actor's own carrier
	// ================================================================

	/**
	 * The number of timers still held by the system, or -1 if the scheduler under
	 * test cannot tell.
	 */
	protected int pendingTimers() {
		return -1;
	}

	@Test
	void nothingStaysAfterManyArmAndCancelOnSelf() throws Exception {
		CompletableFuture<Integer> cancelled = new CompletableFuture<>();
		ActorRef actor = system.spawn("armer", () -> (message, sender, context) -> {
			int count = 0;
			for (int i = 0; i < 10_000; i++) {
				if (context.self().tell(new Tick(i), 30, TimeUnit.SECONDS).cancel(false)) {
					count++;
				}
			}
			cancelled.complete(count);
		});

		actor.tell(new Tick(0));

		assertEquals(10_000, cancelled.get(30, TimeUnit.SECONDS));
		if (pendingTimers() >= 0) {
			waitUntil(() -> pendingTimers() == 0);
		}
		assertNull(received.poll(100, TimeUnit.MILLISECONDS));
	}

	// ================================================================
	// Shutdown
	// ================================================================

	@Test
	void afterShutdownNoPendingTimerFires() throws Exception {
		ActorRef target = spawnCollector("target");
		ScheduledFuture<?> delayed = target.tell(new Tick(1), 300, TimeUnit.MILLISECONDS);
		ScheduledFuture<?> periodic = target.tellAtFixedRate(new Tick(2), null, 300, 50, TimeUnit.MILLISECONDS);

		system.shutdown();

		waitUntil(() -> delayed.isCancelled() && periodic.isCancelled());
		assertNull(received.poll(600, TimeUnit.MILLISECONDS));
	}

	@Test
	void aDelayedTellAfterShutdownReturnsACancelledFuture() throws Exception {
		ActorRef target = spawnCollector("target");
		system.shutdown();
		assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));

		ScheduledFuture<?> delayed = target.tell(new Tick(1), 10, TimeUnit.MILLISECONDS);
		ScheduledFuture<?> periodic = target.tellAtFixedRate(new Tick(2), null, 10, 10, TimeUnit.MILLISECONDS);

		assertTrue(delayed.isCancelled());
		assertTrue(periodic.isCancelled());
		assertNull(received.poll(200, TimeUnit.MILLISECONDS));
	}

	@Test
	void aPendingTimerDoesNotHoldAwaitTermination() throws Exception {
		ActorRef target = spawnCollector("target");
		target.tell(new Tick(1), 1, TimeUnit.HOURS);
		target.tellAtFixedRate(new Tick(2), null, 1, 1, TimeUnit.HOURS);

		system.shutdown();

		assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
	}

	// ================================================================
	// Arguments
	// ================================================================

	@Test
	void nullArgumentsAreRejected() {
		ActorRef target = spawnCollector("target");

		assertThrows(NullPointerException.class, () -> target.tell(null, 1, TimeUnit.MILLISECONDS));
		assertThrows(NullPointerException.class, () -> target.tell(new Tick(1), 1, null));
		assertThrows(NullPointerException.class, () -> target.tell(null, null, 1, TimeUnit.MILLISECONDS));
		assertThrows(NullPointerException.class, () -> target.tellAtFixedRate(null, null, 1, 1, TimeUnit.MILLISECONDS));
		assertThrows(NullPointerException.class, () -> target.tellAtFixedRate(new Tick(1), null, 1, 1, null));
	}

	@Test
	void aPeriodOfZeroOrLessIsRejected() {
		ActorRef target = spawnCollector("target");

		assertThrows(IllegalArgumentException.class,
				() -> target.tellAtFixedRate(new Tick(1), null, 1, 0, TimeUnit.MILLISECONDS));
		assertThrows(IllegalArgumentException.class,
				() -> target.tellAtFixedRate(new Tick(1), null, 1, -5, TimeUnit.MILLISECONDS));
	}

	@Test
	void theTimerTickMustBeAtLeastOneMillisecond() {
		assertThrows(NullPointerException.class, () -> newBuilder().timerTick(null));
		assertThrows(IllegalArgumentException.class, () -> newBuilder().timerTick(Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> newBuilder().timerTick(Duration.ofMillis(-1)));
		assertThrows(IllegalArgumentException.class, () -> newBuilder().timerTick(Duration.ofNanos(999_999)));
		newBuilder().timerTick(Duration.ofMillis(1));
	}

	@Test
	void aPromiseRefDoesNotSupportDelayedTells() throws Exception {
		ActorRef target = system.spawn("target", Replier::new);

		target.ask(new TryDelayedOnSender()).get(10, TimeUnit.SECONDS);

		Throwable failure = senderTimerFailure.get(10, TimeUnit.SECONDS);
		assertInstanceOf(UnsupportedOperationException.class, failure);
	}

	// ================================================================

	protected void waitUntil(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.getAsBoolean()) {
			assertTrue(System.nanoTime() < deadline, "the condition never became true");
			Thread.sleep(1);
		}
	}

	/** Records every message it gets, and when. */
	protected final class Collector implements Actor {

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) {
			arrivals.add(System.nanoTime());
			received.add(message);
		}
	}

	/** Tries a delayed tell on the promise it was asked from. */
	private final class Replier implements Actor {

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) {
			try {
				sender.tell(new Reply(), 1, TimeUnit.MILLISECONDS);
				senderTimerFailure.complete(new AssertionError("no exception"));
			} catch (Throwable t) {
				senderTimerFailure.complete(t);
			}
			sender.tell(new Reply(), context.self());
		}
	}
}
