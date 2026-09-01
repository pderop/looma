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

import java.util.Optional;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Placement;
import io.github.pderop.looma.SpawnOptions;
import io.github.pderop.looma.spi.ActorScheduler;
import io.github.pderop.looma.spi.ActorSchedulerProvider;
import io.github.pderop.looma.spi.MailboxQueueFactory;

/**
 * The one {@link ActorSystem} implementation this project ships: an actor
 * system whose placement comes entirely from an {@link ActorScheduler}, with
 * two possible providers behind that seam — the carrier-affine one
 * (discovered), and {@link JdkActorScheduler} when discovery finds nothing.
 *
 * <p>
 * <b>Callers never name this class.</b> {@link ActorSystem#builder()} is the
 * entry point, it returns the {@link ActorSystem.Builder} contract, and
 * {@link ActorSystem.Builder#build()} hands back an {@link ActorSystem} — so
 * nothing outside this package has to spell out an implementation type to get
 * one. This class is {@code public} only because that static factory has to
 * reach {@link #newBuilder()} from the API package, which package-privacy
 * cannot span without a {@code module-info.java}.
 *
 * <p>
 * The actor engine itself — mailboxes, one virtual thread per actor, hierarchy,
 * supervision, {@code ask}, the graceful cascading stop — lives in
 * {@link ActorRuntime} and {@link ActorCell} and never differs between the two
 * schedulers. This class contributes only placement: which carrier an actor
 * lands on, according to whichever {@link ActorScheduler} it was built with.
 * Every actor then starts its own loop from that carrier's
 * {@link ActorScheduler#vThreadFactory(int)}. It exposes its dispatcher array
 * to {@link ActorRuntime} through the {@link Dispatchers} seam, which is what
 * lets {@link ActorRuntime#dispatcherFor(Placement, ActorCell)} resolve a
 * {@link Placement} without this class implementing the engine's own
 * {@code spawn} logic a second time.
 *
 * <p>
 * Placement follows two <b>default</b> rules, both overridable per spawn via
 * {@link SpawnOptions#placedOn(Placement)}. A <b>root</b> actor is, by default,
 * assigned a carrier round-robin over {@link ActorScheduler#carrierCount()}. A
 * <b>child</b> spawned through {@link ActorContext#spawn} without an explicit
 * placement inherits its parent's home carrier, so a parent-to-child
 * {@code tell} — and a reply back up — pays no cross-carrier hop; so does every
 * virtual thread the actor creates through
 * {@link ActorContext#vThreadFactory()}, which is where its blocking work
 * belongs. A whole subtree of actors and their blocking work therefore shares
 * one carrier's locality by default, unless a spawn opts out with an explicit
 * {@link Placement}. Under {@link JdkActorScheduler}, {@code carrierCount()} is
 * {@code 1}, so every placement collapses to that one factory.
 *
 * <p>
 * A {@code tell} offers into the actor's mailbox and unparks its loop. The loop
 * drains until empty, then parks. How (or whether) a parked continuation
 * resumes on the same carrier is entirely up to the {@link ActorScheduler} in
 * use. Being run elsewhere moves where a continuation <em>executes</em>; it
 * never moves an actor's home carrier, which is fixed for its lifetime the
 * moment its placement is resolved, at spawn time.
 */
public final class DefaultActorSystem implements ActorSystem {

	/**
	 * The scheduler this system's placement comes from, exposed on the
	 * {@code ActorSystem} contract via {@link #scheduler()}: startup diagnostics,
	 * pinning probes and affinity tests all need to reach it.
	 */
	private final ActorScheduler scheduler;

	/**
	 * One dispatcher per carrier, indexed by carrier id. There are only
	 * {@link ActorScheduler#carrierCount()} distinct placements, so the whole
	 * system shares these instances rather than allocating a dispatcher per actor.
	 */
	private final CarrierDispatcher[] dispatchers;

	/**
	 * Round-robin cursor. Advanced by every {@link Placement#roundRobin()}
	 * resolution — a root spawn without an explicit placement, and any spawn (root
	 * or child) that asks for it explicitly — one shared sequence for the whole
	 * system, not a separate one per parent.
	 */
	private final AtomicInteger nextCarrier = new AtomicInteger();

	private final ActorRuntime runtime;

	/**
	 * Package-private: use {@link ActorSystem#builder()}.
	 *
	 * @param scheduler
	 *            the scheduler this system's dispatchers run on and resolve every
	 *            spawn's {@link Placement} against, per the class javadoc's two
	 *            default rules
	 */
	DefaultActorSystem(ActorScheduler scheduler, MailboxQueueFactory mailboxQueueFactory) {
		this.scheduler = scheduler;
		this.dispatchers = new CarrierDispatcher[scheduler.carrierCount()];
		for (int i = 0; i < dispatchers.length; i++) {
			dispatchers[i] = new CarrierDispatcher(scheduler.vThreadFactory(i), i);
		}
		this.runtime = new ActorRuntime(new Dispatchers(), mailboxQueueFactory, this::afterTermination);
	}

	/**
	 * Returns a new builder. Called by {@link ActorSystem#builder()}, which is what
	 * callers use; see {@link Builder} for how the scheduler is chosen when none is
	 * set explicitly.
	 */
	public static ActorSystem.Builder newBuilder() {
		return new Builder();
	}

	@Override
	public ActorScheduler scheduler() {
		return scheduler;
	}

	@Override
	public ActorRef spawn(String name, Supplier<Actor> factory) {
		return runtime.spawnRoot(name, factory, SpawnOptions.defaults());
	}

	@Override
	public ActorRef spawn(String name, Supplier<Actor> factory, SpawnOptions options) {
		return runtime.spawnRoot(name, factory, options);
	}

	@Override
	public int carrierCount() {
		return runtime.carrierCount();
	}

	@Override
	public Optional<ActorRef> findActor(String path) {
		return runtime.find(path).map(ActorCell::self);
	}

	@Override
	public void stop(ActorRef ref) {
		runtime.stop(ref);
	}

	/**
	 * Begins the graceful cascading shutdown. Everything this system privately owns
	 * is released once the cascade completes; see {@link #afterTermination()}.
	 */
	@Override
	public void shutdown() {
		runtime.beginShutdown();
	}

	/**
	 * Awaits the end of the shutdown cascade, then the release of the underlying
	 * {@link ActorScheduler}, within the single given budget (the budget is not
	 * spent twice: the time already used awaiting the cascade is subtracted before
	 * awaiting the scheduler).
	 *
	 * @return {@code true} if both the cascade and the scheduler's release
	 *         completed before the timeout elapsed
	 */
	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);

		boolean runtimeTerminated = runtime.awaitTermination(timeout, unit);

		long remainingNanos = deadlineNanos - System.nanoTime();

		boolean schedulerClosed = scheduler.awaitClosed(Math.max(0L, remainingNanos), TimeUnit.NANOSECONDS);

		return runtimeTerminated && schedulerClosed;
	}

	/**
	 * Releases everything this system privately owns, once {@link #shutdown()} has
	 * been called and the last actor and the last unit of in-flight work are gone:
	 * the {@link ActorScheduler} itself via {@link ActorScheduler#close()} —
	 * non-blocking; see {@link #awaitTermination} for the wait.
	 */
	private void afterTermination() {
		scheduler.close();
	}

	@Override
	public int homeCarrierIdOf(ActorRef ref) {
		return runtime.homeCarrierIdOf(ref);
	}

	// ================================================================
	// Builder
	// ================================================================

	/**
	 * The {@link ActorSystem.Builder} implementation. Package-private: callers only
	 * ever see the interface, handed to them by {@link ActorSystem#builder()}.
	 *
	 * <p>
	 * {@link #build()} resolves the {@link ActorScheduler} in this order: an
	 * explicit {@link #scheduler(ActorScheduler)} wins; else discovery
	 * ({@code ActorSchedulers.discover()}) is tried, falling back to
	 * {@link JdkActorScheduler} if nothing is found. The mailbox queue factory
	 * defaults to {@link ConcurrentLinkedMailboxQueue#factory()} unless
	 * {@link #mailboxQueueFactory} was set.
	 */
	static final class Builder implements ActorSystem.Builder {

		private ActorScheduler scheduler;

		private MailboxQueueFactory mailboxQueueFactory;

		private Builder() {
		}

		@Override
		public Builder scheduler(ActorScheduler scheduler) {
			this.scheduler = scheduler;
			return this;
		}

		@Override
		public Builder mailboxQueueFactory(MailboxQueueFactory mailboxQueueFactory) {
			this.mailboxQueueFactory = mailboxQueueFactory;
			return this;
		}

		@Override
		public DefaultActorSystem build() {
			MailboxQueueFactory queues = mailboxQueueFactory != null
					? mailboxQueueFactory
					: ConcurrentLinkedMailboxQueue.factory();
			return new DefaultActorSystem(resolveScheduler(), queues);
		}

		private ActorScheduler resolveScheduler() {
			if (scheduler != null) {
				return scheduler;
			}
			return ActorSchedulers.discover().map(ActorSchedulerProvider::create).orElseGet(JdkActorScheduler::new);
		}
	}

	// ================================================================
	// DispatcherSource
	// ================================================================

	/**
	 * This system's {@link DispatcherSource}: the one seam {@link ActorRuntime}
	 * uses to reach {@link #dispatchers} and {@link #nextCarrier}, without those
	 * fields — or this class's other internals — becoming visible outside this
	 * file.
	 *
	 * <p>
	 * Private, not {@code private static}: it reads the enclosing instance's
	 * {@link #dispatchers}, {@link #nextCarrier} and {@link #scheduler} directly. A
	 * single instance is created once, in the constructor, and handed to
	 * {@link ActorRuntime}, never exposed on the {@code ActorSystem} contract or
	 * anywhere else.
	 */
	private final class Dispatchers implements DispatcherSource {

		@Override
		public int carrierCount() {
			return dispatchers.length;
		}

		@Override
		public int nextCarrierId() {
			return Math.floorMod(nextCarrier.getAndIncrement(), dispatchers.length);
		}

		@Override
		public ActorDispatcher dispatcherFor(int carrierId) {
			// The bound is already checked by ActorRuntime.dispatcherFor before this is
			// called; an out-of-range index here is an engine bug, not a user-facing one.
			return dispatchers[carrierId];
		}
	}

	// ================================================================
	// Dispatcher
	// ================================================================

	/**
	 * Places an actor's loop on one carrier: the virtual thread started at spawn,
	 * and the threads the actor creates through
	 * {@link ActorContext#vThreadFactory()}, come from that carrier's own factory.
	 *
	 * <p>
	 * The factory is cached at construction, from
	 * {@link ActorScheduler#vThreadFactory(int)} — nothing is looked up on the
	 * spawn path beyond the array index.
	 */
	private static final class CarrierDispatcher implements ActorDispatcher {

		private final ThreadFactory vThreadFactory;

		private final int carrierId;

		private CarrierDispatcher(ThreadFactory vThreadFactory, int carrierId) {
			this.vThreadFactory = vThreadFactory;
			this.carrierId = carrierId;
		}

		@Override
		public ThreadFactory vThreadFactory() {
			return vThreadFactory;
		}

		@Override
		public int carrierId() {
			return carrierId;
		}
	}
}
