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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The parent/child hierarchy every {@link ActorSystem} implementation shares:
 * how paths are composed, how children are spawned and listed, and how a path
 * is resolved.
 *
 * <p>
 * Nothing here is scheduler-specific. What <em>is</em> scheduler-specific is
 * where a child lands: on the carrier-affine implementation it inherits its
 * parent's home carrier, which {@code CarrierAffinityTest} asserts separately.
 */
public abstract class ActorHierarchyTest {

	/**
	 * Asks an actor to spawn a child of the given name, and to reply with that
	 * child's reference — or with {@link SpawnFailed} if the spawn was rejected.
	 */
	private record Spawn(String childName) implements Message {
	}

	/**
	 * Asks an actor to reply with a snapshot of its {@link ActorContext#children()}
	 * paths.
	 */
	private record ListChildren() implements Message {
	}

	/**
	 * Asks an actor to resolve a path through its own {@link ActorContext}, and to
	 * reply with what it found (or an empty path).
	 */
	private record Resolve(String path) implements Message {
	}

	private record Spawned(ActorRef child) implements Message {
	}

	private record SpawnFailed(String exceptionType) implements Message {
	}

	private record Children(List<String> paths) implements Message {
	}

	private record Resolved(String path) implements Message {
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

	@Test
	void aRootActorIsNamedAtTheTopLevel() {
		ActorRef db = system.spawn("db", ParentActor::new);

		assertEquals("db", db.name());
		assertEquals("/db", db.path());
	}

	@Test
	void aChildPathIsComposedFromItsParents() throws Exception {
		ActorRef db = system.spawn("db", ParentActor::new);

		ActorRef worker = spawnChild(db, "worker");

		assertEquals("worker", worker.name(), "name() stays the simple name, never the full path");
		assertEquals("/db/worker", worker.path());

		ActorRef grandChild = spawnChild(worker, "cursor");

		assertEquals("/db/worker/cursor", grandChild.path());
	}

	@Test
	void childrenListsLiveChildrenOnly() throws Exception {
		ActorRef db = system.spawn("db", ParentActor::new);

		assertEquals(List.of(), childPathsOf(db));

		spawnChild(db, "w1");
		spawnChild(db, "w2");

		assertEquals(List.of("/db/w1", "/db/w2"), childPathsOf(db).stream().sorted().toList());

		system.stop(system.findActor("/db/w1").orElseThrow());

		waitUntil(() -> system.findActor("/db/w1").isEmpty());

		assertEquals(List.of("/db/w2"), childPathsOf(db));
	}

	@Test
	void aChildIsFoundByItsFullPathFromTheSystem() throws Exception {
		ActorRef db = system.spawn("db", ParentActor::new);

		ActorRef worker = spawnChild(db, "worker");

		assertSame(worker, system.findActor("/db/worker").orElseThrow());
		assertTrue(system.findActor("/db/nobody").isEmpty());
	}

	/**
	 * A context resolves a path exactly as the system does — absolutely, never
	 * relative to the actor doing the lookup. A worker at {@code /db/worker} asking
	 * for {@code "sibling"} therefore resolves the <em>root</em> {@code /sibling},
	 * not {@code /db/sibling}. Surprising, deliberate, and pinned here.
	 */
	@Test
	void aContextResolvesPathsAbsolutely() throws Exception {
		ActorRef db = system.spawn("db", ParentActor::new);
		ActorRef sibling = system.spawn("sibling", ParentActor::new);
		ActorRef worker = spawnChild(db, "worker");

		assertEquals(sibling.path(), resolveFrom(worker, "sibling"));
		assertEquals(db.path(), resolveFrom(worker, "/db"));
		assertEquals("", resolveFrom(worker, "/db/sibling"));
	}

	@Test
	void twoSiblingsCannotShareAName() throws Exception {
		ActorRef db = system.spawn("db", ParentActor::new);

		spawnChild(db, "worker");

		assertEquals(new SpawnFailed(IllegalStateException.class.getName()), spawnResult(db, "worker"));
	}

	@Test
	void aChildNameContainingASlashIsRejected() throws Exception {
		ActorRef db = system.spawn("db", ParentActor::new);

		assertEquals(new SpawnFailed(IllegalArgumentException.class.getName()), spawnResult(db, "a/b"));
	}

	/**
	 * Uniqueness is enforced between siblings only: two actors under different
	 * parents may share a simple name, since their full paths still differ.
	 */
	@Test
	void twoActorsUnderDifferentParentsMayShareAName() throws Exception {
		ActorRef db = system.spawn("db", ParentActor::new);
		ActorRef cache = system.spawn("cache", ParentActor::new);

		assertEquals("/db/worker", spawnChild(db, "worker").path());
		assertEquals("/cache/worker", spawnChild(cache, "worker").path());
	}

	// ================================================================
	// Helpers
	// ================================================================

	private Message spawnResult(ActorRef parent, String childName) throws Exception {
		return parent.ask(new Spawn(childName)).get(10, TimeUnit.SECONDS);
	}

	private ActorRef spawnChild(ActorRef parent, String childName) throws Exception {
		return parent.ask(new Spawn(childName), Spawned.class).get(10, TimeUnit.SECONDS).child();
	}

	private List<String> childPathsOf(ActorRef parent) throws Exception {
		return parent.ask(new ListChildren(), Children.class).get(10, TimeUnit.SECONDS).paths();
	}

	private String resolveFrom(ActorRef actor, String path) throws Exception {
		return actor.ask(new Resolve(path), Resolved.class).get(10, TimeUnit.SECONDS).path();
	}

	/**
	 * Waits for a condition the engine reaches asynchronously — a stopped actor
	 * leaving the registry, here — rather than sleeping a fixed amount.
	 */
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
	 * Answers the three hierarchy questions this suite asks, from inside its own
	 * {@link ActorContext} — which is the only place {@code spawn} and
	 * {@code children()} exist.
	 *
	 * <p>
	 * A rejected {@code spawn} is caught and reported back as a
	 * {@link SpawnFailed}, rather than being left to propagate: letting it out of
	 * {@code onReceive} would hand it to this actor's supervision strategy (the
	 * default {@link Directive#RESUME}), and the {@code ask} that triggered it
	 * would then have no reply at all and the future would stay open.
	 */
	private static final class ParentActor implements Actor {

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) {
			switch (message) {
				case Spawn spawn -> sender.tell(trySpawn(spawn.childName(), context), context.self());
				case ListChildren ignored ->
					sender.tell(new Children(context.children().stream().map(ActorRef::path).toList()), context.self());
				case Resolve resolve -> sender.tell(
						new Resolved(context.findActor(resolve.path()).map(ActorRef::path).orElse("")), context.self());
				default -> throw new IllegalStateException("unexpected message: " + message);
			}
		}

		private Message trySpawn(String childName, ActorContext context) {
			try {
				return new Spawned(context.spawn(childName, ParentActor::new));
			} catch (RuntimeException e) {
				return new SpawnFailed(e.getClass().getName());
			}
		}
	}
}
