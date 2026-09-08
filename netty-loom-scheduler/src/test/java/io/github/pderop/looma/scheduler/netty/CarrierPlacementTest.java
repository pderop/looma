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
package io.github.pderop.looma.scheduler.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Message;
import io.github.pderop.looma.Placement;
import io.github.pderop.looma.SpawnOptions;

/**
 * The {@link Placement} assertions that need more than one carrier to be
 * meaningful, so they cannot live in the shared {@code SpawnPlacementTest}
 * suite — which the fallback scheduler also runs, and which has exactly one
 * carrier (see that class's javadoc). Standalone, like
 * {@link CarrierAffinityTest}, not a suite subclass.
 *
 * <p>
 * As in {@link CarrierAffinityTest}, every assertion below is made against
 * {@link ActorSystem#homeCarrierIdOf(ActorRef)} — an actor's deterministic
 * <em>placement</em> — or, for the one case that needs it,
 * {@code ActorScheduler.currentCarrierId()} — the carrier the running virtual
 * thread was <em>assigned</em> to when it was created. Neither is
 * {@code EventLoopScheduler.currentRunningScheduler()}, the carrier a queued
 * task happens to actually execute on: under work stealing (the
 * {@code workstealing-test} execution this suite also runs under) that can
 * legitimately be a sibling carrier, so asserting on it would be flaky by
 * construction.
 *
 * <p>
 * This suite runs twice — {@code carrier-test} (two carriers, no stealing) and
 * {@code workstealing-test} (four carriers, stealing on) — and a failure under
 * either is a real failure.
 */
class CarrierPlacementTest {

	/** Asks the actor to spawn a child under itself with the given placement. */
	private record SpawnChild(String name, SpawnOptions options) implements Message {
	}

	private record Spawned(ActorRef child) implements Message {
	}

	/**
	 * Asks the actor to reply with the id of the carrier its own {@code onReceive}
	 * is actually running on right now.
	 */
	private record ReportRunningCarrier() implements Message {
	}

	private record RunningCarrier(int carrierId) implements Message {
	}

	/**
	 * Asks the actor to spawn a child with the given placement, {@code tell} it a
	 * {@link Ping}, and report back once the child's reply completes the round
	 * trip.
	 */
	private record RoundTripViaChild(String childName, SpawnOptions options) implements Message {
	}

	private record Ping() implements Message {
	}

	private record Pong() implements Message {
	}

	private record RoundTripComplete(ActorRef child) implements Message {
	}

	private ActorSystem system;

	@BeforeEach
	void setUp() {
		system = ActorSystem.builder().build();
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		system.shutdown();
		assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
	}

	/**
	 * A child placed explicitly on carrier {@code k} has home carrier {@code k},
	 * whatever carrier its parent happens to be on — and spawning it must not
	 * itself move the parent.
	 */
	@Test
	void aChildPlacedOnAnExplicitCarrierHasThatHomeCarrierAndLeavesItsParentUnchanged() throws Exception {
		ActorRef parent = system.spawn("parent", Probe::new);
		int parentHomeCarrier = system.homeCarrierIdOf(parent);

		// A carrier deliberately different from the parent's own, so this assertion
		// cannot pass
		// by accident: a bug that silently inherited the parent's carrier instead of
		// honouring
		// the explicit placement would still fail it.
		int targetCarrier = Math.floorMod(parentHomeCarrier + 1, system.carrierCount());

		Spawned spawned = (Spawned) ask(parent,
				new SpawnChild("child", SpawnOptions.placedOn(Placement.carrier(targetCarrier))));

		assertEquals(targetCarrier, system.homeCarrierIdOf(spawned.child()));
		assertEquals(parentHomeCarrier, system.homeCarrierIdOf(parent), "spawning a child must not move its parent");
	}

	/**
	 * A child placed round-robin can land on a carrier other than its parent's —
	 * over {@code carrierCount()} such spawns, at least two distinct home carriers
	 * must appear. Never a <em>specific</em> one: the round-robin counter is shared
	 * with every other spawn in the process, so this is the only assertion that
	 * holds unconditionally.
	 */
	@Test
	void aChildPlacedRoundRobinCanLandOnACarrierOtherThanItsParents() throws Exception {
		ActorRef parent = system.spawn("parent", Probe::new);

		Set<Integer> homeCarriers = IntStream.range(0, system.carrierCount()).mapToObj(i -> {
			try {
				return (Spawned) ask(parent,
						new SpawnChild("child" + i, SpawnOptions.placedOn(Placement.roundRobin())));
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}).map(spawned -> system.homeCarrierIdOf(spawned.child())).collect(Collectors.toSet());

		assertTrue(homeCarriers.size() >= 2,
				"carrierCount() round-robin children must not all land on the same carrier, got " + homeCarriers);
	}

	/**
	 * Roots spawned with an explicit {@code carrier(i)} for every {@code i} in
	 * {@code [0, carrierCount())} occupy exactly those carriers, one each — the
	 * guarantee plain round-robin cannot give (two consecutive roots land on
	 * different carriers, per {@link CarrierAffinityTest}, but never a
	 * <em>specific</em> one), and the one {@code examples/http/Acceptor} needs to
	 * make its documented "connection n lands on carrier n % carriers" claim true.
	 */
	@Test
	void rootsSpawnedOnEachExplicitCarrierOccupyExactlyThoseCarriersOneEach() {
		List<ActorRef> roots = IntStream.range(0, system.carrierCount())
				.mapToObj(i -> system.spawn("root" + i, Probe::new, SpawnOptions.placedOn(Placement.carrier(i))))
				.toList();

		for (int i = 0; i < roots.size(); i++) {
			assertEquals(i, system.homeCarrierIdOf(roots.get(i)));
		}
	}

	/**
	 * A child placed on another carrier does not merely report that carrier as its
	 * home — it actually runs {@code onReceive} there, never on its parent's
	 * carrier. Probed the way {@code CarrierAffinityTest.OffloadingActor} probes
	 * its own carrier: through {@code ActorScheduler.currentCarrierId()}, which
	 * answers "what carrier was the running virtual thread assigned to", fixed when
	 * that thread was created — sound, unlike the carrier a task merely happens to
	 * execute on under stealing.
	 */
	@Test
	void aChildPlacedOnAnotherCarrierRunsOnReceiveOnItsOwnCarrierNotItsParents() throws Exception {
		ActorRef parent = system.spawn("parent", Probe::new);
		int parentHomeCarrier = system.homeCarrierIdOf(parent);
		int targetCarrier = Math.floorMod(parentHomeCarrier + 1, system.carrierCount());

		Spawned spawned = (Spawned) ask(parent,
				new SpawnChild("child", SpawnOptions.placedOn(Placement.carrier(targetCarrier))));
		ActorRef child = spawned.child();

		RunningCarrier running = (RunningCarrier) ask(child, new ReportRunningCarrier());

		assertEquals(targetCarrier, running.carrierId());
		assertNotEquals(parentHomeCarrier, running.carrierId());
	}

	/**
	 * A child placed on another carrier is still reachable from its parent through
	 * a plain {@code tell}, and can {@code tell} the parent back — the
	 * cross-carrier hop {@link Placement}'s javadoc documents as the cost of an
	 * explicit placement, exercised end to end rather than assumed. The round trip
	 * is driven entirely through {@link Ping}/{@link Pong} {@code tell}s between
	 * parent and child, never observed from the outside, because the only signal a
	 * test can safely act on is a reply the actor sends itself: an assertion made
	 * inside {@code onReceive} would be caught by supervision and never reach this
	 * method (see the class javadoc and {@code SpawnPlacementTest}'s convention).
	 * The placement checks below run only once the parent reports the trip
	 * complete, so they also pin that the reply actually made it back.
	 */
	@Test
	void aChildOnAnotherCarrierStillReachesItsParentThroughTell() throws Exception {
		ActorRef parent = system.spawn("parent", Probe::new);
		int parentHomeCarrier = system.homeCarrierIdOf(parent);
		int targetCarrier = Math.floorMod(parentHomeCarrier + 1, system.carrierCount());

		RoundTripComplete complete = (RoundTripComplete) ask(parent,
				new RoundTripViaChild("child", SpawnOptions.placedOn(Placement.carrier(targetCarrier))));

		assertEquals(targetCarrier, system.homeCarrierIdOf(complete.child()));
		assertEquals(parentHomeCarrier, system.homeCarrierIdOf(parent), "the round trip must not move the parent");
	}

	/**
	 * A pool applies its placement <b>once per actor</b>, so the default
	 * {@link Placement#roundRobin()} of {@link ActorSystem#spawn} — "the next
	 * carrier" — spreads a root pool one actor per carrier. With
	 * {@code carrierCount()} actors, that is every carrier exactly once: this is
	 * the assertion that a pool advances the system-wide cursor {@code poolSize}
	 * times rather than once, and it needs more than one carrier to say anything at
	 * all.
	 */
	@Test
	void aRootPoolSpreadsOneActorPerCarrier() {
		int carriers = system.carrierCount();

		system.spawn("pool", Probe::new, SpawnOptions.pooled(carriers));

		Set<Integer> homeCarriers = IntStream.range(0, carriers)
				.mapToObj(index -> system.homeCarrierIdOf(system.findActor("/pool-" + index).orElseThrow()))
				.collect(Collectors.toSet());

		assertEquals(carriers, homeCarriers.size(),
				"a root pool of carrierCount() actors must occupy every carrier exactly once");
	}

	/**
	 * The same rule on the child surface, in both directions — and this is the pair
	 * that makes the rule worth having. An unset placement is
	 * {@link Placement#inherit()}, which names one carrier however many times it is
	 * applied, so a child pool stays whole on its parent's carrier; an explicit
	 * {@link Placement#roundRobin()} spreads that same pool one child per carrier.
	 * Neither needs an option of its own: the placement already says which.
	 */
	@Test
	void aChildPoolInheritsByDefaultAndSpreadsWhenPlacedRoundRobin() throws Exception {
		int carriers = system.carrierCount();
		ActorRef parent = system.spawn("parent", Probe::new);
		int parentHomeCarrier = system.homeCarrierIdOf(parent);

		ask(parent, new SpawnChild("kept", SpawnOptions.pooled(carriers)));
		for (int index = 0; index < carriers; index++) {
			assertEquals(parentHomeCarrier,
					system.homeCarrierIdOf(system.findActor("/parent/kept-" + index).orElseThrow()),
					"an unset placement is inherit(), which does not bend for pools");
		}

		ask(parent, new SpawnChild("spread", SpawnOptions.pooled(carriers).withPlacement(Placement.roundRobin())));
		Set<Integer> spreadCarriers = IntStream.range(0, carriers)
				.mapToObj(index -> system.homeCarrierIdOf(system.findActor("/parent/spread-" + index).orElseThrow()))
				.collect(Collectors.toSet());

		assertEquals(carriers, spreadCarriers.size(),
				"roundRobin() means the next carrier, so applying it per child spreads the pool");
	}

	// ================================================================
	// Helpers
	// ================================================================

	private Message ask(ActorRef ref, Message message) throws Exception {
		return ref.ask(message).get(10, TimeUnit.SECONDS);
	}

	// ================================================================
	// Actors
	// ================================================================

	/**
	 * Spawns children on request and reports the carrier its own {@code onReceive}
	 * is running on. An inner class, not a static nested one: it reads
	 * {@link ActorSystem#scheduler()} through this test's own {@code system} field,
	 * exactly like {@link CarrierAffinityTest.OffloadingActor}.
	 */
	private final class Probe implements Actor {

		/**
		 * The asker of the in-flight {@link RoundTripViaChild}, kept until the child's
		 * {@link Pong} closes the loop. Safe unstashed: a mailbox processes one message
		 * at a time, so at most one round trip is ever in flight per actor.
		 */
		private ActorRef roundTripAsker;

		private ActorRef roundTripChild;

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) {
			switch (message) {
				case SpawnChild spawn ->
					sender.tell(new Spawned(context.spawn(spawn.name(), Probe::new, spawn.options())), context.self());
				case ReportRunningCarrier ignored ->
					sender.tell(new RunningCarrier(system.scheduler().currentCarrierId()), context.self());
				case RoundTripViaChild roundTrip -> {
					roundTripAsker = sender;
					roundTripChild = context.spawn(roundTrip.childName(), Probe::new, roundTrip.options());
					roundTripChild.tell(new Ping(), context.self());
				}
				case Ping ignored -> sender.tell(new Pong(), context.self());
				case Pong ignored -> roundTripAsker.tell(new RoundTripComplete(roundTripChild), context.self());
				default -> throw new IllegalStateException("unexpected message: " + message);
			}
		}
	}
}
