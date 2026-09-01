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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The graceful cascading stop, shared by both {@link ActorSystem}
 * implementations: an actor keeps draining what it was already given, then its
 * children are stopped innermost-first, then {@code postStop} unwinds back up.
 *
 * <p>
 * Everything here is driven through {@link ActorSystem#stop(ActorRef)} rather
 * than {@link ActorContext#stop(ActorRef)}: the JUnit thread has no
 * {@code ActorContext} of its own, which is exactly why that overload exists.
 */
public abstract class GracefulStopTest {

	private record Work(int seq) implements Message {
	}

	/** Asks an actor to spawn a child of the given name under itself. */
	private record Spawn(String childName) implements Message {
	}

	private record Spawned(ActorRef child) implements Message {
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
	 * A stop drains: everything already in the mailbox when {@code stop} is called
	 * is still delivered. The first message blocks the loop so that the whole batch
	 * is provably queued before the stop begins.
	 */
	@Test
	void messagesQueuedBeforeStopAreStillProcessed() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		Queue<String> events = new ConcurrentLinkedQueue<>();

		ActorRef worker = system.spawn("worker", () -> new TreeActor("worker", events, release));

		worker.tell(new Work(0)); // blocks on the latch, occupying the loop
		for (int i = 1; i <= 5; i++) {
			worker.tell(new Work(i));
		}

		system.stop(worker);
		release.countDown();

		waitUntil(() -> system.findActor("/worker").isEmpty());

		assertEquals(List.of("worker:started", "worker:0", "worker:1", "worker:2", "worker:3", "worker:4", "worker:5",
				"worker:stopped"), List.copyOf(events));
	}

	@Test
	void tellAfterStopIsDropped() throws Exception {
		Queue<String> events = new ConcurrentLinkedQueue<>();

		ActorRef worker = system.spawn("worker", () -> new TreeActor("worker", events, null));

		system.stop(worker);
		waitUntil(() -> system.findActor("/worker").isEmpty());

		worker.tell(new Work(1));
		Thread.sleep(200);

		assertEquals(List.of("worker:started", "worker:stopped"), List.copyOf(events));
	}

	/**
	 * {@code postStop} runs innermost-first: a parent's own {@code postStop} only
	 * runs once every one of its descendants has fully stopped.
	 */
	@Test
	void postStopRunsChildrenFirstThenTheParent() throws Exception {
		Queue<String> events = new ConcurrentLinkedQueue<>();

		ActorRef parent = system.spawn("parent", () -> new TreeActor("parent", events, null));
		ActorRef child = spawnChild(parent, "child");
		spawnChild(child, "grandChild");

		system.stop(parent);

		waitUntil(() -> system.findActor("/parent").isEmpty());

		assertEquals(List.of("grandChild:stopped", "child:stopped", "parent:stopped"), stopEvents(events));
	}

	/**
	 * The single most common stop case, and the one that silently hangs if
	 * {@code stop} never unparks a parked loop: an actor with an empty mailbox is
	 * sitting in {@code LockSupport.park}, so the stop envelope must unpark it.
	 */
	@Test
	void stoppingAnIdleActorStillTerminatesIt() throws Exception {
		Queue<String> events = new ConcurrentLinkedQueue<>();

		ActorRef idle = system.spawn("idle", () -> new TreeActor("idle", events, null));

		waitUntil(() -> events.contains("idle:started"));

		system.stop(idle);

		waitUntil(() -> system.findActor("/idle").isEmpty());

		assertEquals(List.of("idle:started", "idle:stopped"), List.copyOf(events));
	}

	@Test
	void stopIsIdempotent() throws Exception {
		Queue<String> events = new ConcurrentLinkedQueue<>();

		ActorRef worker = system.spawn("worker", () -> new TreeActor("worker", events, null));

		system.stop(worker);
		system.stop(worker);
		waitUntil(() -> system.findActor("/worker").isEmpty());
		system.stop(worker);

		Thread.sleep(200);

		assertEquals(1, events.stream().filter("worker:stopped"::equals).count());
	}

	/**
	 * Stopping the last root actor of a still-running system must not tear the
	 * system down: only {@link ActorSystem#shutdown()} does that. If the two were
	 * conflated, this next spawn would be rejected — or worse, silently never
	 * scheduled.
	 */
	@Test
	void stoppingTheLastRootDoesNotShutTheSystemDown() throws Exception {
		Queue<String> events = new ConcurrentLinkedQueue<>();

		ActorRef only = system.spawn("only", () -> new TreeActor("only", events, null));

		system.stop(only);
		waitUntil(() -> system.findActor("/only").isEmpty());

		ActorRef next = system.spawn("next", () -> new TreeActor("next", events, null));

		assertEquals("/next/child", spawnChild(next, "child").path(),
				"the system must still be usable after its last root was stopped");
	}

	@Test
	void shutdownStopsAWholeTree() throws Exception {
		Queue<String> events = new ConcurrentLinkedQueue<>();

		ActorRef parent = system.spawn("parent", () -> new TreeActor("parent", events, null));
		ActorRef child = spawnChild(parent, "child");
		spawnChild(child, "grandChild");

		system.shutdown();

		assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));

		assertEquals(List.of("grandChild:stopped", "child:stopped", "parent:stopped"), stopEvents(events));
		assertTrue(system.findActor("/parent").isEmpty());
	}

	// ================================================================
	// Helpers
	// ================================================================

	private Message askSpawn(ActorRef parent, String childName) throws Exception {
		return parent.ask(new Spawn(childName)).get(10, TimeUnit.SECONDS);
	}

	private ActorRef spawnChild(ActorRef parent, String childName) throws Exception {
		return ((Spawned) askSpawn(parent, childName)).child();
	}

	/**
	 * Keeps only the {@code postStop} events, so a stop-order assertion is not
	 * entangled with the start order that preceded it.
	 */
	private List<String> stopEvents(Queue<String> events) {
		return events.stream().filter(event -> event.endsWith(":stopped")).toList();
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
	 * Appends every lifecycle event and every message it handles to a shared queue,
	 * so a whole subtree's stop order can be asserted.
	 *
	 * <p>
	 * That queue is deliberately a {@link ConcurrentLinkedQueue}: unlike an actor's
	 * own state, it is written by several actors, each on its own thread.
	 */
	private static final class TreeActor implements Actor {

		private final String id;

		private final Queue<String> events;

		/**
		 * Released by the test to unblock the first message, or {@code null} to never
		 * block at all.
		 */
		private final CountDownLatch release;

		TreeActor(String id, Queue<String> events, CountDownLatch release) {
			this.id = id;
			this.events = events;
			this.release = release;
		}

		@Override
		public void preStart(ActorContext context) {
			events.add(id + ":started");
		}

		@Override
		public void postStop(ActorContext context) {
			events.add(id + ":stopped");
		}

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) throws Exception {
			switch (message) {
				case Work work -> {
					if (work.seq() == 0 && release != null) {
						release.await(10, TimeUnit.SECONDS);
					}
					events.add(id + ":" + work.seq());
				}
				case Spawn spawn -> sender.tell(
						new Spawned(
								context.spawn(spawn.childName(), () -> new TreeActor(spawn.childName(), events, null))),
						context.self());
				default -> throw new IllegalStateException("unexpected message: " + message);
			}
		}
	}
}
