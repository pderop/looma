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

import java.util.OptionalInt;
import java.util.concurrent.ThreadFactory;

import io.github.pderop.looma.spi.ActorScheduler;
import io.netty.loom.scheduler.EventLoopScheduler;
import io.netty.loom.scheduler.EventLoopSchedulerGroup;
import io.netty.loom.topology.LinuxCarrierTopology;

/**
 * The {@link ActorScheduler} view over
 * {@link EventLoopSchedulerGroup#instance()}, the JVM-wide carrier pool the JDK
 * installs when
 * {@code -Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler}
 * is set.
 *
 * <p>
 * <b>This class owns nothing.</b> The carrier pool is a process-wide singleton
 * created during {@code VirtualThread.<clinit>} — before any
 * {@code ActorSystem} exists, and outliving every one of them — so
 * {@link #close()} is a no-op and {@link #awaitClosed} keeps the interface's
 * {@code true} default. Shutting the pool down when one {@code ActorSystem}
 * stops would break every other user of virtual threads in the process,
 * including the JDK's own.
 *
 * <p>
 * The per-carrier {@link ThreadFactory} is resolved once into an array at
 * construction: neither the group's size nor a carrier's factory can change
 * over the life of the pool. Each actor placed on a carrier starts its loop
 * virtual thread from that factory, so the loop is home-affine.
 */
final class NettyActorScheduler implements ActorScheduler {

	/**
	 * The system property {@link EventLoopSchedulerGroup} itself reads to decide
	 * whether to install a {@code CarrierTopology}. Consulted here for the same
	 * reason and nothing else: without it, carriers float, and there is no cluster
	 * map to report.
	 */
	private static final String TOPOLOGY_PROPERTY = "io.netty.loom.topology";

	private final ThreadFactory[] factories;

	/**
	 * Carrier id to last-level-cache cluster, or {@code null} when no topology was
	 * installed in this JVM.
	 *
	 * <p>
	 * Read back out of the pool the carriers actually run on, rather than from a
	 * topology object built here. A second instance would be a second opinion: it
	 * would answer even on a run where no topology was installed at all and the
	 * carriers float — a cluster map that describes nothing, which reads exactly
	 * like a real one. Whatever the group was built with, including a
	 * {@code CarrierTopology} that is not {@link LinuxCarrierTopology}, this is the
	 * grouping in force.
	 */
	private final int[] carrierToCluster;

	NettyActorScheduler(EventLoopSchedulerGroup group) {
		int size = group.size();
		this.factories = new ThreadFactory[size];
		for (int i = 0; i < size; i++) {
			factories[i] = group.scheduler(i).virtualThreadFactory();
		}
		this.carrierToCluster = readClusterMap(group, size);
	}

	private static int[] readClusterMap(EventLoopSchedulerGroup group, int size) {
		String topologyClass = System.getProperty(TOPOLOGY_PROPERTY);
		if (topologyClass == null || topologyClass.isBlank()) {
			return null;
		}
		int[] map = new int[size];
		for (int cluster = 0; cluster < group.clusterCount(); cluster++) {
			for (EventLoopScheduler scheduler : group.cluster(cluster)) {
				int id = scheduler.id();
				if (id >= 0 && id < size) {
					map[id] = cluster;
				}
			}
		}
		return map;
	}

	@Override
	public int carrierCount() {
		return factories.length;
	}

	@Override
	public ThreadFactory vThreadFactory(int index) {
		return factories[index];
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * Reads {@link EventLoopScheduler#currentScheduler()} — the calling virtual
	 * thread's <b>home</b> carrier — and deliberately not
	 * {@code currentRunningScheduler()}, which reports whichever carrier happens to
	 * be running the continuation right now. Home is what the affinity contract is
	 * about: a stolen actor is still that actor, and an assertion written against
	 * the running carrier would go red exactly when work stealing did its job.
	 */
	@Override
	public int currentCarrierId() {
		EventLoopScheduler current = EventLoopScheduler.currentScheduler();
		return current == null ? -1 : current.id();
	}

	@Override
	public OptionalInt pinnedCpu() {
		int cpu = LinuxCarrierTopology.pinnedCpu();
		return cpu < 0 ? OptionalInt.empty() : OptionalInt.of(cpu);
	}

	@Override
	public OptionalInt clusterOf(int index) {
		if (carrierToCluster == null || index < 0 || index >= carrierToCluster.length) {
			return OptionalInt.empty();
		}
		return OptionalInt.of(carrierToCluster[index]);
	}

	@Override
	public void close() {
		// Nothing to release: see the class javadoc.
	}
}
