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

import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Carriers;
import io.github.pderop.looma.spi.ActorScheduler;

import static io.github.pderop.looma.scheduler.mpsc.install.LinuxThreads.SELF;
import static io.github.pderop.looma.scheduler.mpsc.install.LinuxThreads.TASKS;
import static io.github.pderop.looma.scheduler.mpsc.install.LinuxThreads.call;
import static io.github.pderop.looma.scheduler.mpsc.install.LinuxThreads.cpusAllowed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code Carriers.mpsc(n).pinned(false).affinity(false)}: the MPSC scheduler
 * with no carrier pinned, and no actor kept on its carrier. One fresh JVM for
 * this class.
 *
 * <p>
 * Every test asks for {@link #CARRIERS} first: the first one to run installs
 * the pool, and the others find it.
 */
class MpscUnpinnedNoAffinityInstallTest {

	private static final Carriers.Mpsc CARRIERS = Carriers.mpsc(3).pinned(false).affinity(false);

	private ActorSystem system;

	@AfterEach
	void shutdown() throws InterruptedException {
		if (system != null) {
			system.shutdown();
			assertTrue(system.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	void installsThePoolAskedFor() {
		system = ActorSystem.builder().carriers(CARRIERS).build();

		assertEquals(3, system.carrierCount());
		assertEquals("MpscActorScheduler", system.scheduler().getClass().getSimpleName());
		assertEquals("true", System.getProperty("jdk.virtualThreadScheduler.useMpsc"));
		assertEquals("3", System.getProperty("jdk.virtualThreadScheduler.parallelism"));
		assertFalse(system.scheduler().mailboxThreadsStayOnCarrier());
	}

	/** A mailbox thread is made like an offloaded one: with no affinity. */
	@Test
	void mailboxThreadsHaveNoAffinity() {
		system = ActorSystem.builder().carriers(CARRIERS).build();
		ActorScheduler scheduler = system.scheduler();

		for (int index = 0; index < 3; index++) {
			assertSame(scheduler.vThreadFactory(index), scheduler.mailboxThreadFactory(index),
					"index " + index + " has a factory of its own");
		}
	}

	@Test
	void anotherSettingIsRefused() {
		ActorSystem.builder().carriers(CARRIERS).build().shutdown();

		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> ActorSystem.builder().carriers(Carriers.mpsc(3).pinned(false)).build());

		assertTrue(e.getMessage().contains("already installed"), e.getMessage());
	}

	@Test
	void noCarrierIsPinned() throws Exception {
		assumeTrue(Files.isDirectory(TASKS), "not Linux");
		system = ActorSystem.builder().carriers(CARRIERS).build();
		String all = cpusAllowed(SELF);

		for (int index = 0; index < 3; index++) {
			assertEquals(all, call(system.scheduler(), index, () -> cpusAllowed(SELF)),
					"a carrier lost CPUs");
		}
	}
}
