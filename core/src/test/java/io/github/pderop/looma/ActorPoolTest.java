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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link SpawnOptions#poolSize()}: spawning N actors of the same type behind
 * one load-balancing {@link ActorRef}, on both spawning surfaces, and the
 * properties every {@link ActorSystem} implementation must give it.
 *
 * <p>
 * The assertions here are deliberately about <b>routing and identity</b>, and
 * about placement only where both schedulers can agree: a pool applies its
 * {@link Placement} once per actor, so a placement naming one carrier
 * ({@code inherit()}, {@code carrier(0)}) puts the whole pool there — which is
 * assertable with a single carrier. The other half of that rule, a pool spread
 * one actor per carrier by {@code roundRobin()}, needs more than one carrier to
 * exist at all and belongs to {@code CarrierPlacementTest} instead.
 *
 * <p>
 * Round-robin routing is asserted as an <b>exact</b> distribution, not merely a
 * spread: a pool of {@code n} fed {@code k * n} messages must have given each
 * routee exactly {@code k}. That is what makes it a load balancer rather than a
 * random spray, and the cursor being per pool is what makes it deterministic
 * from a single test's point of view.
 */
public abstract class ActorPoolTest {

	// ================================================================
	// Messages
	// ================================================================

	/** Recorded by the receiving routee, then answered with a {@link Pong}. */
	private record Ping(int seq) implements Message {
	}

	/** Carries the path of the routee that actually handled the {@link Ping}. */
	private record Pong(String path, int seq) implements Message {
	}

	/**
	 * Blocks the receiving routee inside {@code onReceive} until the latch is
	 * released — the crude stand-in for the blocking I/O a pool exists to absorb.
	 */
	private record Block(CountDownLatch gate) implements Message {
	}

	/** Asks the actor to spawn a pool of children and report the reference. */
	private record SpawnPool(String name, SpawnOptions options) implements Message {
	}

	private record PoolSpawned(ActorRef pool) implements Message {
	}

	private record SpawnFailed(String exceptionType) implements Message {
	}

	private record ListChildren() implements Message {
	}

	private record Children(List<String> paths) implements Message {
	}

	protected abstract ActorSystem newActorSystem();

	private ActorSystem system;

	/**
	 * How many messages each routee path has received, across every pool a single
	 * test spawns. Keyed by path, so a routee's share is read back without asking
	 * it anything — an actor blocked on a {@link Block} could not answer anyway.
	 */
	private final Map<String, AtomicInteger> received = new ConcurrentHashMap<>();

	private final AtomicInteger totalReceived = new AtomicInteger();

	@BeforeEach
	void setUp() {
		system = newActorSystem();
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		system.shutdown();
		assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
	}

	// ================================================================
	// Identity: a pool is n ordinary actors, plus one reference over them
	// ================================================================

	/**
	 * The default, and {@code poolSize(1)} spelled out, must both stay exactly the
	 * single-actor spawn they always were: same path, same lookup, no {@code -0}
	 * suffix anywhere. Adding pools must not move a single existing actor.
	 */
	@Test
	void aPoolOfOneIsTheOrdinarySingleActorSpawn() throws Exception {
		ActorRef implicit = system.spawn("implicit", RouteeActor::new);
		ActorRef explicit = system.spawn("explicit", RouteeActor::new, SpawnOptions.pooled(1));

		assertEquals("/implicit", implicit.path());
		assertEquals("/explicit", explicit.path());
		assertEquals("explicit", explicit.name());
		assertTrue(system.findActor("/explicit").isPresent(), "a pool of one is registered under its own plain name");
		assertTrue(system.findActor("/explicit-0").isEmpty(), "and never under a suffixed one");

		assertEquals(new Pong("/explicit", 1), ask(explicit, new Ping(1)));
	}

	/**
	 * A pool of {@code n} is {@code n} ordinary actors at {@code name-0} …
	 * {@code name-(n-1)}, each resolvable on its own. The returned reference is a
	 * router, not an actor: it reports the pool's path, and nothing is registered
	 * there.
	 */
	@Test
	void aPoolIsNOrdinaryActorsAtTheirOwnPathsPlusARouterOverThem() {
		ActorRef pool = system.spawn("db", RouteeActor::new, SpawnOptions.pooled(3));

		assertEquals("db", pool.name());
		assertEquals("/db", pool.path());
		assertTrue(system.findActor("/db").isEmpty(), "the pool's own path holds no actor: the routees are the actors");

		for (int index = 0; index < 3; index++) {
			assertTrue(system.findActor("/db-" + index).isPresent(), "/db-" + index + " must be a live actor");
		}
		assertTrue(system.findActor("/db-3").isEmpty(), "a pool of 3 spawns exactly 3 actors");
	}

	/**
	 * A placement naming <em>one</em> carrier puts the whole pool there: the
	 * placement is applied per actor, and {@link Placement#carrier(int)} answers
	 * the same thing every time it is applied.
	 *
	 * <p>
	 * Only {@code carrier(0)} is exercised, the JDK builtin scheduler having
	 * exactly one carrier; the case that needs several — a pool spread by
	 * {@link Placement#roundRobin()} — is in {@code CarrierPlacementTest}.
	 */
	@Test
	void aPoolPlacedOnOneCarrierPutsEveryRouteeThere() {
		system.spawn("db", RouteeActor::new, SpawnOptions.pooled(4).withPlacement(Placement.carrier(0)));

		for (int index = 0; index < 4; index++) {
			assertEquals(0, system.homeCarrierIdOf(system.findActor("/db-" + index).orElseThrow()),
					"carrier(0) names one carrier, so /db-" + index + " must be on it");
		}
	}

	/**
	 * A pool reference has no home carrier to report — its placement was applied
	 * per actor, so a pool spawned with {@link Placement#roundRobin()} genuinely
	 * spans several carriers — and it says so rather than answering for one of
	 * them. The rejection does not depend on the placement that was used: a
	 * co-located pool is rejected exactly like a spread one, so the contract never
	 * turns on a placement the caller passed long ago.
	 */
	@Test
	void homeCarrierIdOfRejectsAPoolAndPointsAtItsActors() {
		ActorRef spread = system.spawn("db", RouteeActor::new, SpawnOptions.pooled(4));
		ActorRef together = system.spawn("io", RouteeActor::new,
				SpawnOptions.pooled(4).withPlacement(Placement.carrier(0)));

		assertThrows(IllegalArgumentException.class, () -> system.homeCarrierIdOf(spread));
		assertThrows(IllegalArgumentException.class, () -> system.homeCarrierIdOf(together),
				"a pool is rejected on being a pool, never on how its actors happen to be placed");

		assertEquals(0, system.homeCarrierIdOf(system.findActor("/io-0").orElseThrow()),
				"the actors it points at do answer");
	}

	// ================================================================
	// Routing
	// ================================================================

	/**
	 * The load balancing itself: {@code k * n} messages through a pool of {@code n}
	 * leave each routee with exactly {@code k}.
	 */
	@Test
	void aPoolSpreadsItsTellsOverEveryRouteeRoundRobin() throws Exception {
		ActorRef pool = system.spawn("worker", RouteeActor::new, SpawnOptions.pooled(4));

		for (int seq = 0; seq < 12; seq++) {
			pool.tell(new Ping(seq));
		}
		waitUntil(() -> totalReceived.get() == 12);

		for (int index = 0; index < 4; index++) {
			assertEquals(3, countFor("/worker-" + index), "each routee must get an equal share");
		}
	}

	/**
	 * An {@code ask} through a pool is answered <b>once</b>, by the single routee
	 * it was routed to — a pool is a load balancer, not a broadcast — and
	 * successive asks keep rotating.
	 */
	@Test
	void anAskIsAnsweredOnceByTheRouteeItWasRoutedTo() throws Exception {
		ActorRef pool = system.spawn("worker", RouteeActor::new, SpawnOptions.pooled(3));

		Set<String> answered = new HashSet<>();
		for (int seq = 0; seq < 6; seq++) {
			Pong pong = (Pong) ask(pool, new Ping(seq));
			assertEquals(seq, pong.seq());
			answered.add(pong.path());
		}

		assertEquals(Set.of("/worker-0", "/worker-1", "/worker-2"), answered);
		assertEquals(6, totalReceived.get(), "six asks are six messages, not six times the pool size");
	}

	/**
	 * The reason the feature exists: a routee blocking inside {@code onReceive}
	 * stalls its own mailbox and nothing else, so the pool keeps answering without
	 * the actor having had to offload that blocking work to
	 * {@link ActorContext#vThreadFactory()}.
	 *
	 * <p>
	 * The routing is deterministic here, which is what makes the assertion sharp: a
	 * fresh pool's cursor starts at {@code 0}, so the {@link Block} goes to
	 * {@code /io-0} and the {@link Ping} that must still be answered goes to
	 * {@code /io-1}.
	 */
	@Test
	void aRouteeBlockedOnIoDoesNotStallThePool() throws Exception {
		ActorRef pool = system.spawn("io", RouteeActor::new, SpawnOptions.pooled(2));
		CountDownLatch gate = new CountDownLatch(1);

		pool.tell(new Block(gate));
		waitUntil(() -> countFor("/io-0") == 1);

		try {
			Pong pong = (Pong) ask(pool, new Ping(1));
			assertEquals("/io-1", pong.path(), "the peer must answer while /io-0 is blocked");
		} finally {
			gate.countDown();
		}
	}

	/**
	 * A stopped routee is skipped rather than fed its {@code 1/n} share of the
	 * traffic — which, {@code tell} to a stopped actor being a silent drop, would
	 * otherwise be a steady, invisible leak of messages.
	 */
	@Test
	void aStoppedRouteeIsSkippedInsteadOfSwallowingItsShare() throws Exception {
		ActorRef pool = system.spawn("worker", RouteeActor::new, SpawnOptions.pooled(3));

		system.stop(system.findActor("/worker-1").orElseThrow());
		waitUntil(() -> system.findActor("/worker-1").isEmpty());

		for (int seq = 0; seq < 6; seq++) {
			pool.tell(new Ping(seq));
		}
		waitUntil(() -> totalReceived.get() == 6);

		assertEquals(0, countFor("/worker-1"));
		assertEquals(6, countFor("/worker-0") + countFor("/worker-2"),
				"no message may be dropped on the stopped routee");
	}

	// ================================================================
	// The child surface
	// ================================================================

	/**
	 * A pool spawned from an {@link ActorContext} is a pool of ordinary children:
	 * they appear in {@link ActorContext#children()}, at paths composed exactly as
	 * any child's, and — an unset placement still being {@link Placement#inherit()}
	 * — every one of them lands on the parent's own home carrier. The inheritance
	 * rule does not bend for pools.
	 */
	@Test
	void aChildPoolIsAPoolOfOrdinaryChildrenOnTheParentsCarrier() throws Exception {
		ActorRef parent = system.spawn("parent", RouteeActor::new);

		PoolSpawned spawned = (PoolSpawned) ask(parent, new SpawnPool("io", SpawnOptions.pooled(3)));
		ActorRef pool = spawned.pool();

		assertEquals("/parent/io", pool.path());
		assertEquals(List.of("/parent/io-0", "/parent/io-1", "/parent/io-2"),
				((Children) ask(parent, new ListChildren())).paths());

		for (int index = 0; index < 3; index++) {
			ActorRef routee = system.findActor("/parent/io-" + index).orElseThrow();
			assertEquals(system.homeCarrierIdOf(parent), system.homeCarrierIdOf(routee),
					"an unset placement is still inherit(), for a pool as for a single child");
		}

		Pong pong = (Pong) ask(pool, new Ping(7));
		assertEquals("/parent/io-0", pong.path());
	}

	/**
	 * Stopping the pool reference stops every routee — the one operation that has
	 * to understand that a pool reference stands for {@code n} actors rather than
	 * one — and stopping the parent stops them through the ordinary cascade,
	 * without the pool having to be mentioned at all.
	 */
	@Test
	void stoppingAPoolStopsEveryRouteeAndSoDoesTheParentsCascade() throws Exception {
		ActorRef rootPool = system.spawn("db", RouteeActor::new, SpawnOptions.pooled(3));
		system.stop(rootPool);
		for (int index = 0; index < 3; index++) {
			String path = "/db-" + index;
			waitUntil(() -> system.findActor(path).isEmpty());
		}

		ActorRef parent = system.spawn("parent", RouteeActor::new);
		ask(parent, new SpawnPool("io", SpawnOptions.pooled(3)));
		system.stop(parent);
		for (int index = 0; index < 3; index++) {
			String path = "/parent/io-" + index;
			waitUntil(() -> system.findActor(path).isEmpty());
		}
	}

	// ================================================================
	// Validation and failure
	// ================================================================

	/**
	 * A pool of zero — or of minus one — is a caller's mistake, and is rejected by
	 * {@link SpawnOptions} itself, before any system is involved.
	 */
	@Test
	void aPoolSizeBelowOneIsRejectedByTheOptions() {
		assertThrows(IllegalArgumentException.class, () -> SpawnOptions.pooled(0));
		assertThrows(IllegalArgumentException.class, () -> SpawnOptions.defaults().withPoolSize(-1));
	}

	/**
	 * A pool name is validated once, up front, on the name the caller actually
	 * passed — never on the derived {@code name-0}, which would quietly turn a
	 * {@code null} name into the perfectly valid actor {@code "null-0"}.
	 */
	@Test
	void aPoolValidatesTheNameItWasGivenNotTheOneItDerives() {
		assertThrows(IllegalArgumentException.class,
				() -> system.spawn(null, RouteeActor::new, SpawnOptions.pooled(3)));
		assertThrows(IllegalArgumentException.class, () -> system.spawn(" ", RouteeActor::new, SpawnOptions.pooled(3)));
		assertThrows(IllegalArgumentException.class,
				() -> system.spawn("a/b", RouteeActor::new, SpawnOptions.pooled(3)));

		assertTrue(system.findActor("/null-0").isEmpty(), "a rejected pool registers nothing at all");
	}

	/**
	 * A pool that collides partway through leaves nothing behind: the routees it
	 * had already created are stopped and unregistered, and the actor it collided
	 * with is untouched. Were they merely abandoned, they would hold the registry
	 * non-empty and {@code tearDown}'s {@code awaitTermination} would time out —
	 * which is the other half of what this test pins.
	 */
	@Test
	void aPoolThatCollidesPartwayLeavesNothingBehind() throws Exception {
		ActorRef squatter = system.spawn("db-2", RouteeActor::new);

		assertThrows(IllegalStateException.class, () -> system.spawn("db", RouteeActor::new, SpawnOptions.pooled(4)));

		waitUntil(() -> system.findActor("/db-0").isEmpty() && system.findActor("/db-1").isEmpty());
		assertTrue(system.findActor("/db-3").isEmpty(), "the routees after the collision were never created");
		assertTrue(system.findActor("/db-2").isPresent(), "the actor already at that path must survive the failure");
		assertEquals(new Pong("/db-2", 1), ask(squatter, new Ping(1)), "and must still be serving");
	}

	/**
	 * A pool is spawned from one factory, but never from one instance: each routee
	 * gets its own, exactly as two separately spawned actors would — otherwise they
	 * would share the private state an actor is defined by.
	 */
	@Test
	void everyRouteeGetsItsOwnActorInstance() throws Exception {
		AtomicInteger instances = new AtomicInteger();
		List<Integer> ids = new ArrayList<>();

		system.spawn("worker", () -> new RouteeActor(instances.getAndIncrement()), SpawnOptions.pooled(3));
		for (int index = 0; index < 3; index++) {
			Pong pong = (Pong) ask(system.findActor("/worker-" + index).orElseThrow(), new Ping(0));
			ids.add(pong.seq());
		}

		assertEquals(3, instances.get(), "the factory is called once per routee");
		assertNotEquals(ids.get(0), ids.get(1), "and each call must produce a distinct instance");
		assertEquals(List.of(0, 1, 2), ids);
	}

	// ================================================================
	// Helpers
	// ================================================================

	private Message ask(ActorRef ref, Message message) throws Exception {
		return ref.ask(message).get(10, TimeUnit.SECONDS);
	}

	private int countFor(String path) {
		AtomicInteger count = received.get(path);
		return count == null ? 0 : count.get();
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
	 * Records every message it receives under its own path — so a routee's share of
	 * the traffic is readable even while that routee is blocked — and answers a
	 * {@link Ping} with the path that handled it.
	 *
	 * <p>
	 * An instance carries an optional id, echoed back as the {@link Pong}'s
	 * {@code seq}, which is how {@link #everyRouteeGetsItsOwnActorInstance} tells
	 * three instances from one shared one.
	 */
	private final class RouteeActor implements Actor {

		private final int instanceId;

		private final boolean echoInstanceId;

		private RouteeActor() {
			this.instanceId = -1;
			this.echoInstanceId = false;
		}

		private RouteeActor(int instanceId) {
			this.instanceId = instanceId;
			this.echoInstanceId = true;
		}

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) throws Exception {
			record(context);
			switch (message) {
				case Ping ping ->
					replyTo(sender, new Pong(context.self().path(), echoInstanceId ? instanceId : ping.seq()), context);
				case Block block -> block.gate().await();
				case SpawnPool spawn -> sender.tell(trySpawn(spawn, context), context.self());
				case ListChildren ignored ->
					sender.tell(new Children(context.children().stream().map(ActorRef::path).toList()), context.self());
				default -> throw new IllegalStateException("unexpected message: " + message);
			}
		}

		/**
		 * Replies only when there is someone to reply to: half this suite's messages
		 * are plain {@code tell}s sent from the test's own thread, which carry no
		 * sender at all.
		 */
		private void replyTo(ActorRef sender, Message reply, ActorContext context) {
			if (sender != null) {
				sender.tell(reply, context.self());
			}
		}

		private void record(ActorContext context) {
			received.computeIfAbsent(context.self().path(), ignored -> new AtomicInteger()).incrementAndGet();
			totalReceived.incrementAndGet();
		}

		private Message trySpawn(SpawnPool spawn, ActorContext context) {
			try {
				return new PoolSpawned(context.spawn(spawn.name(), RouteeActor::new, spawn.options()));
			} catch (RuntimeException e) {
				return new SpawnFailed(e.getClass().getName());
			}
		}
	}
}
