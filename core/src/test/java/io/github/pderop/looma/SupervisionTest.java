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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Per-actor supervision: what each {@link Directive} does to a failing actor,
 * and which strategy an actor actually ends up applying.
 *
 * <p>
 * This project's supervision is <b>self</b>-supervision — an actor's own
 * strategy decides its fate, there is no parent supervisor — and a
 * {@link Directive#RESTART} deliberately leaves the actor's children running (a
 * documented deviation from Akka, chosen for simplicity).
 */
public abstract class SupervisionTest {

	/** Increments the actor's counter. */
	private record Bump() implements Message {
	}

	/** Makes the actor throw. */
	private record Boom() implements Message {
	}

	/** Asks the actor to report its instance id and its counter. */
	private record Report() implements Message {
	}

	/** Asks the actor to spawn a child under itself. */
	private record Spawn(String childName) implements Message {
	}

	private record Snapshot(int instance, int counter) implements Message {
	}

	private record Spawned(String path) implements Message {
	}

	protected abstract ActorSystem newActorSystem();

	private ActorSystem system;

	@BeforeEach
	void setUp() {
		system = newActorSystem();
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		system.shutdown();
		assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
	}

	/**
	 * {@link Directive#RESUME} — the default — drops the failed message and keeps
	 * everything else: the same instance, its accumulated state, and its mailbox.
	 */
	@Test
	void resumeKeepsTheInstanceAndItsState() throws Exception {
		Family family = new Family(cause -> Directive.RESUME);
		ActorRef ref = system.spawn("a", family.supplier());

		ref.tell(new Bump());
		ref.tell(new Boom());
		ref.tell(new Bump());

		assertEquals(new Snapshot(0, 2), report(ref));
		assertEquals(List.of("started:0"), family.events(), "no lifecycle hook other than preStart may have run");
	}

	/**
	 * {@link Directive#RESTART} replaces the instance: {@code preRestart} runs on
	 * the failing one, a fresh one comes from the {@code Supplier} with its state
	 * reset, {@code postRestart} runs on that one, and the pending mailbox resumes
	 * on it.
	 */
	@Test
	void restartInstallsAFreshInstanceAndResumesTheMailbox() throws Exception {
		Family family = new Family(cause -> Directive.RESTART);
		ActorRef ref = system.spawn("a", family.supplier());

		ref.tell(new Bump());
		ref.tell(new Boom());
		ref.tell(new Bump());

		assertEquals(new Snapshot(1, 1), report(ref), "the second instance must start from a reset counter");
		assertEquals(List.of("started:0", "preRestart:0", "postRestart:1"), family.events(),
				"preRestart runs on the old instance, postRestart on the new one, in that order");
	}

	/**
	 * {@link Directive#STOP} terminates the failing actor and, through the ordinary
	 * cascade, its children — but it must not tear the system down, even when it
	 * was the system's last actor. Spawning again afterwards is the assertion that
	 * catches that: the termination callback is gated on {@code shutdown()} having
	 * been called, precisely for this case.
	 */
	@Test
	void stopTerminatesTheActorAndItsChildrenWithoutShuttingTheSystemDown() throws Exception {
		Family family = new Family(cause -> Directive.STOP);
		ActorRef ref = system.spawn("a", family.supplier());

		assertEquals(new Spawned("/a/child"), ref.ask(new Spawn("child")).get(10, TimeUnit.SECONDS));

		ref.tell(new Boom());

		waitUntil(() -> system.findActor("/a").isEmpty());
		waitUntil(() -> system.findActor("/a/child").isEmpty());

		ActorRef next = system.spawn("b", new Family(cause -> Directive.RESUME).supplier());

		assertEquals(new Snapshot(0, 0), report(next), "the system must still be usable after a STOP");
	}

	/**
	 * A strategy passed at {@code spawn} time wins over the actor's own
	 * {@link Actor#supervisorStrategy()}: here an actor whose own strategy says
	 * RESTART is spawned with RESUME, and therefore keeps its instance.
	 */
	@Test
	void aStrategyPassedAtSpawnOverridesTheActorsOwn() throws Exception {
		Family family = new Family(cause -> Directive.RESTART);
		ActorRef ref = system.spawn("a", family.supplier(), SpawnOptions.supervisedBy(cause -> Directive.RESUME));

		ref.tell(new Bump());
		ref.tell(new Boom());

		assertEquals(new Snapshot(0, 1), report(ref));
		assertEquals(List.of("started:0"), family.events(), "no restart may have happened");
	}

	/**
	 * Hammers a repeatedly restarting actor with sends. A restart must keep the
	 * same loop virtual thread: starting a second one would put two consumers on
	 * the same mailbox, and the only visible symptom would be overlapping
	 * {@code onReceive} calls, under supervision load only.
	 */
	@Test
	void aRestartingActorNeverHandlesTwoMessagesAtOnce() throws Exception {
		Family family = new Family(cause -> Directive.RESTART);
		ActorRef ref = system.spawn("a", family.supplier());

		int rounds = 500;

		for (int i = 0; i < rounds; i++) {
			ref.tell(new Bump());
			ref.tell(new Boom());
		}

		Snapshot snapshot = report(ref);

		assertEquals(rounds, snapshot.instance(), "every Boom must have installed exactly one fresh instance");
		assertEquals(0, family.overlaps(), "two messages were handled concurrently by the same actor");
	}

	// ================================================================
	// Helpers
	// ================================================================

	private Snapshot report(ActorRef ref) throws Exception {
		return ref.ask(new Report(), Snapshot.class).get(10, TimeUnit.SECONDS);
	}

	private void waitUntil(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.getAsBoolean()) {
			assertTrue(System.nanoTime() < deadline, "the condition never became true");
			Thread.sleep(1);
		}
	}

	// ================================================================
	// Actors
	// ================================================================

	/**
	 * Everything a single actor's successive instances share: the strategy they
	 * apply, the instance counter a restart advances, the lifecycle events they
	 * record, and the overlap detector.
	 *
	 * <p>
	 * It exists because a restart creates a <em>new</em> instance from the same
	 * {@code Supplier}: anything that must survive that — and anything the test
	 * wants to observe afterwards — cannot live in the actor instance itself. The
	 * event queue is a {@link ConcurrentLinkedQueue}, not a plain list, because
	 * successive instances may well record from different threads.
	 */
	private static final class Family {

		private final SupervisionStrategy strategy;

		private final Queue<String> events = new ConcurrentLinkedQueue<>();

		private final AtomicInteger instances = new AtomicInteger();

		private final AtomicInteger concurrent = new AtomicInteger();

		private final AtomicInteger overlaps = new AtomicInteger();

		Family(SupervisionStrategy strategy) {
			this.strategy = strategy;
		}

		Supplier<Actor> supplier() {
			return () -> new SupervisedActor(this);
		}

		List<String> events() {
			return List.copyOf(events);
		}

		int overlaps() {
			return overlaps.get();
		}
	}

	/**
	 * Counts its {@link Bump}s, fails on {@link Boom}, and records every lifecycle
	 * hook it runs together with the id of the instance running it — so a restart
	 * shows up both as a new instance id and as a reset counter.
	 */
	private static final class SupervisedActor implements Actor {

		private final Family family;

		private final int instance;

		private int counter;

		private SupervisedActor(Family family) {
			this.family = family;
			this.instance = family.instances.getAndIncrement();
		}

		@Override
		public SupervisionStrategy supervisorStrategy() {
			return family.strategy;
		}

		@Override
		public void preStart(ActorContext context) {
			family.events.add("started:" + instance);
		}

		@Override
		public void preRestart(Throwable cause, Optional<Message> message, ActorContext context) {
			family.events.add("preRestart:" + instance);
		}

		@Override
		public void postRestart(Throwable cause, ActorContext context) {
			family.events.add("postRestart:" + instance);
		}

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) {
			if (family.concurrent.incrementAndGet() != 1) {
				family.overlaps.incrementAndGet();
			}
			try {
				switch (message) {
					case Bump ignored -> counter++;
					case Boom ignored -> throw new IllegalStateException("boom");
					case Report ignored -> sender.tell(new Snapshot(instance, counter), context.self());
					case Spawn spawn -> {
						/*
						 * The child gets a family of its own: it would otherwise share this actor's
						 * overlap detector, and a parent and child running at the same time -- which is
						 * perfectly legal -- would register as a false overlap.
						 */
						ActorRef child = context.spawn(spawn.childName(), new Family(family.strategy).supplier());
						sender.tell(new Spawned(child.path()), context.self());
					}
					default -> throw new IllegalStateException("unexpected message: " + message);
				}
			} finally {
				family.concurrent.decrementAndGet();
			}
		}
	}
}
