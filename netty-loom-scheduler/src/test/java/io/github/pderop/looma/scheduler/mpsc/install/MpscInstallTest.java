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

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Carriers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ActorSystem.Builder#carriers(Carriers)} installing the MPSC scheduler
 * in a JVM started with no scheduler flag. One fresh JVM for this class.
 *
 * <p>
 * Every test asks for {@link #CARRIERS} first: the first one to run installs
 * the pool, and the others find it.
 */
class MpscInstallTest {

	private static final Carriers.Mpsc CARRIERS = Carriers.mpsc(3).workStealing(true);

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
		assertEquals("true", System.getProperty("jdk.virtualThreadScheduler.mpscSteal"));
		assertEquals("3", System.getProperty("jdk.virtualThreadScheduler.parallelism"));
	}

	@Test
	void anEqualRequestGetsTheSamePool() {
		ActorSystem.builder().carriers(CARRIERS).build().shutdown();

		system = ActorSystem.builder().carriers(Carriers.mpsc(3).workStealing(true)).build();

		assertEquals(3, system.carrierCount());
	}

	@Test
	void aDifferentRequestIsRefused() {
		ActorSystem.builder().carriers(CARRIERS).build().shutdown();

		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> ActorSystem.builder().carriers(Carriers.mpsc(2)).build());

		assertTrue(e.getMessage().contains("already installed"), e.getMessage());
	}

	@Test
	void anotherKindOfPoolIsRefused() {
		ActorSystem.builder().carriers(CARRIERS).build().shutdown();

		assertThrows(IllegalStateException.class, () -> ActorSystem.builder().carriers(Carriers.builtin(3)).build());
		assertThrows(IllegalStateException.class, () -> ActorSystem.builder().carriers(Carriers.affine(3)).build());
	}

	@Test
	void discoveryFindsTheInstalledPool() {
		ActorSystem.builder().carriers(CARRIERS).build().shutdown();

		system = ActorSystem.builder().build();

		assertEquals(3, system.carrierCount());
	}
}
