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

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Directive;
import io.github.pderop.looma.Message;
import io.github.pderop.looma.Placement;
import io.github.pderop.looma.SpawnOptions;
import io.github.pderop.looma.SupervisionStrategy;
import io.github.pderop.looma.spi.MailboxQueue;

/**
 * One actor's whole state: its POJO instance, its mailbox, its loop virtual
 * thread, its place in the hierarchy, and the {@link ActorContext} it sees
 * itself through — this class implements {@link ActorContext} directly, since
 * every piece of state that interface exposes ({@link #self()}, its children,
 * its dispatcher) is already state this class owns.
 *
 * <p>
 * Each actor is one virtual thread for life, started from its dispatcher's
 * factory at {@link #start()}. {@code tell} offers an envelope and
 * {@link LockSupport#unpark unpark}s that thread. The loop drains until the
 * mailbox is empty, then parks. Serialization is the virtual thread itself:
 * there is no 0/1 scheduling gate and no per-message task. Cooperative
 * {@link Thread#yield()} every {@value #YIELD_EVERY} drained envelopes keeps a
 * CPU-bound mailbox from holding a carrier indefinitely — virtual threads are
 * not time-sliced.
 *
 * <p>
 * Every lifecycle transition — the first {@code preStart}, a restart's
 * before/after hooks, the final {@code postStop} — is threaded through this
 * exact same mailbox as a {@link Envelope#lifecycle(Runnable) lifecycle
 * envelope}: this is what guarantees {@code preStart} always completes before
 * the first message and that a graceful stop can never run concurrently with a
 * message being delivered.
 */
final class ActorCell implements ActorContext {

	/**
	 * How many consecutive drained envelopes this loop tolerates before calling
	 * {@link Thread#yield()}. Virtual threads are not preemptible, so a mailbox
	 * that never empties would otherwise hold its platform carrier. Parking on an
	 * empty mailbox is itself a yield point, so the counter resets there.
	 */
	private static final int YIELD_EVERY = 8;

	/**
	 * A cell's life-cycle state, transitioned exclusively by compare-and-swap so
	 * that two concurrent observers of the same transition (e.g. the task that just
	 * finished draining the mailbox, and a stop request arriving at the same
	 * instant) never both act on it.
	 */
	enum State {
		/**
		 * Created and registered, but {@code preStart} has not yet run. Messages sent
		 * during this window queue normally; they are simply guaranteed to be delivered
		 * after {@code preStart} completes, since both share the same mailbox and loop.
		 */
		STARTING,

		/**
		 * Draining its mailbox normally.
		 */
		RUNNING,

		/**
		 * Winding down: no longer accepting new messages, but still delivering whatever
		 * lifecycle envelopes and already-queued messages remain.
		 */
		TERMINATING,

		/**
		 * Fully stopped and unregistered. The loop exits once it observes this.
		 */
		STOPPED
	}

	private final String name;

	private final String path;

	private final ActorCell parent;

	private final CopyOnWriteArrayList<ActorCell> children = new CopyOnWriteArrayList<>();

	private final Supplier<Actor> factory;

	/**
	 * Mutable on purpose: a restart replaces this with a fresh instance obtained
	 * from {@link #factory}, while every other field on this cell (its path, its
	 * mailbox, its loop thread) stays exactly as it was.
	 */
	private volatile Actor actor;

	/**
	 * The strategy explicitly supplied at spawn time, or {@code null} if none was —
	 * in which case the effective strategy is the actor instance's own
	 * {@link Actor#supervisorStrategy()}, re-read from each fresh instance after a
	 * restart rather than cached once.
	 */
	private final SupervisionStrategy explicitStrategy;

	private final MailboxQueue<Envelope> mailbox;

	private final AtomicReference<State> state = new AtomicReference<>(State.STARTING);

	/**
	 * Guards the one-time transition from "draining finished" to actually running
	 * the stop cascade (stopping every child, then scheduling {@code postStop}).
	 * More than one caller can observe "{@link State#TERMINATING}, mailbox empty"
	 * for the same cell — the loop that just finished the last message, and a
	 * completion callback chained from a sibling event, for instance — and only the
	 * first one through this compare-and-set may proceed; a second run would stop
	 * every child a second time and run {@code postStop} twice.
	 */
	private final AtomicBoolean cascadeStarted = new AtomicBoolean();

	/**
	 * Completes once this cell has fully stopped: its mailbox drained, every
	 * descendant itself fully stopped, and its own {@code postStop} run.
	 */
	private final CompletableFuture<Void> terminated = new CompletableFuture<>();

	private final ActorDispatcher dispatcher;

	private final ActorRuntime runtime;

	private final ActorRefImpl self;

	/**
	 * This actor's loop virtual thread. Published before {@link Thread#start()},
	 * and read by {@link #enqueue} to unpark. {@code null} only in the window
	 * between construction and {@link #start()} — a {@code tell} in that window
	 * still offers, and the loop sees the envelope when it first drains.
	 */
	private volatile Thread loopThread;

	private static final Logger LOG = System.getLogger(ActorCell.class.getName());

	/**
	 * @param name
	 *            this actor's simple name
	 * @param path
	 *            this actor's full, slash-separated path
	 * @param parent
	 *            the parent cell, or {@code null} for a root actor
	 * @param factory
	 *            creates the actor instance, called once here and again after every
	 *            restart
	 * @param explicitStrategy
	 *            the strategy supplied at spawn time, or {@code null} to use the
	 *            instance's own
	 * @param dispatcher
	 *            where this cell's loop and extra virtual threads are placed
	 * @param runtime
	 *            the shared engine state this cell's actor system was built with
	 */
	public ActorCell(String name, String path, ActorCell parent, Supplier<Actor> factory,
			SupervisionStrategy explicitStrategy, ActorDispatcher dispatcher, ActorRuntime runtime) {
		this.name = name;
		this.path = path;
		this.parent = parent;
		this.factory = factory;
		this.explicitStrategy = explicitStrategy;
		this.dispatcher = dispatcher;
		this.runtime = runtime;
		this.mailbox = runtime.mailboxQueueFactory().create();
		this.actor = factory.get();
		this.self = new ActorRefImpl(this);
	}

	// ================================================================
	// Identity and hierarchy accessors
	// ================================================================

	public String name() {
		return name;
	}

	public String path() {
		return path;
	}

	public ActorCell parent() {
		return parent;
	}

	/**
	 * Returns this cell's dispatcher — where its loop and the threads from
	 * {@link #vThreadFactory()} come from. {@link ActorRuntime}'s
	 * {@code homeCarrierIdOf} reaches it through {@link ActorRefImpl#cell()} to
	 * resolve an actor's home carrier. A child spawned with
	 * {@link Placement#inherit()} inherits this cell's
	 * {@link ActorDispatcher#carrierId() carrier id}, not this dispatcher instance
	 * — every actor has its own loop; inherit is home-carrier only.
	 */
	public ActorDispatcher dispatcher() {
		return dispatcher;
	}

	public State state() {
		return state.get();
	}

	public CompletableFuture<Void> terminated() {
		return terminated;
	}

	/**
	 * Returns the shared engine state this cell's actor system was built with — the
	 * accessor {@link ActorRefImpl#ask} needs to reach the path registry's shutdown
	 * flag.
	 */
	ActorRuntime runtime() {
		return runtime;
	}

	/**
	 * Returns whether this cell is currently {@link State#TERMINATING} or already
	 * {@link State#STOPPED} — the one check both a spawn under this cell and an
	 * {@code ask} of this cell need to make.
	 */
	boolean isTerminatingOrStopped() {
		return isTerminatingOrStopped(state.get());
	}

	private static boolean isTerminatingOrStopped(State candidate) {
		return candidate == State.TERMINATING || candidate == State.STOPPED;
	}

	/**
	 * Resolves every {@link ActorCell} a plain {@link ActorRef} stands for: the one
	 * cell behind a single actor's reference, every routee behind a
	 * {@link PoolRef}, or nothing at all for a reference that owns no cell (a
	 * single-use {@link PromiseRef}, or an unknown implementation).
	 *
	 * <p>
	 * This is what makes {@code stop} uniform across the two kinds of reference a
	 * {@code spawn} can hand back: stopping a pool is stopping each of its actors,
	 * each through the very same cascade a single actor goes through, and neither
	 * caller has to know which kind it holds.
	 */
	static List<ActorCell> cellsOf(ActorRef ref) {
		if (ref instanceof ActorRefImpl impl) {
			return List.of(impl.cell());
		}
		if (ref instanceof PoolRef pool) {
			return pool.cells();
		}
		return List.of();
	}

	/**
	 * Adds {@code child} to this cell's live children. Package-visible: only the
	 * engine code that creates a child cell in the first place calls this,
	 * immediately after constructing it.
	 */
	void addChild(ActorCell child) {
		children.add(child);
	}

	/**
	 * Removes {@code child} from this cell's live children, e.g. once it has itself
	 * fully stopped.
	 */
	void removeChild(ActorCell child) {
		children.remove(child);
	}

	// ================================================================
	// Spawn
	// ================================================================

	/**
	 * Creates and registers a new cell — a root actor when {@code parent} is
	 * {@code null}, a child otherwise — and leaves it not yet running: the caller
	 * finishes registering it and then calls {@link #start()}, which enqueues
	 * {@code preStart} and starts the loop virtual thread, so {@code preStart} is
	 * guaranteed to run before this cell's first message even if no message is ever
	 * sent.
	 *
	 * @param name
	 *            the new cell's simple name; must be non-blank and contain no
	 *            {@code '/'}
	 * @param factory
	 *            creates the actor instance, called once here and again after every
	 *            restart
	 * @param explicitStrategy
	 *            the strategy supplied at spawn time, or {@code null} to use the
	 *            instance's own {@link Actor#supervisorStrategy()}
	 * @param parent
	 *            the parent cell, or {@code null} for a root actor
	 * @param dispatcher
	 *            where the new cell's loop and extra virtual threads are placed —
	 *            already resolved from the caller's {@link Placement} through
	 *            {@link ActorRuntime#dispatcherFor(Placement, ActorCell)} before
	 *            this method is called
	 * @param runtime
	 *            the shared engine state the new cell is registered into
	 * @throws IllegalArgumentException
	 *             if {@code name} is blank or contains {@code '/'}
	 * @throws IllegalStateException
	 *             if a sibling already occupies the composed path, or if
	 *             {@code parent} is itself terminating or stopped — spawning under
	 *             a terminating parent would make its own stop condition ("mailbox
	 *             drained, children known and themselves stopped") unreachable
	 * @return the new cell, not yet running: the caller finishes registering it and
	 *         then calls {@link #start()}
	 */
	static ActorCell createCell(String name, Supplier<Actor> factory, SupervisionStrategy explicitStrategy,
			ActorCell parent, ActorDispatcher dispatcher, ActorRuntime runtime) {
		validateName(name);
		if (parent != null && parent.isTerminatingOrStopped()) {
			throw new IllegalStateException("cannot spawn \"" + name + "\" under a stopping actor: " + parent.path());
		}
		String path = parent == null ? "/" + name : parent.path() + "/" + name;
		ActorCell cell = new ActorCell(name, path, parent, factory, explicitStrategy, dispatcher, runtime);
		if (!runtime.register(cell)) {
			throw new IllegalStateException("an actor already exists at path: " + path);
		}
		if (parent != null) {
			parent.addChild(cell);
			if (parent.isTerminatingOrStopped()) {
				/*
				 * Best-effort closing of a narrow race, in the same best-effort spirit as a
				 * tell racing a stop (see enqueue()): the check above already rejects the
				 * overwhelmingly common case, a parent already stopping when spawn is called,
				 * but a parent that begins stopping in the handful of statements between that
				 * check and this one could otherwise have already taken its children snapshot
				 * without this brand-new cell in it. Stopping the new cell here keeps it from
				 * being orphaned; it does not make the two operations atomic, only shrinks the
				 * window to almost nothing.
				 */
				cell.beginStop();
			}
		}
		return cell;
	}

	/**
	 * Enqueues this cell's {@code preStart} and starts its loop virtual thread.
	 *
	 * <p>
	 * Separate from {@link #createCell} on purpose. {@code preStart} may fail, and
	 * a failing {@code preStart} stops the cell immediately — which unregisters it,
	 * including removing it from its parent's children or from the runtime's root
	 * list. Were it started inside {@code createCell}, that removal could run
	 * before the caller had finished putting the cell <em>into</em> those
	 * structures, and the caller's own insertion would then land a permanently
	 * stopped cell there with nothing left to take it out again. Every caller must
	 * therefore register the cell fully, then call this.
	 *
	 * <p>
	 * The loop thread is assigned to {@link #loopThread} <b>before</b>
	 * {@link Thread#start()}, so a {@code tell} that races this method can unpark
	 * it. The loop itself is counted as in-flight work through
	 * {@link ActorRuntime#countedThreadFactory(ThreadFactory)}.
	 */
	void start() {
		enqueue(Envelope.lifecycle(this::runPreStart));
		Thread thread = runtime.countedThreadFactory(dispatcher.vThreadFactory()).newThread(this::runLoop);
		loopThread = thread;
		thread.start();
	}

	/**
	 * Rejects a name that is null, blank, or would forge a path. Package-visible
	 * because a pool spawn validates the <em>pool</em>'s name before deriving its
	 * routees' names from it: {@code name + "-" + i} would otherwise turn a null
	 * name into the perfectly valid {@code "null-0"}.
	 */
	static void validateName(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("an actor name must not be null or blank");
		}
		if (name.indexOf('/') >= 0) {
			throw new IllegalArgumentException("an actor name must not contain '/': " + name);
		}
	}

	// ================================================================
	// ActorContext
	// ================================================================

	@Override
	public ActorRef self() {
		return self;
	}

	/**
	 * Returns this cell's own reference, narrowed to the engine's own type — what
	 * {@link PoolRef} holds its routees as, so that a routed {@code tell} or
	 * {@code ask} lands directly on the single-actor implementation rather than
	 * going back through the interface.
	 */
	ActorRefImpl selfRef() {
		return self;
	}

	@Override
	public Optional<ActorRef> findActor(String path) {
		return runtime.find(path).<ActorRef>map(cell -> cell.self);
	}

	@Override
	public List<ActorRef> children() {
		return children.stream().<ActorRef>map(cell -> cell.self).toList();
	}

	@Override
	public ThreadFactory vThreadFactory() {
		return runtime.countedThreadFactory(dispatcher.vThreadFactory());
	}

	@Override
	public ActorRef spawn(String childName, Supplier<Actor> childFactory) {
		return spawn(childName, childFactory, SpawnOptions.defaults());
	}

	@Override
	public ActorRef spawn(String childName, Supplier<Actor> childFactory, SpawnOptions options) {
		Objects.requireNonNull(options, "options");
		// The placement is resolved per actor inside spawnGroup, pool or not: a pool
		// is exactly the children this same call would have produced one at a time.
		return runtime.spawnGroup(childName, childFactory, options, this,
				options.placement().orElse(Placement.inherit()));
	}

	@Override
	public void stop(ActorRef ref) {
		runtime.stop(ref);
	}

	@Override
	public int carrierCount() {
		return runtime.carrierCount();
	}

	@Override
	public int homeCarrierIdOf(ActorRef ref) {
		return runtime.homeCarrierIdOf(ref);
	}

	// ================================================================
	// Mailbox and loop
	// ================================================================

	/**
	 * Enqueues {@code envelope} and unparks this cell's loop, unless
	 * {@code envelope} carries a message and this cell is no longer accepting them.
	 *
	 * <p>
	 * A lifecycle envelope is exempt from that check: it is what runs this very
	 * cell's own winding-down steps, so it must be accepted (and delivered)
	 * regardless of {@link #state}.
	 *
	 * <p>
	 * {@link LockSupport#unpark} of a not-yet-started or not-yet-parked thread is a
	 * sticky permit, so a {@code tell} that races {@link #start()} or lands during
	 * {@code onReceive} cannot lose the wakeup.
	 */
	void enqueue(Envelope envelope) {
		if (!envelope.isLifecycle()) {
			State current = state.get();
			if (current == State.TERMINATING || current == State.STOPPED) {
				return;
			}
		}
		mailbox.offer(envelope);
		Thread thread = loopThread;
		if (thread != null) {
			LockSupport.unpark(thread);
		}
	}

	/**
	 * Drains the mailbox until empty, parks, repeats, until {@link State#STOPPED}.
	 * A throwing envelope does not kill this loop — {@link #runEnvelope} already
	 * contains every failure; anything that still escapes is logged and the loop
	 * continues, the same way a {@code ForkJoinPool} worker survives a throwing
	 * task. {@link Thread#interrupted()} is cleared after every envelope and after
	 * every park so a task that re-arms the interrupt status cannot turn the next
	 * park into a spin.
	 */
	private void runLoop() {
		Thread.currentThread().setName("looma-" + path);
		int rounds = 0;
		while (state.get() != State.STOPPED) {
			Envelope envelope = mailbox.poll();
			if (envelope != null) {
				runEnvelope(envelope);
				Thread.interrupted();
				if (++rounds >= YIELD_EVERY) {
					rounds = 0;
					Thread.yield();
				}
				continue;
			}
			rounds = 0;
			if (state.get() == State.STOPPED) {
				break;
			}
			if (state.get() == State.TERMINATING && !cascadeStarted.get()) {
				tryFinishDraining();
				continue;
			}
			LockSupport.park();
			Thread.interrupted();
		}
	}

	/**
	 * Runs a single envelope: a lifecycle step directly, or a message through
	 * {@link Actor#onReceive}.
	 */
	private void runEnvelope(Envelope envelope) {
		if (envelope.isLifecycle()) {
			runLifecycleStep(envelope.lifecycle());
		} else {
			runMessage(envelope);
		}
	}

	private void runLifecycleStep(Runnable step) {
		try {
			step.run();
		} catch (Throwable error) {
			/*
			 * Safety net only: every lifecycle step this engine itself schedules
			 * (runPreStart, restart(), runFinalStop) already applies its own documented
			 * failure policy internally. Reaching here would mean one of them let something
			 * escape it should not have.
			 */
			LOG.log(Level.WARNING, () -> "actor " + path + " lifecycle step failed unexpectedly", error);
		}
	}

	private void runMessage(Envelope envelope) {
		Message message = envelope.message();
		try {
			actor.onReceive(message, envelope.sender(), this);
		} catch (Throwable error) {
			handleFailure(error, message);
		}
	}

	// ================================================================
	// Lifecycle: preStart, graceful stop, final stop
	// ================================================================

	/**
	 * Runs once, as this cell's very first lifecycle envelope, before any message
	 * is ever delivered. A failure here is never handed to a supervision strategy —
	 * a strategy could only restart into the very failure that just happened — it
	 * stops this cell immediately instead.
	 */
	private void runPreStart() {
		try {
			actor.preStart(this);
			state.compareAndSet(State.STARTING, State.RUNNING);
		} catch (Throwable error) {
			LOG.log(Level.WARNING, () -> "actor " + path + " preStart failed; stopping", error);
			beginStop();
		}
	}

	/**
	 * Begins the graceful cascading stop protocol on this cell: idempotent (a
	 * second call, or a call on an already-terminating or already-stopped cell, is
	 * a silent no-op) and — critically — unparks this cell's loop unconditionally,
	 * even when its mailbox is already empty and the loop is parked.
	 *
	 * <p>
	 * That unconditional kick is not an edge case: stopping an <em>idle</em> actor
	 * is the common case, and without it nothing would ever notice the state
	 * change, since the loop only runs envelopes that are actually queued.
	 * Enqueuing a (no-op) lifecycle envelope here is what makes the mailbox
	 * non-empty again, so the loop observes {@link State#TERMINATING} and proceeds
	 * to {@link #tryFinishDraining()}.
	 */
	void beginStop() {
		State previous = state.getAndUpdate(
				current -> (current == State.STARTING || current == State.RUNNING) ? State.TERMINATING : current);
		if (previous == State.STARTING || previous == State.RUNNING) {
			enqueue(Envelope.lifecycle(() -> {
			}));
		}
	}

	/**
	 * Runs once the mailbox holds no more message envelope while this cell is still
	 * {@link State#TERMINATING}: stops every current child, chains on their
	 * combined termination, and schedules this cell's own final stop once every one
	 * of them has itself fully stopped.
	 *
	 * <p>
	 * Guarded by {@link #cascadeStarted}, a compare-and-set rather than a plain
	 * check, because more than one caller can end up observing that condition for
	 * the same cell; only the first may proceed, or {@code postStop} — and every
	 * child's stop — would run twice.
	 */
	private void tryFinishDraining() {
		if (!cascadeStarted.compareAndSet(false, true)) {
			return;
		}
		List<ActorCell> childrenSnapshot = List.copyOf(children);
		childrenSnapshot.forEach(ActorCell::beginStop);
		CompletableFuture<?>[] childrenTerminated = childrenSnapshot.stream().map(ActorCell::terminated)
				.toArray(CompletableFuture[]::new);
		CompletableFuture.allOf(childrenTerminated).whenComplete((ignoredValue, ignoredError) -> scheduleFinalStop());
	}

	/**
	 * Enqueues this cell's very last lifecycle envelope: {@code postStop}, then
	 * unregistration.
	 */
	private void scheduleFinalStop() {
		enqueue(Envelope.lifecycle(this::runFinalStop));
	}

	/**
	 * Runs {@code postStop}, then unregisters this cell from the path registry and
	 * from its parent's children (or the runtime's root list), and completes
	 * {@link #terminated}. A failure from {@code postStop} is logged and never
	 * prevents any of the steps that follow it.
	 *
	 * <p>
	 * {@code terminated.complete(null)} runs its dependents inline, on this very
	 * thread — that is how a parent's
	 * {@code CompletableFuture.allOf(childrenTerminated)} cascade in
	 * {@link #tryFinishDraining()} advances when this cell is the last child to
	 * finish. The loop then observes {@link State#STOPPED} and exits; there is no
	 * dispatcher to release.
	 */
	private void runFinalStop() {
		try {
			actor.postStop(this);
		} catch (Throwable error) {
			LOG.log(Level.WARNING, () -> "actor " + path + " postStop failed", error);
		}
		state.set(State.STOPPED);
		if (parent != null) {
			parent.removeChild(this);
		} else {
			runtime.removeRoot(this);
		}
		runtime.unregister(this);
		terminated.complete(null);
	}

	// ================================================================
	// Supervision
	// ================================================================

	/**
	 * Reacts to a failure thrown by {@link Actor#onReceive}.
	 *
	 * <p>
	 * A failure while this cell is already {@link State#TERMINATING} or
	 * {@link State#STOPPED} never consults a strategy at all: restarting an actor
	 * that is winding down would fight its own cascade, and stopping it again would
	 * be redundant. It is simply logged, and draining continues.
	 *
	 * <p>
	 * This catches {@link Throwable} (dispatched from {@link #runMessage}, which
	 * already caught it), not merely {@code RuntimeException}:
	 * {@link Actor#onReceive} declares {@code throws Exception}, and an
	 * {@link Error} deserves the same supervision treatment as any other failure
	 * rather than a silent kill of the loop.
	 */
	private void handleFailure(Throwable error, Message message) {
		if (isTerminatingOrStopped()) {
			LOG.log(Level.WARNING, () -> "actor " + path + " failed while terminating; message dropped", error);
			return;
		}
		Directive directive;
		try {
			directive = effectiveStrategy().decide(error);
		} catch (Throwable strategyError) {
			LOG.log(Level.WARNING, () -> "actor " + path + " supervisorStrategy threw while deciding; stopping",
					strategyError);
			directive = Directive.STOP;
		}
		switch (directive) {
			case RESUME -> LOG.log(Level.WARNING, () -> "actor " + path + " resumed after a failure", error);
			case RESTART -> restart(error, message);
			case STOP -> beginStop();
		}
	}

	/**
	 * Restarts this cell in place: runs {@code preRestart} on the failing instance,
	 * obtains a fresh one from {@link #factory}, and runs {@code postRestart} on it
	 * — all synchronously, inside the loop that caught the failure.
	 *
	 * <p>
	 * Deliberately does not start a second loop virtual thread: the same thread
	 * keeps draining. The failed message is discarded, not retried; the pending
	 * mailbox resumes on the new instance through the normal path. Children are
	 * left running, untouched — unlike Akka, which stops them, and chosen here for
	 * simplicity.
	 *
	 * <p>
	 * <b>The consequence, for callers:</b> an actor that spawns its children in
	 * {@code preStart} cannot be restarted. The default {@code postRestart} calls
	 * {@code preStart}, which spawns at a path the surviving old child still holds,
	 * so registration throws and the escalation below turns the restart into a
	 * {@link Directive#STOP}. Spawn children on a message rather than in
	 * {@code preStart} if the actor is meant to be restartable.
	 *
	 * <p>
	 * A throwing {@code preRestart}/{@code postRestart}, or a {@link #factory} that
	 * throws or returns {@code null}, escalates to a {@link Directive#STOP} of this
	 * cell instead, logged.
	 */
	private void restart(Throwable cause, Message message) {
		try {
			actor().preRestart(cause, Optional.of(message), this);
			Actor fresh = newActorInstance();
			if (fresh == null) {
				throw new IllegalStateException("actor factory returned null for " + path + " on restart");
			}
			replaceActor(fresh);
			fresh.postRestart(cause, this);
		} catch (Throwable restartError) {
			LOG.log(Level.WARNING, () -> "actor " + path + " restart failed; stopping instead", restartError);
			beginStop();
		}
	}

	/**
	 * Returns this cell's current actor instance.
	 */
	Actor actor() {
		return actor;
	}

	/**
	 * Replaces this cell's actor instance, e.g. after a restart obtains a fresh one
	 * from {@link #factory}.
	 */
	void replaceActor(Actor freshInstance) {
		this.actor = freshInstance;
	}

	/**
	 * Creates a fresh actor instance from this cell's factory, for a restart to
	 * install via {@link #replaceActor(Actor)}.
	 */
	Actor newActorInstance() {
		return factory.get();
	}

	/**
	 * Returns the strategy this cell currently applies to a failure: the one
	 * supplied explicitly at spawn time, if any, otherwise the current actor
	 * instance's own {@link Actor#supervisorStrategy()} — re-read here rather than
	 * cached, so that a restart's fresh instance is consulted on its own terms.
	 */
	SupervisionStrategy effectiveStrategy() {
		return explicitStrategy != null ? explicitStrategy : actor.supervisorStrategy();
	}
}
