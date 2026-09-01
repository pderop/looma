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

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.github.pderop.looma.impl.DefaultActorSystem;
import io.github.pderop.looma.spi.ActorScheduler;
import io.github.pderop.looma.spi.MailboxQueueFactory;

/**
 * The root of an actor hierarchy: spawns root actors, resolves paths, and
 * coordinates their shutdown.
 *
 * <p>
 * One implementation of this interface ships in this project, and
 * {@link #builder()} is the only way to reach it — a caller never names an
 * implementation type. It always runs the identical actor engine (spawn,
 * hierarchy, one virtual thread per actor, supervision, {@code ask}, graceful
 * stop). What varies is which {@code io.github.pderop.looma.spi.ActorScheduler}
 * supplies the factory that starts those loops — two shapes ship in this
 * project: a carrier-affine one, discovered through the SPI and backed by a
 * JVM-wide pool of carrier threads installed once per process via
 * {@code -Djdk.virtualThreadScheduler.implClass} and outliving any single
 * {@code ActorSystem}; or, when none is installed, the JDK builtin scheduler,
 * whose factories create plain virtual threads on the JDK's default virtual
 * thread scheduler. Both are directly comparable for exactly this reason; see
 * {@link #shutdown()} and {@link #awaitTermination(long, TimeUnit)} for the one
 * place the difference still shows up, at process teardown.
 */
public interface ActorSystem {

	/**
	 * Returns a builder for the actor system this project ships.
	 *
	 * <p>
	 * With nothing else set, {@link Builder#build()} takes the scheduler that
	 * {@code ServiceLoader} discovery finds, or the JDK builtin scheduler when it
	 * finds none — so the plain {@code ActorSystem.builder().build()} is the right
	 * call in application code, and which scheduler carries it is a deployment
	 * decision (JVM flags and classpath), not a code one.
	 */
	static Builder builder() {
		return DefaultActorSystem.newBuilder();
	}

	/**
	 * Fluent builder for an {@link ActorSystem}, obtained from
	 * {@link ActorSystem#builder()}.
	 *
	 * <p>
	 * {@link #scheduler(ActorScheduler)} bypasses discovery: setting it is a
	 * statement that this caller knows which scheduler it wants, which is exactly
	 * what a benchmark control or a test needs and exactly what an application
	 * should not do. {@link #mailboxQueueFactory} is orthogonal: it does not choose
	 * a scheduler, only the per-actor envelope queue.
	 */
	interface Builder {

		/**
		 * Sets the scheduler this system's placement comes from explicitly, bypassing
		 * discovery entirely. Used by tests that need to inject a specific scheduler.
		 */
		Builder scheduler(ActorScheduler scheduler);

		/**
		 * Sets the factory that creates one mailbox queue per actor. When unset, each
		 * actor gets a {@code ConcurrentLinkedQueue}. Not discovered through
		 * {@code ServiceLoader}.
		 */
		Builder mailboxQueueFactory(MailboxQueueFactory mailboxQueueFactory);

		/** Builds the system. */
		ActorSystem build();
	}

	/**
	 * Returns the {@link ActorScheduler} placing this system's work — the carrier
	 * count, the calling thread's carrier id, and the topology queries startup
	 * banners and pinning probes need.
	 *
	 * <p>
	 * On the contract rather than on an implementation type because the two
	 * consumers of it — an example's startup banner and the benchmark harness'
	 * "refuse to publish numbers from an unpinned fork" guard — must keep working
	 * whichever scheduler is installed, without naming one.
	 */
	ActorScheduler scheduler();

	/**
	 * Returns the id of the carrier {@code ref}'s actor calls home — the one its
	 * loop virtual thread and its {@link ActorContext#vThreadFactory()} threads are
	 * placed on. Always {@code 0} under a single-carrier scheduler such as the JDK
	 * builtin.
	 *
	 * <p>
	 * The home carrier is the only <em>deterministic</em> thing about an actor's
	 * placement: which carrier a task happens to run on may, under a scheduler that
	 * steals work, legitimately be a sibling carrier. An affinity assertion must be
	 * made against this, never against the running carrier.
	 */
	int homeCarrierIdOf(ActorRef ref);

	/**
	 * Returns the number of carriers {@link Placement#carrier(int)} is bounded by —
	 * the same value {@link #scheduler()}{@code .carrierCount()} reports.
	 */
	int carrierCount();

	/**
	 * Spawns a root actor, placed on a carrier chosen round-robin
	 * ({@link Placement#roundRobin()}, the default for this surface), using the
	 * actor's own {@link Actor#supervisorStrategy()}. Equivalent to
	 * {@link #spawn(String, Supplier, SpawnOptions) spawn(name, factory,
	 * SpawnOptions.defaults())}.
	 *
	 * @param name
	 *            the actor's simple name (also its full path, e.g. {@code "db"} for
	 *            {@code "/db"}); must be non-blank, contain no {@code '/'}, and be
	 *            unique among current root actors
	 * @param factory
	 *            creates the actor instance; also invoked again, by the engine, to
	 *            obtain a fresh instance after a {@link Directive#RESTART}
	 * @return a reference to the new actor
	 * @throws IllegalArgumentException
	 *             if the name is {@code null}, blank, or contains {@code '/'}
	 *             (which would forge a path)
	 * @throws IllegalStateException
	 *             if the name is already taken by a root actor, or this system is
	 *             shutting down or already shut down
	 */
	ActorRef spawn(String name, Supplier<Actor> factory);

	/**
	 * Equivalent to {@link #spawn(String, Supplier)}, but with an explicit
	 * {@link SpawnOptions}: an unset placement still defaults to
	 * {@link Placement#roundRobin()}, and an unset strategy still defaults to the
	 * actor's own {@link Actor#supervisorStrategy()}.
	 *
	 * <p>
	 * A root actor gets placement and supervision on the same terms a child does,
	 * without needing a parent context to be spawned from:
	 *
	 * <pre>{@code
	 * // one acceptor per carrier, each restarted rather than resumed on
	 * // failure
	 * for (int carrier = 0; carrier < system.carrierCount(); carrier++) {
	 * 	system.spawn("conn-" + carrier, ConnectionActor::new,
	 * 			SpawnOptions.placedOn(Placement.carrier(carrier)).withStrategy(cause -> Directive.RESTART));
	 * }
	 * }</pre>
	 *
	 * @param name
	 *            the actor's simple name (also its full path); must be non-blank,
	 *            contain no {@code '/'}, and be unique among current root actors
	 * @param factory
	 *            creates the actor instance; also invoked again, by the engine, to
	 *            obtain a fresh instance after a {@link Directive#RESTART}
	 * @param options
	 *            the placement and strategy to apply; never {@code null} — use
	 *            {@link SpawnOptions#defaults()} for "unset both". A placement of
	 *            {@link Placement#inherit()} is meaningless here, since a root has
	 *            no parent to inherit from, and throws
	 * @return a reference to the new actor
	 * @throws NullPointerException
	 *             if {@code options} is {@code null}
	 * @throws IllegalArgumentException
	 *             if the name is {@code null}, blank, or contains {@code '/'}, if
	 *             {@code options}' placement is {@link Placement#inherit()}, or if
	 *             it is a {@link Placement#carrier(int)} whose id is not less than
	 *             {@link #carrierCount()}
	 * @throws IllegalStateException
	 *             if the name is already taken by a root actor, or this system is
	 *             shutting down or already shut down
	 */
	ActorRef spawn(String name, Supplier<Actor> factory, SpawnOptions options);

	/**
	 * Looks up a live actor by its full path (e.g. {@code "/db/worker"}), or by a
	 * bare name for a root actor (e.g. {@code "db"} resolves {@code "/db"}).
	 *
	 * @return the actor at that path, or {@link Optional#empty()} if none is
	 *         currently live there
	 */
	Optional<ActorRef> findActor(String path);

	/**
	 * Stops an actor: runs the graceful cascading stop protocol on it and every
	 * descendant, then unregisters it. See {@link ActorContext#stop(ActorRef)} for
	 * the exact protocol.
	 *
	 * <p>
	 * This is the surface a plain thread stops an actor through: a {@code main}
	 * method or a test has no {@link ActorContext} of its own. Stopping this
	 * system's very last actor does not, by itself, shut the system down — only
	 * {@link #shutdown()} does that; see there.
	 */
	void stop(ActorRef ref);

	/**
	 * Begins a graceful, cascading shutdown of the whole system: every root actor
	 * is stopped, each mailbox drains, the cascade descends to every child, and
	 * {@code postStop} unwinds back up to the roots. Does not block; see
	 * {@link #awaitTermination(long, TimeUnit)} to wait for completion.
	 *
	 * <p>
	 * The system drains identically regardless of which scheduler is in use. Once
	 * the cascade has fully completed, the underlying {@code ActorScheduler} is
	 * released: the JDK builtin owns nothing to stop; a carrier-affine scheduler
	 * owns no pool to stop at all — its carrier threads are a permanent, JVM-wide
	 * pool shared by every virtual thread in the process.
	 *
	 * <p>
	 * Once shutdown has begun, {@link ActorRef#tell} to any actor in this system is
	 * silently dropped (dead-letter semantics) and {@link ActorRef#ask} fails
	 * immediately with {@link IllegalStateException}; a new {@link #spawn} throws
	 * {@link IllegalStateException} for the same reason.
	 */
	void shutdown();

	/**
	 * Blocks until this system has fully terminated after a call to
	 * {@link #shutdown()} — every actor unregistered and every unit of in-flight
	 * work finished — or the timeout elapses.
	 *
	 * <p>
	 * "In-flight work" means every actor loop virtual thread and every thread
	 * created through {@link ActorContext#vThreadFactory()} that has not yet
	 * finished, regardless of which scheduler is in use: this is what keeps a
	 * caller from observing termination while user-spawned blocking work is still
	 * running.
	 *
	 * @return {@code true} if the system terminated before the timeout elapsed
	 * @throws InterruptedException
	 *             if interrupted while waiting
	 */
	boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;
}
