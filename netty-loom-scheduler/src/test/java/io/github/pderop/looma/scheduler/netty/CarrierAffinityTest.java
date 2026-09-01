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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Message;
import io.github.pderop.looma.spi.ActorScheduler;

/**
 * The carrier-affinity properties specific to this implementation: an actor is
 * assigned one home carrier for life, the virtual threads it creates through
 * {@link ActorContext#vThreadFactory()} are pinned to that same carrier, and a
 * child spawned from its context inherits it.
 *
 * <p>
 * Two different notions of "carrier" appear below, and mixing them up is how
 * these assertions go flaky:
 * <ul>
 * <li>{@link ActorSystem#homeCarrierIdOf(ActorRef)} — the carrier an actor is
 * <em>placed</em> on. Deterministic, and the only sound basis for a
 * parent-versus-child assertion.
 * <li>{@link ActorScheduler#currentCarrierId()} — the carrier the running
 * virtual thread is <em>assigned</em> to, fixed when that thread was created.
 * Sound for "this thread was created by that actor's factory", which is what
 * the {@code vThreadFactory} case checks.
 * </ul>
 * Neither is {@code EventLoopScheduler.currentRunningScheduler()}, the carrier
 * a task happens to be executing on: under work stealing a queued task may
 * legitimately be picked up by a sibling carrier, so asserting on it would be
 * flaky by construction. See {@code EventLoopSchedulerWorkStealingTest} for the
 * proof that this really does happen.
 *
 * <p>
 * Both assertions above compare carrier <b>ids</b>, so they are written with
 * {@code assertEquals}/{@code assertNotEquals}, never with
 * {@code assertSame}/{@code assertNotSame}: an {@code int} accessor autoboxes,
 * and identity on {@link Integer} answers a question about the boxing cache
 * rather than about placement.
 *
 * <p>
 * This suite runs twice — {@code carrier-test} (two carriers, no stealing) and
 * {@code workstealing-test} (four carriers, stealing on) — and a failure under
 * either is a real failure.
 */
class CarrierAffinityTest {

	private record Ping() implements Message {
	}

	/**
	 * Asks the actor to spawn a child under itself and reply with its reference.
	 */
	private record SpawnChild(String name) implements Message {
	}

	private record Spawned(ActorRef child) implements Message {
	}

	/**
	 * Asks the actor to offload work onto a {@code vThreadFactory()} thread and
	 * reply from there with whether it landed on the same carrier.
	 */
	private record OffloadAndCompare() implements Message {
	}

	private record SameCarrier(boolean same) implements Message {
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

	@Test
	void anActorIsAssignedOneStableHomeCarrierForLife() throws Exception {
		AtomicReference<Integer> homeCarrierId = new AtomicReference<>();
		AtomicInteger mismatches = new AtomicInteger();
		// Captured here, not asserted inside onReceive: supervision swallows an
		// AssertionError thrown there, degenerating this into a 10-second timeout on
		// done.get() instead of a clean failure. Asserted below, on the JUnit thread.
		AtomicBoolean sawInvalidCarrierId = new AtomicBoolean();
		CompletableFuture<Void> done = new CompletableFuture<>();

		int count = 200;

		ActorRef ref = system.spawn("a", () -> new Actor() {

			private int seen;

			@Override
			public void onReceive(Message message, ActorRef sender, ActorContext context) {
				int id = system.scheduler().currentCarrierId();
				if (id == -1) {
					sawInvalidCarrierId.set(true);
				}

				Integer previous = homeCarrierId.compareAndExchange(null, id);
				if (previous != null && previous != id) {
					mismatches.incrementAndGet();
				}

				if (++seen == count) {
					done.complete(null);
				}
			}
		});

		for (int i = 0; i < count; i++) {
			ref.tell(new Ping());
		}

		done.get(10, TimeUnit.SECONDS);

		assertFalse(sawInvalidCarrierId.get(), "onReceive must run on a carrier-affine virtual thread");
		assertEquals(0, mismatches.get(), "every invocation must be assigned to the same home carrier");
	}

	@Test
	void aVThreadFactoryThreadIsAssignedToItsActorsCarrier() throws Exception {
		ActorRef ref = system.spawn("a", OffloadingActor::new);

		Message reply = ref.ask(new OffloadAndCompare()).get(10, TimeUnit.SECONDS);

		assertEquals(new SameCarrier(true), reply);
	}

	/**
	 * The second half of the affinity rule, and the reason a child's default
	 * placement, {@link io.github.pderop.looma.Placement#inherit()
	 * Placement.inherit()}, resolves to the parent's own dispatcher: a
	 * parent-to-child {@code tell} — and the child's reply back up — never pays a
	 * cross-carrier hop.
	 */
	@Test
	void aChildInheritsItsParentsHomeCarrier() throws Exception {
		ActorRef parent = system.spawn("parent", OffloadingActor::new);

		ActorRef child = parent.ask(new SpawnChild("child"), Spawned.class).get(10, TimeUnit.SECONDS).child();

		assertEquals(system.homeCarrierIdOf(parent), system.homeCarrierIdOf(child));
	}

	/**
	 * The round-robin cursor is one shared sequence for the whole system — a child
	 * asking for {@code Placement.roundRobin()} advances the very same counter a
	 * root spawn does, there is no second, per-parent sequence — so on a group of
	 * more than one carrier, two consecutive root spawns (each defaulting to
	 * {@code Placement.roundRobin()}) land on different carriers. That is what
	 * makes the inheritance assertion above meaningful rather than vacuous.
	 */
	@Test
	void consecutiveRootActorsAreSpreadOverTheGroup() {
		ActorRef first = system.spawn("first", OffloadingActor::new);
		ActorRef second = system.spawn("second", OffloadingActor::new);

		assertNotEquals(system.homeCarrierIdOf(first), system.homeCarrierIdOf(second),
				"two consecutive roots must be placed on two different carriers, round-robin");
	}

	// ================================================================
	// Actors
	// ================================================================

	/**
	 * Spawns children on request, and offloads work to a
	 * {@link ActorContext#vThreadFactory()} thread that reports back whether it
	 * landed on the same carrier as the {@code onReceive} that created it.
	 *
	 * <p>
	 * An inner class rather than a static nested one: the comparison below reads
	 * carrier ids through {@link ActorSystem#scheduler()}, which is an instance
	 * method of the system under test.
	 */
	private final class OffloadingActor implements Actor {

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) {
			switch (message) {
				case SpawnChild spawn ->
					sender.tell(new Spawned(context.spawn(spawn.name(), OffloadingActor::new)), context.self());
				case OffloadAndCompare ignored -> {
					ActorScheduler scheduler = system.scheduler();
					int onReceiveCarrierId = scheduler.currentCarrierId();
					ActorRef self = context.self();

					context.vThreadFactory()
							.newThread(() -> sender
									.tell(new SameCarrier(scheduler.currentCarrierId() == onReceiveCarrierId), self))
							.start();
				}
				default -> throw new IllegalStateException("unexpected message: " + message);
			}
		}
	}
}
