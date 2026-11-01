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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.pderop.looma.Carriers;
import io.netty.loom.scheduler.NettyScheduler;
import io.netty.loom.topology.LinuxCarrierTopology;

/**
 * The system properties a {@link Carriers.Affine} stands for — the ones
 * {@code scripts/} used to pass as {@code -D} flags, under the copied
 * scheduler's own names, which stay unchanged because that code does. The
 * values are always the same: carriers pinned and grouped by L3, stealing
 * within a group, poller mode 3.
 */
final class NettyCarrierProperties {

	static final String IMPL_CLASS = "jdk.virtualThreadScheduler.implClass";

	static final String CARRIERS = "io.netty.loom.schedulers";

	static final String TOPOLOGY = "io.netty.loom.topology";

	static final String WORK_STEALING = "io.netty.loom.workstealing.enabled";

	static final String STEAL_SCOPE = "io.netty.loom.workstealing.scope";

	/**
	 * {@code 3} is POLLER_PER_CARRIER: each carrier runs its own read poller, which
	 * {@code NettyScheduler} keeps on that carrier. Left to the JDK's default the
	 * read pollers are virtual threads of the built-in scheduler instead, whose
	 * unpinned ForkJoinPool workers then run on the carriers' CPUs and take turns
	 * with them.
	 */
	static final String POLLER_MODE = "jdk.pollerMode";

	private NettyCarrierProperties() {
	}

	/**
	 * Every property {@code carriers} decides, mapped to its value.
	 * {@code implClass} last: it is the one that makes the others matter.
	 */
	static Map<String, String> of(Carriers.Affine carriers) {
		Map<String, String> properties = new LinkedHashMap<>();
		properties.put(CARRIERS, Integer.toString(carriers.count()));
		properties.put(TOPOLOGY, LinuxCarrierTopology.class.getName());
		properties.put(WORK_STEALING, "true");
		properties.put(STEAL_SCOPE, "CLUSTER_LOCAL");
		properties.put(POLLER_MODE, "3");
		properties.put(IMPL_CLASS, NettyScheduler.class.getName());
		return properties;
	}

	/**
	 * Sets every property of {@link #of(Carriers.Affine)}, after checking that none
	 * of them is already set to something else — so that nothing is set at all when
	 * one conflicts.
	 *
	 * @return the properties this call set, which were absent before: what
	 *         {@link #clear(List)} takes back if the install fails
	 * @throws IllegalStateException
	 *             naming the first property already set to another value
	 */
	static List<String> set(Carriers.Affine carriers) {
		Map<String, String> properties = of(carriers);
		properties.forEach((key, value) -> {
			String current = System.getProperty(key);
			if (current != null && !current.equals(value)) {
				throw new IllegalStateException("-D" + key + "=" + current + " is already set, and conflicts with "
						+ carriers + ", which sets it to " + value);
			}
		});
		List<String> added = new ArrayList<>();
		properties.forEach((key, value) -> {
			if (System.getProperty(key) == null) {
				System.setProperty(key, value);
				added.add(key);
			}
		});
		return added;
	}

	/** Removes the properties {@link #set} added. */
	static void clear(List<String> added) {
		added.forEach(System::clearProperty);
	}
}
