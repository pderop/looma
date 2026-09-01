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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link Placement} and {@link SpawnOptions}: the properties every
 * {@link ActorSystem} implementation shares, and therefore the only ones
 * asserted here.
 *
 * <p>
 * <b>This engine self-supervises</b> ({@code SupervisionTest} pins the
 * deviation): nothing below asserts that a parent supervises a child, only that
 * an explicit {@link SpawnOptions#supervisedBy(SupervisionStrategy)} overrides
 * the spawned actor's own {@link Actor#supervisorStrategy()}.
 *
 * <p>
 * <b>Only {@code Placement.carrier(0)} is exercised here</b>, never
 * {@code carrier(1)} or higher: the JDK builtin scheduler has
 * {@code carrierCount() == 1}, so a higher id would legitimately throw under
 * one scheduler and succeed under the other. Every assertion that needs more
 * than one carrier lives in {@code CarrierPlacementTest} instead, which runs
 * only under the carrier-affine scheduler.
 *
 * <p>
 * An exception thrown inside {@code onReceive} never reaches this class:
 * {@code ActorCell.runMessage} catches it and hands it to supervision (the
 * default {@link Directive#RESUME}, just logged), so a plain
 * {@code assertThrows} wrapped around a {@code tell}/{@code ask} would see
 * nothing happen. Every assertion that needs to observe a thrown exception is
 * therefore made by the actor itself, from inside {@code onReceive}: it catches
 * the exception, and replies with its class name for the test to assert on —
 * exactly the way {@code CarrierAffinityTest.OffloadingActor} replies with its
 * own measurement instead of asserting from inside the actor.
 */
public abstract class SpawnPlacementTest {

	// ================================================================
	// Messages
	// ================================================================

	/** Asks the actor to spawn a child through the unchanged 2-arg overload. */
	private record SpawnChildPlain(String name, Supplier<Actor> factory) implements Message {
	}

	/**
	 * Asks the actor to spawn a child through the {@link SpawnOptions} overload.
	 */
	private record SpawnChild(String name, Supplier<Actor> factory, SpawnOptions options) implements Message {
	}

	private record ChildSpawned(ActorRef child) implements Message {
	}

	private record SpawnFailed(String exceptionType) implements Message {
	}

	/**
	 * Asks the actor to reply with a snapshot of its
	 * {@link ActorContext#children()} paths.
	 */
	private record ListChildren() implements Message {
	}

	private record Children(List<String> paths) implements Message {
	}

	/**
	 * Asks the actor to report its own home carrier id and {@code carrierCount()}.
	 */
	private record SelfCarrier() implements Message {
	}

	private record SelfCarrierReport(int homeCarrierId, int carrierCount) implements Message {
	}

	/**
	 * Asks the actor to call {@code context.homeCarrierIdOf(sender)} on the very
	 * {@code sender} it was handed — which, delivered through {@code ask}, is a
	 * single-use promise reference, not a spawned actor.
	 */
	private record CarrierOfSender() implements Message {
	}

	private record CarrierOfReport(int carrierId) implements Message {
	}

	private record CarrierOfFailed(String exceptionType) implements Message {
	}

	/**
	 * Asks the actor to echo back the {@code sender} it was handed, so the test can
	 * call {@code ActorSystem.homeCarrierIdOf} on it directly, from this class's
	 * own thread rather than from inside {@code onReceive}.
	 */
	private record EchoSender() implements Message {
	}

	private record SenderEcho(ActorRef sender) implements Message {
	}

	/**
	 * Asks the actor to reply with its own {@link ActorContext}, so the test can
	 * keep using it (per that interface's class javadoc, which allows use from any
	 * thread, not only from inside {@code onReceive}) after the actor itself has
	 * stopped accepting messages.
	 */
	private record CaptureContext() implements Message {
	}

	private record ContextCaptured(ActorContext context) implements Message {
	}

	private record Bump() implements Message {
	}

	private record Boom() implements Message {
	}

	private record Report() implements Message {
	}

	private record Snapshot(int instance, int counter) implements Message {
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

	// ================================================================
	// Tests
	// ================================================================

	/**
	 * The 2-arg {@code spawn(name, factory)}, on both surfaces: a root lands
	 * wherever round-robin puts it, and a child inherits its parent's home carrier.
	 * These are the defaults, and adding {@link SpawnOptions} must not change them.
	 */
	@Test
	void plainTwoArgSpawnIsUnchangedOnBothSurfaces() throws Exception {
		ActorRef parent = system.spawn("parent", ProbeActor::new);

		ChildSpawned spawned = (ChildSpawned) ask(parent, new SpawnChildPlain("child", ProbeActor::new));
		ActorRef child = spawned.child();

		assertEquals("/parent/child", child.path());
		assertEquals(system.homeCarrierIdOf(parent), system.homeCarrierIdOf(child),
				"a child spawned through the unchanged 2-arg overload still inherits its parent's home carrier");
	}

	/**
	 * A strategy passed at {@code ActorSystem.spawn} time wins over the root
	 * actor's own {@link Actor#supervisorStrategy()} — the behaviour the removed
	 * {@code spawn(name, factory, strategy)} overload used to give, which
	 * {@link SpawnOptions#supervisedBy(SupervisionStrategy)} must not lose.
	 */
	@Test
	void supervisedByOverridesTheActorsOwnStrategyForARoot() throws Exception {
		AtomicInteger instances = new AtomicInteger();
		ActorRef ref = system.spawn("counter", () -> new CounterActor(instances),
				SpawnOptions.supervisedBy(cause -> Directive.RESUME));

		ref.tell(new Bump());
		ref.tell(new Boom()); // the actor's own strategy says RESTART; the spawn-time RESUME must win
		ref.tell(new Bump());

		assertEquals(new Snapshot(0, 2), report(ref), "RESUME must keep the same instance and its accumulated state");
	}

	/**
	 * Same override, for a child spawned through {@code ActorContext.spawn}.
	 */
	@Test
	void supervisedByOverridesTheActorsOwnStrategyForAChild() throws Exception {
		ActorRef parent = system.spawn("parent", ProbeActor::new);
		AtomicInteger instances = new AtomicInteger();

		ChildSpawned spawned = (ChildSpawned) ask(parent, new SpawnChild("counter", () -> new CounterActor(instances),
				SpawnOptions.supervisedBy(cause -> Directive.RESUME)));
		ActorRef child = spawned.child();

		child.tell(new Bump());
		child.tell(new Boom());
		child.tell(new Bump());

		assertEquals(new Snapshot(0, 2), report(child));
	}

	/**
	 * The override still applies when a placement is set in the very same
	 * {@link SpawnOptions} — the two fields are independent, and setting one must
	 * not silently drop the other.
	 */
	@Test
	void supervisedByStillAppliesWhenAPlacementIsAlsoSetOnTheSameOptions() throws Exception {
		ActorRef parent = system.spawn("parent", ProbeActor::new);
		AtomicInteger instances = new AtomicInteger();

		SpawnOptions options = SpawnOptions.placedOn(Placement.carrier(0)).withStrategy(cause -> Directive.RESUME);
		ChildSpawned spawned = (ChildSpawned) ask(parent,
				new SpawnChild("counter", () -> new CounterActor(instances), options));
		ActorRef child = spawned.child();

		child.tell(new Bump());
		child.tell(new Boom());

		assertEquals(new Snapshot(0, 1), report(child), "the strategy must still be honoured");
		assertEquals(0, system.homeCarrierIdOf(child), "the placement in the same SpawnOptions must also be honoured");
	}

	/**
	 * {@code carrier(0)} is always a valid placement, root and child, regardless of
	 * how many carriers the underlying scheduler actually has — the JDK builtin has
	 * exactly one, and a carrier-affine scheduler always has at least one.
	 */
	@Test
	void carrierZeroIsAlwaysAValidPlacementForARootAndAChild() throws Exception {
		ActorRef root = system.spawn("root0", ProbeActor::new, SpawnOptions.placedOn(Placement.carrier(0)));
		assertEquals(0, system.homeCarrierIdOf(root));

		ActorRef parent = system.spawn("parent", ProbeActor::new);
		ChildSpawned spawned = (ChildSpawned) ask(parent,
				new SpawnChild("child0", ProbeActor::new, SpawnOptions.placedOn(Placement.carrier(0))));

		assertEquals(0, system.homeCarrierIdOf(spawned.child()));
	}

	/**
	 * The two ways a {@link Placement#carrier(int)} can be invalid, and where each
	 * is caught: a negative id never reaches a system at all — it is rejected by
	 * the record's own compact constructor — while an id that is merely too big for
	 * <em>this</em> system can only be known at spawn time, once
	 * {@code carrierCount()} is known.
	 */
	@Test
	void carrierPlacementValidatesItsBounds() {
		assertThrows(IllegalArgumentException.class, () -> Placement.carrier(-1),
				"a negative carrier id is rejected by Placement.Carrier's own compact constructor, before any system exists");

		assertThrows(IllegalArgumentException.class,
				() -> system.spawn("tooFar", ProbeActor::new,
						SpawnOptions.placedOn(Placement.carrier(system.carrierCount()))),
				"valid carrier ids are [0, carrierCount()), so carrierCount() itself is already out of range");

		assertTrue(system.findActor("tooFar").isEmpty(), "a rejected placement registers nothing");
	}

	/**
	 * {@link Placement#inherit()} is meaningless on {@link ActorSystem#spawn}: a
	 * root has no parent to inherit a carrier from.
	 */
	@Test
	void placementInheritOnARootThrows() {
		assertThrows(IllegalArgumentException.class,
				() -> system.spawn("root", ProbeActor::new, SpawnOptions.placedOn(Placement.inherit())));
	}

	/**
	 * The point of the whole plan: a child placed away from its parent's carrier is
	 * still, in every other respect, an ordinary child. It shows up in
	 * {@link ActorContext#children()}, its path is composed exactly as before, a
	 * duplicate sibling name is rejected whatever placement either spawn used
	 * (placement never participates in identity), and stopping the parent still
	 * stops it through the ordinary cascade.
	 */
	@Test
	void aChildPlacedOnANonDefaultCarrierIsStillAnOrdinaryChild() throws Exception {
		ActorRef parent = system.spawn("parent", ProbeActor::new);

		ChildSpawned spawned = (ChildSpawned) ask(parent,
				new SpawnChild("child", ProbeActor::new, SpawnOptions.placedOn(Placement.carrier(0))));
		ActorRef child = spawned.child();

		assertEquals("/parent/child", child.path());
		assertEquals(List.of("/parent/child"), ((Children) ask(parent, new ListChildren())).paths());

		// A different placement on the duplicate-name attempt proves the rejection is
		// about
		// the name, not about a repeated placement value.
		Message duplicate = ask(parent,
				new SpawnChild("child", ProbeActor::new, SpawnOptions.placedOn(Placement.roundRobin())));
		assertEquals(new SpawnFailed(IllegalStateException.class.getName()), duplicate,
				"a duplicate sibling name is rejected whatever placement either spawn used");

		system.stop(parent);
		waitUntil(() -> system.findActor(parent.path()).isEmpty());
		waitUntil(() -> system.findActor(child.path()).isEmpty());
	}

	/**
	 * Two exceptions could apply to a spawn under a stopping parent with an invalid
	 * placement — the parent's own {@link IllegalStateException} and the
	 * placement's {@link IllegalArgumentException} — and only one can actually be
	 * thrown. This pins which: the placement is resolved, and therefore rejected,
	 * before {@code createCell}'s stopping-parent check ever runs.
	 *
	 * <p>
	 * The context is captured before the parent starts stopping, and used
	 * afterwards from this test's own thread — legitimate per
	 * {@link ActorContext}'s class javadoc, and the only way to still reach a
	 * parent that no longer accepts {@code tell}/{@code ask} once it is
	 * terminating.
	 */
	@Test
	void spawningUnderAStoppingParentWithAnInvalidPlacementThrowsIllegalArgumentException() throws Exception {
		ActorRef parent = system.spawn("parent", ProbeActor::new);
		ActorContext parentContext = ((ContextCaptured) ask(parent, new CaptureContext())).context();

		system.stop(parent);

		assertThrows(IllegalArgumentException.class, () -> parentContext.spawn("tooLate", ProbeActor::new,
				SpawnOptions.placedOn(Placement.carrier(system.carrierCount()))));
	}

	/**
	 * {@code spawn(name, factory, null)} throws {@link NullPointerException}, root
	 * and child: "unset" is {@link SpawnOptions#defaults()}, never {@code null}.
	 */
	@Test
	void nullSpawnOptionsThrowsNullPointerException() throws Exception {
		assertThrows(NullPointerException.class, () -> system.spawn("root", ProbeActor::new, null));

		ActorRef parent = system.spawn("parent", ProbeActor::new);
		Message reply = ask(parent, new SpawnChild("child", ProbeActor::new, null));

		assertEquals(new SpawnFailed(NullPointerException.class.getName()), reply);
	}

	/**
	 * A cell keeps its dispatcher after {@code beginStop()}, so
	 * {@code homeCarrierIdOf} on an already-stopped actor must not throw — pinned
	 * here so nobody adds a liveness check to it later.
	 */
	@Test
	void homeCarrierIdOfAStoppedActorStillReportsItsCarrier() throws Exception {
		ActorRef ref = system.spawn("temp", ProbeActor::new);
		int homeCarrierId = system.homeCarrierIdOf(ref);

		system.stop(ref);
		waitUntil(() -> system.findActor(ref.path()).isEmpty());

		assertEquals(homeCarrierId, system.homeCarrierIdOf(ref));
	}

	/**
	 * {@code carrierCount()} must agree everywhere it is exposed: on
	 * {@link ActorContext}, on {@link ActorSystem}, and on the
	 * {@code ActorScheduler} both delegate to.
	 */
	@Test
	void carrierCountAgreesAcrossContextSystemAndScheduler() throws Exception {
		ActorRef ref = system.spawn("probe", ProbeActor::new);
		SelfCarrierReport report = (SelfCarrierReport) ask(ref, new SelfCarrier());

		assertEquals(system.carrierCount(), report.carrierCount());
		assertEquals(system.scheduler().carrierCount(), report.carrierCount());
	}

	/**
	 * {@code ctx.homeCarrierIdOf(ctx.self())}, read from inside the actor, must
	 * agree with {@code system.homeCarrierIdOf(ref)}, read from outside it.
	 */
	@Test
	void contextHomeCarrierIdOfSelfAgreesWithTheSystem() throws Exception {
		ActorRef ref = system.spawn("probe", ProbeActor::new);
		SelfCarrierReport report = (SelfCarrierReport) ask(ref, new SelfCarrier());

		assertEquals(system.homeCarrierIdOf(ref), report.homeCarrierId());
	}

	/**
	 * The {@code sender} an actor sees on a message delivered through {@code ask}
	 * is a single-use promise, backed by a future rather than by a cell —
	 * {@code ActorContext.homeCarrierIdOf} must reject it with
	 * {@link IllegalArgumentException}, never a {@link ClassCastException} naming
	 * the internal promise type.
	 */
	@Test
	void homeCarrierIdOfRejectsTheAskPromiseRefFromTheContext() throws Exception {
		ActorRef ref = system.spawn("probe", ProbeActor::new);

		Message reply = ask(ref, new CarrierOfSender());

		assertEquals(new CarrierOfFailed(IllegalArgumentException.class.getName()), reply);
	}

	/**
	 * Same rejection, on {@code ActorSystem.homeCarrierIdOf} instead — the promise
	 * reference is echoed back by the actor so this test can call the system
	 * surface directly, from its own thread.
	 */
	@Test
	void homeCarrierIdOfRejectsTheAskPromiseRefFromTheSystem() throws Exception {
		ActorRef ref = system.spawn("probe", ProbeActor::new);

		SenderEcho echo = (SenderEcho) ask(ref, new EchoSender());

		assertThrows(IllegalArgumentException.class, () -> system.homeCarrierIdOf(echo.sender()));
	}

	// ================================================================
	// Helpers
	// ================================================================

	private Message ask(ActorRef ref, Message message) throws Exception {
		return ref.ask(message).get(10, TimeUnit.SECONDS);
	}

	private Snapshot report(ActorRef ref) throws Exception {
		return (Snapshot) ask(ref, new Report());
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
	 * Answers every placement/hierarchy question this suite asks, from inside its
	 * own {@link ActorContext}. Every spawn attempt is caught and reported back as
	 * a {@link SpawnFailed} rather than left to propagate: propagating it would
	 * hand it to this actor's supervision (the default {@link Directive#RESUME}),
	 * and the {@code ask} that triggered it would then leave the future open.
	 */
	private static final class ProbeActor implements Actor {

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) {
			switch (message) {
				case SpawnChildPlain spawn -> sender.tell(trySpawnPlain(spawn, context), context.self());
				case SpawnChild spawn -> sender.tell(trySpawn(spawn, context), context.self());
				case ListChildren ignored ->
					sender.tell(new Children(context.children().stream().map(ActorRef::path).toList()), context.self());
				case SelfCarrier ignored ->
					sender.tell(new SelfCarrierReport(context.homeCarrierIdOf(context.self()), context.carrierCount()),
							context.self());
				case CarrierOfSender ignored -> sender.tell(tryHomeCarrierOf(sender, context), context.self());
				case EchoSender ignored -> sender.tell(new SenderEcho(sender), context.self());
				case CaptureContext ignored -> sender.tell(new ContextCaptured(context), context.self());
				default -> throw new IllegalStateException("unexpected message: " + message);
			}
		}

		private Message trySpawnPlain(SpawnChildPlain spawn, ActorContext context) {
			try {
				return new ChildSpawned(context.spawn(spawn.name(), spawn.factory()));
			} catch (RuntimeException e) {
				return new SpawnFailed(e.getClass().getName());
			}
		}

		private Message trySpawn(SpawnChild spawn, ActorContext context) {
			try {
				return new ChildSpawned(context.spawn(spawn.name(), spawn.factory(), spawn.options()));
			} catch (RuntimeException e) {
				return new SpawnFailed(e.getClass().getName());
			}
		}

		private Message tryHomeCarrierOf(ActorRef ref, ActorContext context) {
			try {
				return new CarrierOfReport(context.homeCarrierIdOf(ref));
			} catch (RuntimeException e) {
				return new CarrierOfFailed(e.getClass().getName());
			}
		}
	}

	/**
	 * Counts its {@link Bump}s, fails on {@link Boom}, and reports its instance id
	 * together with its counter — so a {@link Directive#RESTART} shows up as a new
	 * instance id with a reset counter, and a {@link Directive#RESUME} shows up as
	 * the same instance id with the counter kept.
	 *
	 * <p>
	 * Its own {@link #supervisorStrategy()} always says {@link Directive#RESTART};
	 * every test using it spawns it with an explicit
	 * {@link SpawnOptions#supervisedBy(SupervisionStrategy)} of
	 * {@link Directive#RESUME}, so restarting is exactly what must <em>not</em>
	 * happen if the override is honoured.
	 */
	private static final class CounterActor implements Actor {

		private final int instance;

		private int counter;

		CounterActor(AtomicInteger instances) {
			this.instance = instances.getAndIncrement();
		}

		@Override
		public SupervisionStrategy supervisorStrategy() {
			return cause -> Directive.RESTART;
		}

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) {
			switch (message) {
				case Bump ignored -> counter++;
				case Boom ignored -> throw new IllegalStateException("boom");
				case Report ignored -> sender.tell(new Snapshot(instance, counter), context.self());
				default -> throw new IllegalStateException("unexpected message: " + message);
			}
		}
	}
}
