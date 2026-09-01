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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every {@link ActorSystem} implementation must satisfy, independent
 * of how it schedules an actor's work underneath: spawning and naming, ordering
 * and mutual exclusion, path lookup, failure handling, and what happens to work
 * submitted after {@link ActorSystem#shutdown()}.
 *
 * <p>
 * Subclasses supply the implementation under test via
 * {@link #newActorSystem()}. This class deliberately asserts nothing about
 * carrier affinity — those assertions belong to the carrier-affine
 * implementation's own suite ({@code CarrierAffinityTest}). The hierarchy,
 * {@code ask}, graceful stop and supervision each have a shared suite of their
 * own: {@link ActorHierarchyTest}, {@link AskTest}, {@link GracefulStopTest},
 * {@link SupervisionTest}.
 */
public abstract class AbstractActorSystemTest {

	/**
	 * A request. It carries no destination: routing is entirely the
	 * {@link ActorRef} a message is sent through.
	 */
	record Ping(int seq) implements Message {
	}

	/**
	 * The reply to a {@link Ping} — a plain {@link Message}, like every reply in
	 * this API.
	 */
	record Pong(int seq) implements Message {
	}

	/**
	 * Creates a fresh {@link ActorSystem} for a single test. Called once per test
	 * method, from {@link #setUp()}.
	 */
	protected abstract ActorSystem newActorSystem();

	private ActorSystem system;

	@BeforeEach
	void setUp() {
		system = newActorSystem();
	}

	/**
	 * Shuts the system down and asserts the cascade actually completed. A test that
	 * already shut it down is unaffected: both operations are idempotent, and the
	 * second {@code awaitTermination} returns immediately.
	 */
	@AfterEach
	void tearDown() throws InterruptedException {
		system.shutdown();
		assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
	}

	@Test
	void spawningTwoRootsWithTheSameNameIsRejected() {
		system.spawn("a", () -> new RecordingActor(0));

		assertThrows(IllegalStateException.class, () -> system.spawn("a", () -> new RecordingActor(0)));
	}

	@Test
	void anInvalidNameIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> system.spawn("  ", () -> new RecordingActor(0)));
		assertThrows(IllegalArgumentException.class, () -> system.spawn("a/b", () -> new RecordingActor(0)));
	}

	/**
	 * The {@link ActorSystem} contract makes both implementations reject work after
	 * {@link ActorSystem#shutdown()} in the same way, even though what they shut
	 * down underneath differs completely (an owned dispatch pool vs. carriers that
	 * are never stopped at all) — so this belongs here, not in either
	 * implementation's own suite.
	 */
	@Test
	void spawningAfterShutdownIsRejected() {
		system.shutdown();

		assertThrows(IllegalStateException.class, () -> system.spawn("a", () -> new RecordingActor(0)));
	}

	/**
	 * The companion to {@link #spawningAfterShutdownIsRejected()}, for the spawn
	 * that is not cleanly after the shutdown but concurrent with it. Such a spawn
	 * may legitimately either be rejected or succeed — but a root it does create
	 * must still be stopped, or the shutdown never completes at all: one root left
	 * running holds the registry non-empty forever, so {@code awaitTermination} can
	 * never return {@code true} and the system's own resources (its scheduler, its
	 * ask executor) are never released.
	 *
	 * <p>
	 * Necessarily probabilistic: the window is the handful of statements between
	 * the shutting-down check and the new root becoming visible to the shutdown's
	 * snapshot, and there is no seam to stop a thread inside it. The rounds below
	 * are what give it enough attempts to matter — an unfixed engine fails this
	 * within the first few.
	 */
	@Test
	void aRootSpawnedWhileShuttingDownIsStillStopped() throws Exception {
		for (int round = 0; round < 50; round++) {
			ActorSystem racing = newActorSystem();
			CountDownLatch spawning = new CountDownLatch(1);

			Thread spawner = Thread.ofPlatform().start(() -> {
				for (int i = 0; i < 100; i++) {
					try {
						racing.spawn("x" + i, () -> new RecordingActor(0));
					} catch (IllegalStateException shuttingDown) {
						break;
					} finally {
						spawning.countDown();
					}
				}
			});

			// Shut down while the spawn loop is provably already running, so that
			// beginShutdown lands mid-spawn rather than before the first one.
			assertTrue(spawning.await(10, TimeUnit.SECONDS));
			racing.shutdown();
			spawner.join();

			assertTrue(racing.awaitTermination(10, TimeUnit.SECONDS),
					"round " + round + ": a root spawned as the system shut down was never stopped");
		}
	}

	/**
	 * {@code tell} is fire-and-forget, so a message sent to a system that is gone
	 * is dropped rather than reported: the send returns normally and nothing is
	 * delivered. Its {@code ask} counterpart is
	 * {@link #askingAfterShutdownFailsTheReply()}.
	 */
	@Test
	void tellingAfterShutdownIsSilentlyIgnored() throws Exception {
		RecordingActor actor = new RecordingActor(1);
		ActorRef ref = system.spawn("a", () -> actor);

		ref.tell(new Ping(0));
		assertTrue(actor.awaitHandled(10, TimeUnit.SECONDS));

		system.shutdown();
		assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));

		ref.tell(new Ping(1));

		/*
		 * There is nothing to await here -- the point is that nothing happens -- so
		 * give a delivery that should not occur ample time to occur anyway.
		 */
		Thread.sleep(200);

		assertEquals(List.of(0), actor.handled());
	}

	@Test
	void askingAfterShutdownFailsTheReply() {
		ActorRef ref = system.spawn("a", () -> new RecordingActor(0));

		system.shutdown();

		ExecutionException e = assertThrows(ExecutionException.class, () -> ref.ask(new Ping(0)).get());

		assertInstanceOf(IllegalStateException.class, e.getCause());
	}

	@Test
	void findActorResolvesALiveRootAndNothingElse() {
		ActorRef ref = system.spawn("db", () -> new RecordingActor(0));

		assertSame(ref, system.findActor("db").orElseThrow(), "a bare name resolves as a root path");
		assertSame(ref, system.findActor("/db").orElseThrow());
		assertTrue(system.findActor("/nobody").isEmpty());
		assertTrue(system.findActor("nobody").isEmpty());
	}

	@Test
	void messagesAreHandledInOrderAndNeverConcurrently() throws Exception {
		int count = 1_000;

		RecordingActor actor = new RecordingActor(count);
		ActorRef ref = system.spawn("a", () -> actor);

		for (int i = 0; i < count; i++) {
			ref.tell(new Ping(i));
		}

		assertTrue(actor.awaitHandled(30, TimeUnit.SECONDS));

		List<Integer> handled = actor.handled();

		assertEquals(count, handled.size());

		for (int i = 0; i < count; i++) {
			assertEquals(i, handled.get(i));
		}

		assertEquals(0, actor.overlaps(), "two messages were handled concurrently by the same actor");
	}

	/**
	 * The default {@link Actor#supervisorStrategy()} is {@link Directive#RESUME},
	 * so a failing message is dropped and the actor keeps its instance, its state
	 * and its mailbox. {@link SupervisionTest} covers the other two directives.
	 */
	@Test
	void aThrowingActorKeepsRunningUnderTheDefaultStrategy() throws Exception {
		ActorRef ref = system.spawn("a", BoomOnZeroActor::new);

		ref.tell(new Ping(0));

		Message reply = ref.ask(new Ping(1)).get(10, TimeUnit.SECONDS);

		assertEquals(new Pong(1), reply);
	}

	/**
	 * {@link Actor#preStart} is enqueued as this actor's very first lifecycle step,
	 * through the same mailbox as its messages, so no message can overtake it.
	 *
	 * <p>
	 * This replaces an older regression test on the previous API, where an actor
	 * received its context through a separate callback and a message could reach
	 * {@code onMessage} before it (an intermittent NPE, about one run in five). The
	 * hazard is now structurally impossible: the context is a parameter of every
	 * hook, including this one, so there is no window in which it does not exist.
	 */
	@Test
	void preStartRunsBeforeTheFirstMessage() throws Exception {
		ActorRef ref = system.spawn("a", StartCountingActor::new);

		Message reply = ref.ask(new Ping(0)).get(10, TimeUnit.SECONDS);

		assertEquals(new Pong(1), reply, "onReceive ran before preStart, or preStart ran more than once");
	}

	// ================================================================
	// Actors
	// ================================================================

	/**
	 * Records the order in which messages are handled, and detects any concurrent
	 * handling of two of them.
	 */
	static final class RecordingActor implements Actor {

		/**
		 * Deliberately a plain, unsynchronized {@link ArrayList}: the actor system must
		 * be the only thing serializing accesses to an actor's own state. It is read
		 * only after {@link #awaitHandled}, whose latch establishes the happens-before.
		 */
		private final List<Integer> handled = new ArrayList<>();

		private final AtomicInteger concurrent = new AtomicInteger();

		private final AtomicInteger overlaps = new AtomicInteger();

		private final CountDownLatch expected;

		RecordingActor(int expectedMessages) {
			this.expected = new CountDownLatch(expectedMessages);
		}

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) {
			if (concurrent.incrementAndGet() != 1) {
				overlaps.incrementAndGet();
			}
			try {
				handled.add(((Ping) message).seq());
			} finally {
				concurrent.decrementAndGet();
				expected.countDown();
			}
		}

		boolean awaitHandled(long timeout, TimeUnit unit) throws InterruptedException {
			return expected.await(timeout, unit);
		}

		List<Integer> handled() {
			return List.copyOf(handled);
		}

		int overlaps() {
			return overlaps.get();
		}
	}

	/**
	 * Fails on {@link Ping} zero and replies to every other one.
	 */
	static final class BoomOnZeroActor implements Actor {

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) {
			int seq = ((Ping) message).seq();
			if (seq == 0) {
				throw new IllegalStateException("boom");
			}
			sender.tell(new Pong(seq), context.self());
		}
	}

	/**
	 * Replies with the number of times its own {@code preStart} has run — so a
	 * reply of anything other than {@code 1} means the two ran out of order, or
	 * more than once.
	 */
	static final class StartCountingActor implements Actor {

		private int starts;

		@Override
		public void preStart(ActorContext context) {
			starts++;
		}

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) {
			sender.tell(new Pong(starts), context.self());
		}
	}
}
