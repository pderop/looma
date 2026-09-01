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
package io.github.pderop.looma.spi;

import java.util.OptionalInt;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * How an {@code ActorSystem} places actors on carriers: how many there are,
 * which virtual-thread factory creates an actor's loop (and its offloaded
 * threads) for a given carrier, and what the calling thread's own placement is.
 *
 * <p>
 * This interface is deliberately small. The engine no longer dispatches
 * per-message tasks onto an {@code Executor}: each actor is one virtual thread
 * for life, started from {@link #vThreadFactory(int)} at spawn. Resist growing
 * this interface. A concern that is not placement or topology (topology probing
 * aside, which is diagnostics-only, see below) belongs somewhere else, never
 * here.
 *
 * <p>
 * <b>{@link #pinnedCpu()} is current-thread; {@link #clusterOf(int)} is
 * indexed. This asymmetry is deliberate, not an oversight.</b>
 * {@code pinnedCpu()} answers "what is the calling carrier thread pinned to",
 * which is exactly what {@code sched_getaffinity(0, …)} — "the calling thread's
 * own affinity" — can answer without any other argument; there is no cheaper or
 * more direct way to ask it, and no carrier index the underlying syscall could
 * use. {@code clusterOf(int)} answers "what last-level-cache cluster does
 * carrier {@code index} belong to", which is a static property of the topology
 * read once from {@code sysfs} at startup and needs no calling thread at all —
 * it is looked up by index because the caller already knows which carrier it is
 * asking about. Do not "fix" one to match the other's shape; they answer
 * genuinely different questions.
 *
 * <p>
 * <b>Both {@link #pinnedCpu()} and {@link #clusterOf(int)} exist purely as a
 * compile-time firewall, not because the dispatch path needs them.</b> They let
 * {@code benchmarks}' {@code CarrierPinning} and {@code examples}'
 * {@code HttpBenchMain.reportCarrierPinning} keep their "refuse to publish
 * numbers from an unpinned fork" and "warn on a single-LLC host" guards while
 * talking only to this interface — neither module ever imports anything from
 * the scheduler module itself. That is precisely what lets {@code benchmarks}
 * and {@code examples} stay compiled at {@code release=25}, without
 * {@code --enable-preview}, on a stock JDK: the topology questions those two
 * guards ask are answerable through the SPI alone.
 *
 * <p>
 * <b>An implementation never touches in-flight accounting.</b>
 * {@code ActorRuntime} is the sole owner of the counter
 * {@code ActorSystem.awaitTermination} waits on: it wraps the
 * {@link ThreadFactory} returned by {@link #vThreadFactory(int)} before it ever
 * reaches an implementation or user code. An implementation that counted its
 * own work would inevitably count it differently from another implementation of
 * this interface, and the two would stop meaning the same thing.
 */
public interface ActorScheduler extends AutoCloseable {

	/**
	 * Returns the number of carriers root actors are placed over, round-robin.
	 * Always {@code >= 1}.
	 */
	int carrierCount();

	/**
	 * Returns the factory that creates an actor's loop virtual thread — and every
	 * extra virtual thread that actor hands out through
	 * {@code ActorContext.vThreadFactory()} — placed on carrier {@code index},
	 * {@code 0 <= index < carrierCount()}.
	 */
	ThreadFactory vThreadFactory(int index);

	/**
	 * Returns the id of the carrier the <b>calling thread</b> belongs to, or
	 * {@code -1} if the calling thread belongs to none (e.g. it is not a virtual
	 * thread this scheduler placed).
	 */
	int currentCarrierId();

	/**
	 * Returns the CPU the <b>calling carrier</b> is pinned to, or
	 * {@link OptionalInt#empty()} if it floats or the pinning is unknown.
	 * Diagnostics only — never consulted on the dispatch path.
	 */
	OptionalInt pinnedCpu();

	/**
	 * Returns the last-level-cache cluster carrier {@code index} belongs to, or
	 * {@link OptionalInt#empty()} if the topology is unknown. Diagnostics only —
	 * never consulted on the dispatch path.
	 */
	OptionalInt clusterOf(int index);

	/**
	 * Begins releasing whatever this scheduler privately owns. Idempotent — calling
	 * it more than once, or when {@link ActorSchedulerProvider#create()} was never
	 * reached, is a no-op, not an error — and <b>non-blocking</b>.
	 *
	 * <p>
	 * The non-blocking contract is load-bearing, not stylistic:
	 * {@code ActorRuntime.maybeFireAfterTermination()} — the caller that reaches
	 * this method — runs from inside the actor loop whose completion just made the
	 * in-flight counter hit zero, i.e. <b>on that actor's virtual thread</b>.
	 * Blocking here would risk that thread waiting on its own exit. Waiting for the
	 * release to complete is {@link #awaitClosed}'s job, called separately from
	 * {@code ActorSystem.awaitTermination}, off the loop.
	 */
	@Override
	void close();

	/**
	 * Waits for a previously started {@link #close()} to complete, or
	 * {@code timeout} to elapse.
	 *
	 * <p>
	 * Default: {@code true} immediately — nothing to wait for. An implementation
	 * that owns no releasable resource (e.g. a carrier pool that is a JVM-wide
	 * singleton outliving every {@code ActorSystem}, or the JDK builtin scheduler)
	 * need not override this.
	 *
	 * @return {@code true} if the release completed before the timeout elapsed
	 */
	default boolean awaitClosed(long timeout, TimeUnit unit) throws InterruptedException {
		return true;
	}
}
