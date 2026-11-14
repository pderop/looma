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
package io.github.pderop.looma.scheduler.mpsc.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Carriers;
import io.github.pderop.looma.scheduler.mpsc.JdkSupport;

/**
 * A JDK without {@code Thread.Affinity}: only
 * {@code pinned(false).affinity(false)} installs, and its mailbox threads are
 * kept alive. One fresh JVM for this class.
 */
class MpscNoThreadAffinityTest {

	@BeforeAll
	static void needsAJdkWithoutThreadAffinity() {
		assumeFalse(JdkSupport.hasThreadAffinity(), "this JDK has Thread.Affinity");
	}

	/**
	 * One test, in this order: the refusals install nothing, then the pool without
	 * affinity installs.
	 */
	@Test
	void onlyAPoolWithoutAffinityOrPinningInstalls() throws InterruptedException {
		for (Carriers.Mpsc refused : new Carriers.Mpsc[]{Carriers.mpsc(3), Carriers.mpsc(3).pinned(false),
				Carriers.mpsc(3).affinity(false)}) {
			IllegalStateException e = assertThrows(IllegalStateException.class,
					() -> ActorSystem.builder().carriers(refused).build(), refused.toString());
			assertTrue(e.getMessage().contains("no Thread.Affinity"), e.getMessage());
			assertNull(System.getProperty("jdk.virtualThreadScheduler.useMpsc"), "a refused pool set a property");
		}

		ActorSystem system = ActorSystem.builder().carriers(Carriers.mpsc(3).pinned(false).affinity(false)).build();
		try {
			assertEquals(3, system.carrierCount());
			assertTrue(system.scheduler().keepMailboxThreadsAlive());
		} finally {
			system.shutdown();
			assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
		}
	}
}
