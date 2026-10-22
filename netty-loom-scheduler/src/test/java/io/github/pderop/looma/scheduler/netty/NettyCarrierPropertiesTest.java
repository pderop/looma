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

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.pderop.looma.Carriers;
import io.netty.loom.topology.LinuxCarrierTopology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link NettyCarrierProperties#of(Carriers.Affine)} as a pure function: a
 * pinned pool names a topology, an unpinned one must leave that property unset.
 * Nothing here sets a system property or touches a live scheduler — see
 * {@code install-test} for that.
 */
class NettyCarrierPropertiesTest {

	@Test
	void pinnedWithClusterLocalUsesLinuxCarrierTopology() {
		Map<String, String> properties = NettyCarrierProperties
				.of(Carriers.affine(4).pinned().workStealing(Carriers.WorkStealing.CLUSTER_LOCAL));

		assertEquals(LinuxCarrierTopology.class.getName(), properties.get(NettyCarrierProperties.TOPOLOGY));
		assertEquals("CLUSTER_LOCAL", properties.get(NettyCarrierProperties.STEAL_SCOPE));
	}

	@Test
	void pinnedWithGlobalUsesLinuxCarrierTopologyToo() {
		Map<String, String> properties = NettyCarrierProperties
				.of(Carriers.affine(4).pinned().workStealing(Carriers.WorkStealing.GLOBAL));

		assertEquals(LinuxCarrierTopology.class.getName(), properties.get(NettyCarrierProperties.TOPOLOGY));
		assertEquals("GLOBAL", properties.get(NettyCarrierProperties.STEAL_SCOPE));
	}

	@Test
	void everyPoolAsksForPerCarrierPollers() {
		assertEquals("3",
				NettyCarrierProperties.of(Carriers.affine(4).pinned()).get(NettyCarrierProperties.POLLER_MODE));
		assertEquals("3", NettyCarrierProperties.of(Carriers.affine(4).workStealing(Carriers.WorkStealing.GLOBAL))
				.get(NettyCarrierProperties.POLLER_MODE));
	}

	@Test
	void unpinnedNeverSetsATopology() {
		Map<String, String> properties = NettyCarrierProperties
				.of(Carriers.affine(4).workStealing(Carriers.WorkStealing.GLOBAL));

		assertNull(properties.get(NettyCarrierProperties.TOPOLOGY));
	}
}
