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

/**
 * The {@link ActorScheduler} view over the JDK's MPSC scheduler.
 *
 * <p>
 * With affinity (the default of {@code Carriers.mpsc}), an actor's mailbox
 * threads are created with the affinity of its carrier: they run there, and are
 * never stolen. Without ({@code affinity(false)}), they have none: an actor can
 * run on any carrier. Its thread starts on the carrier of its sender, and an
 * idle carrier may steal it. The threads an actor offloads never have affinity:
 * they start on the carrier that starts them, and an idle carrier may steal
 * them.
 *
 * <p>
 * This class owns nothing: the carriers belong to the JDK. {@link #close()}
 * does nothing.
 */
final class MpscActorScheduler implements ActorScheduler {

	private final MpscPool pool;

	private final ThreadFactory[] mailboxThreadFactories;

	private final ThreadFactory offloadFactory = Thread.ofVirtual().factory();

	private final boolean affinity;

	MpscActorScheduler(MpscPool pool, boolean affinity) {
		this.pool = pool;
		this.affinity = affinity;
		this.mailboxThreadFactories = new ThreadFactory[pool.count()];
		for (int i = 0; i < mailboxThreadFactories.length; i++) {
			mailboxThreadFactories[i] = affinity ? Thread.ofVirtual().affinity(pool.home(i)).factory() : offloadFactory;
		}
	}

	@Override
	public int carrierCount() {
		return mailboxThreadFactories.length;
	}

	@Override
	public ThreadFactory vThreadFactory(int index) {
		return offloadFactory;
	}

	@Override
	public ThreadFactory mailboxThreadFactory(int index) {
		return mailboxThreadFactories[index];
	}

	/**
	 * Only with affinity. Without, the timers of an actor go through the queues.
	 */
	@Override
	public boolean mailboxThreadsStayOnCarrier() {
		return affinity;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * The carrier the calling thread runs on. For an actor's mailbox thread with
	 * affinity, it is its home: it never moves.
	 */
	@Override
	public int currentCarrierId() {
		return MpscPool.currentCarrier();
	}

	@Override
	public ThreadFactory platformThreadFactory() {
		return AllCpus.platform();
	}

	@Override
	public OptionalInt pinnedCpu() {
		int cpu = LinuxCpus.pinnedCpu();
		return cpu < 0 ? OptionalInt.empty() : OptionalInt.of(cpu);
	}

	@Override
	public OptionalInt clusterOf(int index) {
		if (index < 0 || index >= mailboxThreadFactories.length) {
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
