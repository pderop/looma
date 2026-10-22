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
package io.github.pderop.looma.scheduler.netty.install;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Carriers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ActorSystem.Builder#carriers(Carriers)} installing the carrier-affine
 * scheduler in a JVM started with no scheduler flag at all — the
 * {@code install-test} execution, one fresh JVM per class, since what is tested
 * is the process's one and only scheduler resolution.
 *
 * <p>
 * Every test asks for {@link #CARRIERS} first, so none depends on running after
 * another: the first to run installs the pool, and each later one checks that
 * an equal request is a repeat rather than a conflict.
 */
class CarriersInstallTest {

	private static final Carriers.Affine CARRIERS = Carriers.affine(3).workStealing(Carriers.WorkStealing.GLOBAL);

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
		assertEquals("NettyActorScheduler", system.scheduler().getClass().getSimpleName());
		assertEquals("true", System.getProperty("io.netty.loom.workstealing.enabled"));
		assertEquals("GLOBAL", System.getProperty("io.netty.loom.workstealing.scope"));
	}

	@Test
	void anEqualRequestGetsTheSamePool() {
		ActorSystem.builder().carriers(CARRIERS).build().shutdown();

		system = ActorSystem.builder().carriers(Carriers.affine(3).workStealing(Carriers.WorkStealing.GLOBAL)).build();

		assertEquals(3, system.carrierCount());
	}

	@Test
	void aDifferentRequestIsRefused() {
		ActorSystem.builder().carriers(CARRIERS).build().shutdown();

		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> ActorSystem.builder().carriers(Carriers.affine(2)).build());

		assertTrue(e.getMessage().contains("already installed"), e.getMessage());
	}

	@Test
	void discoveryFindsTheInstalledPool() {
		ActorSystem.builder().carriers(CARRIERS).build().shutdown();

		system = ActorSystem.builder().build();

		assertEquals(3, system.carrierCount());
		assertSame(system.scheduler().vThreadFactory(0), system.scheduler().actorLoopFactory(0));
	}

	@Test
	void actorsStayHomeIsAChoiceOfEachSystem() throws InterruptedException {
		ActorSystem plain = ActorSystem.builder().carriers(CARRIERS).build();
		system = ActorSystem.builder().carriers(CARRIERS.actorsStayHome()).build();

		assertSame(plain.scheduler().vThreadFactory(0), plain.scheduler().actorLoopFactory(0));
		assertNotSame(system.scheduler().vThreadFactory(0), system.scheduler().actorLoopFactory(0));
		plain.shutdown();
		assertTrue(plain.awaitTermination(5, TimeUnit.SECONDS));
	}

	@Test
	void theBuiltinIsRefusedOnceTheAffinePoolIsInstalled() {
		ActorSystem.builder().carriers(CARRIERS).build().shutdown();

		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> ActorSystem.builder().carriers(Carriers.builtin(3)).build());

		assertTrue(e.getMessage().contains("already installed"), e.getMessage());
	}
}
