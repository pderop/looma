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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

/**
 * The view an actor has of itself, its children, and the system it belongs to.
 *
 * <p>
 * Every actor has a home carrier thread, assigned once when it is spawned and
 * fixed for its lifetime. By <b>default</b>, a root actor gets one round-robin
 * (from {@link ActorSystem#spawn}) and a child spawned through {@link #spawn}
 * inherits its parent's home carrier, so a parent-to-child {@code tell} pays no
 * cross-carrier hop. {@link SpawnOptions#placedOn(Placement)} overrides either
 * default without changing anything else about the child: its place in the
 * cascading stop, its path, and its place among its parent's children are
 * exactly the same whichever carrier it lands on. {@link Actor#onReceive} — and
 * every lifecycle hook — always runs on this actor's own loop virtual thread,
 * started from that home carrier's factory.
 *
 * <p>
 * How that scheduling is actually implemented — and in particular whether a
 * home carrier is a real, pinned resource or just a conceptual grouping — is
 * implementation-defined; see {@link #vThreadFactory()} for the exact
 * per-implementation guarantee.
 *
 * <p>
 * An {@code ActorContext} is thread-safe and long-lived: it may legitimately be
 * used from a thread spawned through {@link #vThreadFactory()} (for instance,
 * to {@code self().tell(...)} a result back once blocking work completes), not
 * only from inside {@link Actor#onReceive} itself.
 *
 * <p>
 * This interface is deliberately not an {@link java.util.concurrent.Executor}
 * and takes no {@code Runnable} directly: {@link #vThreadFactory()} is the sole
 * mechanism for moving blocking work off an actor's own loop, because it is
 * also the mechanism that carries the actor's carrier affinity to that work —
 * see there for exactly what it guarantees.
 */
public interface ActorContext {

	/**
	 * Returns this actor's own reference — the same reference a lookup of its
	 * {@link ActorRef#path()} would return.
	 */
	ActorRef self();

	/**
	 * Looks up a live actor by path.
	 *
	 * <p>
	 * <b>Surprising, so stated plainly:</b> the path is resolved exactly as
	 * {@link ActorSystem#findActor(String)} resolves it, <em>not</em> relative to
	 * {@link #self()}. A worker at {@code "/db/worker"} calling
	 * {@code context.findActor("sibling")} resolves the root path
	 * {@code "/sibling"}, not {@code "/db/sibling"} — even though, intuitively, a
	 * context "belongs" to {@code /db}. This is deliberate (one resolution rule
	 * everywhere, rather than one for the system and a different one for every
	 * context), not an oversight; {@code ActorHierarchyTest} pins exactly this
	 * behaviour. A path with no {@code '/'} is resolved as a root, e.g.
	 * {@code findActor("worker")} resolves {@code "/worker"}.
	 *
	 * @return the actor at that path, or {@link Optional#empty()} if none is
	 *         currently live there
	 */
	Optional<ActorRef> findActor(String path);

	/**
	 * Returns a snapshot of this actor's current children.
	 *
	 * <p>
	 * The returned list is immutable and never reflects later spawns or stops: it
	 * is a point-in-time copy, never a live view.
	 */
	List<ActorRef> children();

	/**
	 * Spawns a child actor under this one, placed on this actor's own home carrier
	 * ({@link Placement#inherit()}), using the child's own
	 * {@link Actor#supervisorStrategy()}. Equivalent to
	 * {@link #spawn(String, Supplier, SpawnOptions) spawn(name, factory,
	 * SpawnOptions.defaults())}.
	 *
	 * @param name
	 *            the child's simple name; must be non-blank and contain no
	 *            {@code '/'} (which would forge a path), and unique among this
	 *            actor's current children
	 * @param factory
	 *            creates the actor instance; also invoked again, by the engine, to
	 *            obtain a fresh instance after a {@link Directive#RESTART}
	 * @return a reference to the new child, usable immediately (its
	 *         {@link Actor#preStart} is guaranteed to run before its first message,
	 *         not necessarily before this method returns)
	 * @throws IllegalArgumentException
	 *             if the name is {@code null}, blank, or contains {@code '/'}
	 *             (which would forge a path)
	 * @throws IllegalStateException
	 *             if a sibling already has that name, or this actor is itself
	 *             stopping or stopped (spawning under a terminating parent would
	 *             make its own stop condition — "mailbox drained, children known
	 *             and themselves stopped" — unreachable)
	 */
	ActorRef spawn(String name, Supplier<Actor> factory);

	/**
	 * Equivalent to {@link #spawn(String, Supplier)}, but with an explicit
	 * {@link SpawnOptions}: an unset placement still defaults to
	 * {@link Placement#inherit()}, and an unset strategy still defaults to the
	 * child's own {@link Actor#supervisorStrategy()}.
	 *
	 * <p>
	 * Every optional spawn parameter goes through {@link SpawnOptions} rather than
	 * through an overload per combination, so that adding one costs a method there
	 * instead of an overload on every spawning surface:
	 *
	 * <pre>{@code
	 * // a child on a carrier of its own, restarted rather than resumed on
	 * // failure
	 * ActorRef worker = context.spawn("worker", Worker::new,
	 * 		SpawnOptions.placedOn(Placement.roundRobin()).withStrategy(cause -> Directive.RESTART));
	 * }</pre>
	 *
	 * <p>
	 * <b>A {@link SpawnOptions#poolSize()} above {@code 1} spawns a pool of
	 * children</b>: that many actors from the same {@code factory}, named
	 * {@code name-0} … {@code name-(poolSize-1)}, and one returned {@link ActorRef}
	 * spreading every {@code tell} and {@code ask} over them round-robin. Each has
	 * a mailbox and a loop of its own, so a child that blocks on I/O inside
	 * {@code onReceive} stalls only its own mailbox and its {@code poolSize - 1}
	 * peers keep serving — a pool is the way to absorb blocking work without
	 * offloading it to {@link #vThreadFactory()}. Ordering, in exchange, is per
	 * child and no longer pool-wide.
	 *
	 * <pre>{@code
	 * // four children on this actor's own carrier, load-balanced behind one
	 * // reference
	 * ActorRef io = context.spawn("io", BlockingIoActor::new, SpawnOptions.pooled(4));
	 * io.tell(new Fetch(url), context.self());
	 *
	 * // the same four, spread one per carrier
	 * ActorRef io = context.spawn("io", BlockingIoActor::new,
	 * 		SpawnOptions.pooled(4).withPlacement(Placement.roundRobin()));
	 * }</pre>
	 *
	 * <p>
	 * Each is an ordinary child: it appears in {@link #children()}, resolves at its
	 * own path, is supervised on its own terms, and is stopped by this actor's
	 * cascade — {@link #stop(ActorRef)} on the returned reference stops all of
	 * them. That reference is a router, not an actor: it reports the pool's path
	 * ({@code "/parent/io"}), {@link #findActor(String)} finds nothing there — the
	 * live actors being {@code "/parent/io-0"} onwards — and
	 * {@link #homeCarrierIdOf(ActorRef)} rejects it, a pool having no single home
	 * carrier of its own.
	 *
	 * <p>
	 * <b>The placement applies to each child of the pool</b>, which is to say a
	 * pool is exactly the children this same call would have produced one at a
	 * time. An unset placement is therefore still {@link Placement#inherit()} and
	 * puts every child of the pool on this actor's own home carrier — the
	 * inheritance rule does not bend for pools — while
	 * {@link Placement#roundRobin()}, meaning "the next carrier", spreads the pool
	 * one child per carrier. Nothing else is needed to choose between the two: the
	 * placement already says which.
	 *
	 * @param name
	 *            the child's simple name; must be non-blank and contain no
	 *            {@code '/'} (which would forge a path), and unique among this
	 *            actor's current children
	 * @param factory
	 *            creates the actor instance; also invoked again, by the engine, to
	 *            obtain a fresh instance after a {@link Directive#RESTART}
	 * @param options
	 *            the placement, strategy and pool size to apply; never {@code null}
	 *            — use {@link SpawnOptions#defaults()} for "all unset"
	 * @return a reference to the new child, usable immediately, or, when
	 *         {@code options}' {@link SpawnOptions#poolSize()} is above {@code 1},
	 *         one reference routing over the pool of children
	 * @throws NullPointerException
	 *             if {@code options} is {@code null}
	 * @throws IllegalArgumentException
	 *             if the name is {@code null}, blank, or contains {@code '/'}, or
	 *             {@code options}' placement is a {@link Placement#carrier(int)}
	 *             whose id is not less than {@link #carrierCount()}
	 * @throws IllegalStateException
	 *             if a sibling already has that name — or, for a pool, any of the
	 *             {@code poolSize} names it derives — or this actor is itself
	 *             stopping or stopped. A pool that fails partway leaves nothing
	 *             behind: the children it had already created are stopped before
	 *             this throws
	 */
	ActorRef spawn(String name, Supplier<Actor> factory, SpawnOptions options);

	/**
	 * Stops an actor: runs the graceful cascading stop protocol on it (drain its
	 * mailbox, then stop and drain every descendant, innermost first, then run
	 * {@code postStop} on the way back up) and unregisters it once the cascade
	 * completes.
	 *
	 * <p>
	 * {@code ref} may be <em>any</em> live actor, not only a direct or indirect
	 * child of this one: there is one uniform rule and no ownership check. Stopping
	 * an already-stopped or unknown reference is a silent no-op.
	 *
	 * <p>
	 * A pool reference stops every actor of the pool, each through this very same
	 * cascade — one uniform rule there too, and nothing for the caller to unpack.
	 */
	void stop(ActorRef ref);

	/**
	 * Returns the number of carriers {@link Placement#carrier(int)} is bounded by —
	 * the same value {@code ActorSystem.carrierCount()} and
	 * {@code scheduler().carrierCount()} report.
	 *
	 * <p>
	 * This is what makes {@code Placement.carrier(int)} usable from inside an actor
	 * without reaching for the underlying
	 * {@code io.github.pderop.looma.spi.ActorScheduler}, which this interface
	 * deliberately does not expose (see this interface's class javadoc).
	 */
	int carrierCount();

	/**
	 * Returns the id of the carrier {@code ref}'s actor calls home — the one its
	 * loop virtual thread and its {@link #vThreadFactory()} threads are placed on.
	 * Always {@code 0} under a single-carrier scheduler such as the JDK builtin.
	 *
	 * <p>
	 * The home carrier is the only <em>deterministic</em> thing about an actor's
	 * placement: which carrier a task happens to run on may, under a scheduler that
	 * steals work, legitimately be a sibling carrier. An affinity assertion must be
	 * made against this, never against the running carrier.
	 *
	 * <p>
	 * This is what lets a caller compute
	 * {@code SpawnOptions.placedOn(Placement.carrier(ctx.homeCarrierIdOf(other)))}
	 * to co-locate a new actor with an existing one.
	 *
	 * @throws IllegalArgumentException
	 *             if {@code ref} does not stand for exactly one spawned actor —
	 *             either the single-use promise reference an actor sees as
	 *             {@code sender} on an {@code ask}, which is backed by a future
	 *             rather than a cell, or a pool reference, which stands for
	 *             {@link SpawnOptions#poolSize()} actors and points the caller at
	 *             them instead
	 */
	int homeCarrierIdOf(ActorRef ref);

	/**
	 * Returns a factory for creating extra virtual threads on behalf of this actor:
	 * this is where blocking work goes, e.g.
	 * {@code ctx.vThreadFactory().newThread(blockingWork).start()}.
	 *
	 * <p>
	 * The affinity guarantee depends on which
	 * {@code io.github.pderop.looma.spi.ActorScheduler} placed this actor: a
	 * carrier-affine scheduler pins a thread created here to this actor's home
	 * carrier, so it shares its locality and can complete work back into the actor
	 * (e.g. {@code self().tell(result, self())}) without a cross-carrier hop; the
	 * JDK builtin scheduler hands out a plain, unpinned virtual thread on the JDK's
	 * default scheduler instead. Either way, blocking directly inside
	 * {@link Actor#onReceive} instead of offloading here occupies this actor's own
	 * loop and stalls its own mailbox — that never changes. Neighbours have loops
	 * of their own.
	 *
	 * <p>
	 * The engine counts every thread created through this factory as in-flight
	 * work, so that {@link ActorSystem#awaitTermination} does not report
	 * termination while such a thread is still running. Two consequences to note: a
	 * thread that is created but never {@code start()}ed pins that counter forever
	 * (a caller bug — it surfaces as {@code awaitTermination} timing out, not as
	 * corruption), and this counting is a wrapper around the underlying factory —
	 * it never changes which carrier (if any) the thread is bound to.
	 */
	ThreadFactory vThreadFactory();
}
