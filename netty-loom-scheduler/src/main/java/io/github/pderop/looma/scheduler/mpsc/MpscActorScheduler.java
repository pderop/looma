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
package io.github.pderop.looma.scheduler.mpsc;

import java.util.OptionalInt;
import java.util.concurrent.ThreadFactory;

import io.github.pderop.looma.spi.ActorScheduler;
import io.netty.loom.topology.LinuxCarrierTopology;

/**
 * The {@link ActorScheduler} view over the JDK's MPSC scheduler.
 *
 * <p>
 * An actor's bursts are created with the affinity of its carrier: they run
 * there, and are never stolen. The threads an actor offloads have no affinity:
 * they start on the actor's carrier, and an idle carrier may steal them.
 *
 * <p>
 * This class owns nothing: the carriers belong to the JDK. {@link #close()}
 * does nothing.
 */
final class MpscActorScheduler implements ActorScheduler {

	private final MpscPool pool;

	private final ThreadFactory[] loopFactories;

	private final ThreadFactory offloadFactory = Thread.ofVirtual().factory();

	MpscActorScheduler(MpscPool pool) {
		this.pool = pool;
		this.loopFactories = new ThreadFactory[pool.count()];
		for (int i = 0; i < loopFactories.length; i++) {
			loopFactories[i] = Thread.ofVirtual().affinity(pool.home(i)).factory();
		}
	}

	@Override
	public int carrierCount() {
		return loopFactories.length;
	}

	@Override
	public ThreadFactory vThreadFactory(int index) {
		return offloadFactory;
	}

	@Override
	public ThreadFactory actorLoopFactory(int index) {
		return loopFactories[index];
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * The carrier the calling thread runs on. For an actor's burst, it is its home:
	 * it never moves.
	 */
	@Override
	public int currentCarrierId() {
		return MpscPool.currentCarrier();
	}

	@Override
	public OptionalInt pinnedCpu() {
		int cpu = LinuxCarrierTopology.pinnedCpu();
		return cpu < 0 ? OptionalInt.empty() : OptionalInt.of(cpu);
	}

	@Override
	public OptionalInt clusterOf(int index) {
		if (index < 0 || index >= loopFactories.length) {
			return OptionalInt.empty();
		}
		int cluster = pool.cluster(index);
		return cluster < 0 ? OptionalInt.empty() : OptionalInt.of(cluster);
	}

	@Override
	public void close() {
		// Nothing to release: see the class javadoc.
	}
}
