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
package io.github.pderop.looma.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Placement;
import io.github.pderop.looma.SpawnOptions;
import io.github.pderop.looma.SupervisionStrategy;
import io.github.pderop.looma.spi.MailboxQueueFactory;

/**
 * The state one {@code ActorSystem} instance shares across every actor it
 * hosts: the path registry, the root-level children, the in-flight work
 * counter, the mailbox-queue factory, and the one-shot signal
 * {@code DefaultActorSystem} uses to release its own resources once every actor
 * has fully stopped.
 *
 * <p>
 * Everything in this class is scheduler-agnostic — it never runs a task itself,
 * only counts and bookkeeps around an {@link ActorCell}'s loop virtual thread
 * and the threads {@link #countedThreadFactory(ThreadFactory)} wraps. This is
 * what lets it stay identical regardless of which
 * {@code io.github.pderop.looma.spi.ActorScheduler} placed the actors it hosts:
 * a bug fixed here is fixed for every scheduler at once.
 */
final class ActorRuntime {

	/**
	 * Every live actor, keyed by its full path (e.g. {@code "/db"},
	 * {@code "/db/worker"}). Membership in this map is exactly what
	 * {@link #awaitTermination} and {@link #maybeFireAfterTermination()} watch: an
	 * actor is "live" for as long as, and only as long as, it is registered here.
	 */
	private final ConcurrentHashMap<String, ActorCell> registry = new ConcurrentHashMap<>();

	/**
	 * The current root-level actors — a subset of {@link #registry}'s values, kept
	 * separately because a system-wide shutdown starts by stopping exactly these,
	 * not their descendants directly.
	 */
	private final CopyOnWriteArrayList<ActorCell> roots = new CopyOnWriteArrayList<>();

	/**
	 * The seam to whoever owns the dispatcher array: {@link #dispatcherFor}
	 * resolves every {@link Placement} to a carrier id first — round-robin over
	 * whatever the underlying {@code ActorScheduler} exposes as its carriers (a
	 * single shared one for the JDK builtin scheduler, several for a carrier-affine
	 * scheduler), a specific carrier id, or the parent's own carrier id for
	 * {@link Placement#inherit()} — and only then asks this seam to turn that id
	 * into an actual {@link ActorDispatcher}. {@link Placement#inherit()} inherits
	 * its parent's <b>carrier</b> (the factory that starts the child's own loop),
	 * never a shared run loop: every actor has a loop of its own.
	 */
	private final DispatcherSource dispatchers;

	private final MailboxQueueFactory mailboxQueueFactory;

	/**
	 * Every unit of work currently spawned or running: an actor's loop virtual
	 * thread and every thread created through
	 * {@link #countedThreadFactory(ThreadFactory)} count once from the moment they
	 * are created (not started) until their body finishes. Reaching zero while
	 * {@link #shuttingDown} is set and {@link #registry} is empty is what
	 * {@link #awaitTermination} and {@link #afterTermination} both wait for.
	 */
	private final AtomicInteger inFlight = new AtomicInteger();

	/**
	 * Set once by {@link #beginShutdown()}. Every registration, send and
	 * {@code ask} rejects new work once this is set — but this class only exposes
	 * the flag; enforcing the rejection is each caller's own responsibility
	 * (Decision: this is best-effort against a concurrent {@code tell}, exactly
	 * like any other single flag guarding a race, never atomic with the check that
	 * raced it).
	 */
	private final AtomicBoolean shuttingDown = new AtomicBoolean();

	/**
	 * Guards {@link #afterTermination} so it runs at most once, even though
	 * {@link #maybeFireAfterTermination()} can be reached concurrently from several
	 * independent events (a registry removal and an in-flight decrement landing on
	 * zero at the same time, for instance).
	 */
	private final AtomicBoolean terminationFired = new AtomicBoolean();

	/**
	 * Runs exactly once, the moment {@link #shuttingDown} is set, {@link #registry}
	 * is empty and {@link #inFlight} is zero, all three at once.
	 * {@code DefaultActorSystem} uses it to release whatever private resources it
	 * owns (the underlying {@code ActorScheduler}) — resources this class never
	 * touches itself, since carrier threads in particular are not this class's to
	 * stop.
	 */
	private final Runnable afterTermination;

	/**
	 * @param dispatchers
	 *            the seam to whoever owns the dispatcher array, used to resolve
	 *            every {@link Placement} into an {@link ActorDispatcher}
	 * @param mailboxQueueFactory
	 *            creates one mailbox queue per spawned actor
	 * @param afterTermination
	 *            invoked exactly once, after {@link #beginShutdown()} has been
	 *            called and every actor and every in-flight unit of work has
	 *            finished
	 */
	public ActorRuntime(DispatcherSource dispatchers, MailboxQueueFactory mailboxQueueFactory,
			Runnable afterTermination) {
		this.dispatchers = dispatchers;
		this.mailboxQueueFactory = mailboxQueueFactory;
		this.afterTermination = afterTermination;
	}

	/**
	 * Returns the factory that creates one mailbox queue per actor, at spawn.
	 */
	MailboxQueueFactory mailboxQueueFactory() {
		return mailboxQueueFactory;
	}

	// ================================================================
	// Registry
	// ================================================================

	/**
	 * Registers {@code cell} under its own path, unless a live actor already
	 * occupies it.
	 *
	 * @return {@code true} if {@code cell} is now the live actor at its path,
	 *         {@code false} if a sibling already held that path
	 */
	public boolean register(ActorCell cell) {
		return registry.putIfAbsent(cell.path(), cell) == null;
	}

	/**
	 * Unregisters {@code cell}, then re-evaluates the termination condition — this
	 * is one of the events enumerated on {@link #maybeFireAfterTermination()}.
	 */
	public void unregister(ActorCell cell) {
		registry.remove(cell.path(), cell);
		maybeFireAfterTermination();
	}

	/**
	 * Resolves a path exactly as {@code ActorSystem.findActor} and
	 * {@code ActorContext.findActor} both document it: {@code path} is used as-is
	 * if it already starts with {@code '/'}, otherwise it is resolved as a root,
	 * e.g. {@code "worker"} resolves {@code "/worker"}.
	 */
	public Optional<ActorCell> find(String path) {
		String absolute = path.startsWith("/") ? path : "/" + path;
		return Optional.ofNullable(registry.get(absolute));
	}

	/**
	 * Adds {@code cell} to the root-level actor list.
	 */
	public void addRoot(ActorCell cell) {
		roots.add(cell);
	}

	/**
	 * Removes {@code cell} from the root-level actor list; a no-op if {@code cell}
	 * was never a root (e.g. it is a nested child).
	 */
	public void removeRoot(ActorCell cell) {
		roots.remove(cell);
	}

	/**
	 * Returns a point-in-time snapshot of the current root actors, e.g. for a
	 * system-wide shutdown to stop each of them.
	 */
	public List<ActorCell> roots() {
		return List.copyOf(roots);
	}

	/**
	 * Returns the number of distinct carriers a {@link Placement#carrier(int)} can
	 * be resolved against — {@code ActorSystem.carrierCount()} and
	 * {@code ActorContext.carrierCount()} both delegate here, so the two surfaces
	 * always agree.
	 */
	public int carrierCount() {
		return dispatchers.carrierCount();
	}

	/**
	 * Resolves a {@link Placement} into the {@link ActorDispatcher} the new cell
	 * will use — the single implementation both spawn paths
	 * ({@link #spawnRoot(String, Supplier, SpawnOptions)} and
	 * {@code ActorCell.spawn}) call through. Every placement resolves to a carrier
	 * <b>id</b> first, with an exhaustive pattern switch over {@link Placement}'s
	 * three cases, and only then is that id handed to
	 * {@link DispatcherSource#dispatcherFor(int)}:
	 *
	 * <ul>
	 * <li>{@link Placement.Inherit} — {@code parent.dispatcher().carrierId()}, or
	 * {@link IllegalArgumentException} if {@code parent} is {@code null} (a root
	 * has nothing to inherit from);
	 * <li>{@link Placement.RoundRobin} — {@link DispatcherSource#nextCarrierId()};
	 * <li>{@link Placement.Carrier} — bounds-checked against
	 * {@link #carrierCount()} (the lower bound is already guaranteed by the
	 * record's own compact constructor), or {@link IllegalArgumentException} naming
	 * both the requested id and the count.
	 * </ul>
	 *
	 * <p>
	 * Called before the new cell is created, so an invalid placement throws without
	 * having registered anything.
	 *
	 * @param placement
	 *            where the new cell should be placed
	 * @param parent
	 *            the parent cell for a child spawn, or {@code null} for a root
	 * @throws IllegalArgumentException
	 *             if {@code placement} is {@link Placement#inherit()} and
	 *             {@code parent} is {@code null}, or {@code placement} is a
	 *             {@link Placement#carrier(int)} whose id is not less than
	 *             {@link #carrierCount()}
	 */
	public ActorDispatcher dispatcherFor(Placement placement, ActorCell parent) {
		int carrierId = switch (placement) {
			case Placement.Inherit ignored -> {
				if (parent == null) {
					throw new IllegalArgumentException("Placement.inherit() has no parent to inherit from");
				}
				yield parent.dispatcher().carrierId();
			}
			case Placement.RoundRobin ignored -> dispatchers.nextCarrierId();
			case Placement.Carrier(int carrierIdRequested) -> {
				int count = carrierCount();
				if (carrierIdRequested >= count) {
					throw new IllegalArgumentException("carrierId " + carrierIdRequested
							+ " is out of range: this system has " + count + " carrier(s)");
				}
				yield carrierIdRequested;
			}
		};
		return dispatchers.dispatcherFor(carrierId);
	}

	/**
	 * Returns the id of the carrier {@code ref}'s actor calls home — the single
	 * implementation {@code DefaultActorSystem} and {@link ActorCell} both delegate
	 * to for {@code ActorSystem.homeCarrierIdOf} /
	 * {@code ActorContext.homeCarrierIdOf}.
	 *
	 * <p>
	 * No ownership check: a reference from another {@code ActorSystem} answers,
	 * rather than throwing, since carrier ids are process-global under a
	 * carrier-affine scheduler and every scheduler answers {@code 0} under the JDK
	 * builtin. This lets a caller compute
	 * {@code Placement.carrier(otherSystem.homeCarrierIdOf(ref))} to legitimately
	 * co-locate a new actor with one hosted by a different system.
	 */
	public int homeCarrierIdOf(ActorRef ref) {
		/*
		 * Not every ActorRef points at an actor with a home carrier: the sender an
		 * actor sees on an ask is a single-use PromiseRef backed by a future, not by a
		 * cell, and a caller may pass any implementation of the interface. Neither has
		 * a carrier to report, and the caller deserves to be told which of its
		 * arguments was wrong rather than a ClassCastException naming an internal type.
		 */
		if (ref instanceof PoolRef pool) {
			/*
			 * A pool has no home carrier of its own to report: its placement was resolved
			 * once per actor, so a pool spawned with roundRobin() genuinely spans several
			 * carriers. Answering for a co-located pool and throwing for a spread one would
			 * make the contract depend on a placement the caller has long since passed and
			 * this reference no longer carries, so a pool is always rejected — and pointed
			 * at the actors that do have an answer, each of them an ordinary actor at its
			 * own path.
			 */
			throw new IllegalArgumentException("a pool has no single home carrier: ask one of its actors, e.g. \""
					+ pool.cells().get(0).path() + "\"");
		}
		if (!(ref instanceof ActorRefImpl actorRef)) {
			throw new IllegalArgumentException(
					"homeCarrierIdOf requires a reference to a spawned actor, but got " + describe(ref));
		}
		return actorRef.cell().dispatcher().carrierId();
	}

	private static String describe(ActorRef ref) {
		if (ref == null) {
			return "null";
		}
		return ref.getClass().getSimpleName() + " (" + ref.path() + ")";
	}

	/**
	 * Spawns a root actor: resolves {@code options}' placement (defaulting to
	 * {@link Placement#roundRobin()}) through
	 * {@link #dispatcherFor(Placement, ActorCell)}, registers it, adds it to
	 * {@link #roots()}, and starts its loop virtual thread ({@code preStart} is the
	 * first envelope). {@code DefaultActorSystem}'s {@code spawn} methods are
	 * exactly this one call, and never implement any of it a second time.
	 *
	 * <p>
	 * A spawn that races {@link #beginShutdown()} closely enough to pass the
	 * shutting-down check does not throw: it returns a reference to an actor that
	 * has already been put into {@link ActorCell.State#TERMINATING} and will stop
	 * without ever processing a message. The alternative — leaving it running —
	 * would wedge the system permanently; see the comment at the re-check below.
	 *
	 * @param name
	 *            the actor's simple name, also its full path (e.g. {@code "db"} for
	 *            {@code "/db"})
	 * @param factory
	 *            creates the actor instance, called once here and again after every
	 *            restart
	 * @param options
	 *            the placement (defaulting to {@link Placement#roundRobin()}) and
	 *            strategy (defaulting to the instance's own
	 *            {@link Actor#supervisorStrategy()}) for the new actor
	 * @return a reference to the new root actor
	 * @throws IllegalStateException
	 *             if {@link #beginShutdown()} has already been called, or a root
	 *             actor already occupies that name
	 * @throws IllegalArgumentException
	 *             if {@code options}' placement is {@link Placement#inherit()}, or
	 *             a {@link Placement#carrier(int)} whose id is out of range
	 */
	public ActorRef spawnRoot(String name, Supplier<Actor> factory, SpawnOptions options) {
		if (shuttingDown.get()) {
			throw new IllegalStateException("cannot spawn \"" + name + "\": the system is shutting down");
		}
		Objects.requireNonNull(options, "options");
		return spawnGroup(name, factory, options, null, options.placement().orElse(Placement.roundRobin()));
	}

	/**
	 * Creates every actor one {@code spawn} call asks for — a single actor, or the
	 * {@link SpawnOptions#poolSize()} actors of a pool — registers them, starts
	 * them, and returns the one reference the caller gets back. Both spawning
	 * surfaces funnel here, having each applied their own default {@link Placement}
	 * first, so that "what a spawn does" exists once rather than once per surface.
	 *
	 * <p>
	 * <b>{@code placement} is resolved once per actor, not once for the pool</b> —
	 * a pool is exactly the same actors the caller would have got by spawning them
	 * one at a time with these very options, and nothing else. That is the whole
	 * rule, and it is what gives both shapes of pool for free, because the three
	 * placements answer it differently by their own definitions:
	 * {@link Placement#inherit()} and {@link Placement#carrier(int)} are constant —
	 * every actor of the pool lands on the one carrier they name — while
	 * {@link Placement#roundRobin()} means "the next carrier", so resolving it once
	 * per actor spreads the pool one carrier at a time, advancing the system-wide
	 * cursor {@code poolSize} times. A pool therefore never needs a placement of
	 * its own, and no option here decides between "together" and "spread": the
	 * placement already says which.
	 *
	 * <p>
	 * The actors of a pool are ordinary siblings named {@code name-0} …
	 * {@code name-(poolSize-1)}: same path composition, same registry, same
	 * children list, same cascade. A pool of one is not named or wrapped at all —
	 * it is the single actor it always was, returned as its own
	 * {@link ActorRefImpl}, so nothing about the pre-existing single-actor spawn
	 * changes shape.
	 *
	 * <p>
	 * A partially created pool is never left behind. If the {@code k}-th actor
	 * cannot be created — a name already taken, most plausibly, since the first
	 * {@code k} routees have just claimed paths of their own — the {@code k-1}
	 * already created are started and immediately stopped, so they run their own
	 * cascade and unregister, and the original failure is rethrown to the caller.
	 * Starting them first is load-bearing: a cell that is stopped without ever
	 * having been started owns no loop to run that cascade, and would sit in the
	 * registry forever, wedging {@code awaitTermination}.
	 *
	 * @param name
	 *            the pool's (or the single actor's) simple name, validated here
	 *            before any routee name is derived from it
	 * @param factory
	 *            creates each actor instance — called once per actor in the pool,
	 *            and again after each of their restarts
	 * @param options
	 *            the spawn's options; only {@link SpawnOptions#poolSize()} and
	 *            {@link SpawnOptions#strategy()} are read here — the placement is
	 *            passed separately, each surface having already substituted its own
	 *            default for an unset one
	 * @param parent
	 *            the parent cell for a child spawn, or {@code null} for roots
	 * @param placement
	 *            where to put each actor this call creates, resolved once per
	 *            actor; an invalid one throws on the first resolution, before
	 *            anything has been created
	 * @return the single actor's own reference when {@code poolSize} is {@code 1},
	 *         otherwise a {@link PoolRef} routing over the pool
	 */
	ActorRef spawnGroup(String name, Supplier<Actor> factory, SpawnOptions options, ActorCell parent,
			Placement placement) {
		int poolSize = options.poolSize();
		SupervisionStrategy strategy = options.strategy().orElse(null);
		// Before deriving name-0 … name-(n-1) from it: "null" + "-0" is a perfectly
		// valid actor name, and would let a null pool name through unnoticed.
		ActorCell.validateName(name);

		List<ActorCell> cells = new ArrayList<>(poolSize);
		try {
			for (int index = 0; index < poolSize; index++) {
				// Per actor, deliberately: this is the one line that makes a pool spawned with
				// roundRobin() spread over carriers and one spawned with inherit() stay whole.
				ActorDispatcher dispatcher = dispatcherFor(placement, parent);
				ActorCell cell = ActorCell.createCell(routeeName(name, index, poolSize), factory, strategy, parent,
						dispatcher, this);
				if (parent == null) {
					// In the roots list before it can run, never after: a preStart that fails stops
					// the cell, and that removal must not be able to precede this insertion. See
					// ActorCell.start(). (A child is already in its parent's children by now —
					// createCell put it there.)
					addRoot(cell);
				}
				cells.add(cell);
			}
		} catch (RuntimeException | Error failure) {
			cells.forEach(ActorRuntime::startThenStop);
			throw failure;
		}

		if (parent == null && shuttingDown.get()) {
			/*
			 * Best-effort closing of a narrow race, exactly as ActorCell.createCell does
			 * for a child racing its parent's stop: the guard at the top of spawnRoot
			 * already rejects the overwhelmingly common case, a system already shutting
			 * down when spawn is called, but a beginShutdown() landing in the handful of
			 * statements between that guard and the addRoot above would have taken its
			 * roots() snapshot without these brand-new cells in it. Nothing would ever stop
			 * them afterwards, and an unstoppable root is fatal to the whole system, not
			 * just to itself: it holds the registry non-empty forever, so awaitTermination
			 * can never return true and afterTermination — hence ActorScheduler.close() and
			 * the ask scheduler's shutdown — never runs. Stopping them here keeps them from
			 * being orphaned; it does not make the two operations atomic, only shrinks the
			 * window to almost nothing.
			 */
			cells.forEach(ActorCell::beginStop);
		}
		cells.forEach(ActorCell::start);

		if (poolSize == 1) {
			return cells.get(0).self();
		}
		return new PoolRef(name, poolPath(name, parent), cells);
	}

	/**
	 * Names the {@code index}-th actor of a pool. A pool of one keeps the name it
	 * was given, unsuffixed: a single-actor spawn must land at exactly the path it
	 * always did.
	 */
	private static String routeeName(String name, int index, int poolSize) {
		return poolSize == 1 ? name : name + "-" + index;
	}

	/**
	 * Composes the path a {@link PoolRef} reports, exactly as a single actor of
	 * that name would have been given — {@code "/db"} for a root pool, {@code
	 * "/app/db"} for one spawned under {@code /app}. No cell is registered under
	 * it: the live actors are the routees, one path each.
	 */
	private static String poolPath(String name, ActorCell parent) {
		return parent == null ? "/" + name : parent.path() + "/" + name;
	}

	/**
	 * Starts a cell only to stop it immediately — the cleanup a partially created
	 * pool needs. The start is what gives the cell the loop that runs its own stop
	 * cascade and finally unregisters it; {@code beginStop} alone on a
	 * never-started cell would leave it registered forever.
	 */
	private static void startThenStop(ActorCell cell) {
		cell.start();
		cell.beginStop();
	}

	/**
	 * Stops an actor: resolves {@code ref} back to the cell — or, for a pool, the
	 * cells — it stands for, and runs the graceful cascading stop protocol on each
	 * of them; see {@code ActorContext.stop(ActorRef)} for the exact protocol. A
	 * {@code ref} that resolves to no live cell at all (unknown, already fully
	 * stopped, or a single-use {@link PromiseRef}) is a silent no-op.
	 *
	 * <p>
	 * This is what {@code ActorSystem.stop(ActorRef)} delegates to, so that a plain
	 * thread with no {@code ActorContext} can stop an actor.
	 */
	public void stop(ActorRef ref) {
		ActorCell.cellsOf(ref).forEach(ActorCell::beginStop);
	}

	// ================================================================
	// In-flight accounting
	// ================================================================

	/**
	 * Wraps {@code raw} into the counting factory an {@code ActorContext} hands out
	 * as {@code vThreadFactory()} — and that {@code ActorCell.start()} uses for the
	 * actor loop itself: {@code newThread(r)} counts one unit of in-flight work
	 * from the moment the thread is created — not from when it starts running —
	 * until {@code r} finishes.
	 *
	 * <p>
	 * Two consequences worth stating plainly, since neither is enforced by this
	 * method and both surface only much later:
	 * <ul>
	 * <li>a thread that is created here but never {@code start()}ed pins the
	 * counter forever, which surfaces as {@code awaitTermination} timing out —
	 * never as data corruption, since nothing about the thread ever actually runs;
	 * <li>this wraps {@code r}, the {@link Runnable} the caller passed to
	 * {@code newThread}, and hands the wrapped runnable to
	 * {@code raw.newThread(...)} — the exact same factory the caller would
	 * otherwise have used directly. It never constructs a {@link Thread} itself and
	 * never substitutes a different factory, so whatever binding {@code raw} gives
	 * a thread (carrier affinity, in particular) is entirely preserved.
	 * </ul>
	 */
	public ThreadFactory countedThreadFactory(ThreadFactory raw) {
		return runnable -> {
			inFlight.incrementAndGet();
			return raw.newThread(() -> {
				try {
					runnable.run();
				} finally {
					workFinished();
				}
			});
		};
	}

	private void workFinished() {
		inFlight.decrementAndGet();
		maybeFireAfterTermination();
	}

	// ================================================================
	// Shutdown and termination
	// ================================================================

	/**
	 * Returns whether {@link #beginShutdown()} has been called.
	 */
	public boolean isShuttingDown() {
		return shuttingDown.get();
	}

	/**
	 * Marks this runtime as shutting down, stops every current root actor —
	 * cascading down to every descendant, exactly as {@code ActorContext.stop}
	 * would for each of them — and re-evaluates the termination condition.
	 *
	 * <p>
	 * This is the entirety of {@code ActorSystem.shutdown()}'s cascading-stop
	 * behaviour: a graceful, draining shutdown rather than an immediate one, the
	 * same regardless of which {@code ActorScheduler} placed the actors being
	 * stopped.
	 *
	 * <p>
	 * The final re-evaluation call is also what makes {@link #afterTermination}
	 * fire for a system with zero actors: every other re-evaluation point
	 * ({@link #unregister} and every in-flight decrement reaching zero) is a no-op
	 * unless this flag is already set, so a system that never hosted a single actor
	 * would otherwise never terminate at all.
	 */
	public void beginShutdown() {
		shuttingDown.set(true);
		roots().forEach(ActorCell::beginStop);
		maybeFireAfterTermination();
	}

	/**
	 * Fires {@link #afterTermination} exactly once, the first time all three of
	 * {@link #shuttingDown}, an empty {@link #registry} and a zero
	 * {@link #inFlight} are observed to hold at once.
	 *
	 * <p>
	 * The {@code shuttingDown} conjunct is load-bearing, not a simplification:
	 * without it, the registry emptying out on a perfectly healthy, still-running
	 * system — every actor stopped one at a time, none of them the last root, until
	 * none are left — would fire it too, tearing down the underlying
	 * {@code ActorScheduler} while the system still needs it for the next
	 * {@code spawn}.
	 */
	private void maybeFireAfterTermination() {
		if (shuttingDown.get() && registry.isEmpty() && inFlight.get() == 0
				&& terminationFired.compareAndSet(false, true)) {
			afterTermination.run();
		}
	}

	/**
	 * Blocks until {@link #registry} is empty and {@link #inFlight} is zero, or
	 * {@code timeout} elapses.
	 *
	 * <p>
	 * Correctness here rests entirely on {@link #registry} and {@link #inFlight}
	 * being kept faithful by every other operation on this class and by every
	 * {@link ActorCell}: this method itself does nothing but poll the two.
	 *
	 * @return {@code true} if both conditions held before the timeout elapsed
	 * @throws InterruptedException
	 *             if interrupted while waiting
	 */
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
		while (!registry.isEmpty() || inFlight.get() != 0) {
			if (Thread.interrupted()) {
				throw new InterruptedException();
			}
			long remainingNanos = deadlineNanos - System.nanoTime();
			if (remainingNanos <= 0) {
				return false;
			}
			LockSupport.parkNanos(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(1)));
		}
		return true;
	}
}
