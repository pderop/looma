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
package io.github.pderop.looma;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Carriers} as a value, and the one refusal
 * {@link ActorSystem.Builder#build()} makes before installing anything. Nothing
 * here installs a scheduler: this JVM is the builtin control, and a property
 * set here would leak into every other test in it. Installing is tested where a
 * scheduler can be, in {@code netty-loom-scheduler}'s {@code install-test}.
 */
class CarriersTest {

	@Test
	void affineStartsUnpinnedWithoutStealing() {
		Carriers.Affine carriers = Carriers.affine(4);

		assertEquals(4, carriers.count());
		assertFalse(carriers.pinnedToCpus());
		assertEquals(Carriers.WorkStealing.OFF, carriers.workStealing());
	}

	@Test
	void refinementsReturnNewValues() {
		Carriers.Affine carriers = Carriers.affine(4).pinned().workStealing(Carriers.WorkStealing.CLUSTER_LOCAL);

		assertTrue(carriers.pinnedToCpus());
		assertEquals(Carriers.WorkStealing.CLUSTER_LOCAL, carriers.workStealing());
		assertEquals(new Carriers.Affine(4, true, Carriers.WorkStealing.CLUSTER_LOCAL, true), carriers);
	}

	@Test
	void rejectsAnEmptyPool() {
		assertThrows(IllegalArgumentException.class, () -> Carriers.affine(0));
		assertThrows(IllegalArgumentException.class, () -> Carriers.builtin(0));
	}

	@Test
	void rejectsNullCarriers() {
		assertThrows(NullPointerException.class, () -> ActorSystem.builder().carriers(null));
	}

	@Test
	void rejectsCarriersNextToAnExplicitScheduler() {
		ActorSystem other = ActorSystem.builder().build();
		try {
			ActorSystem.Builder builder = ActorSystem.builder().scheduler(other.scheduler())
					.carriers(Carriers.affine(2));

			IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);

			assertTrue(e.getMessage().contains("both set"), e.getMessage());
		} finally {
			other.shutdown();
		}
	}
}
