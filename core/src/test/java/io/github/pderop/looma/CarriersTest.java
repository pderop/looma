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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Carriers} as a value, and the one refusal
 * {@link ActorSystem.Builder#build()} makes before installing anything. Nothing
 * here installs a scheduler: this JVM is the builtin control, and a property
 * set here would leak into every other test in it. Installing is tested where a
 * scheduler can be, in the {@code install-test} of {@code mpsc-scheduler}.
 */
class CarriersTest {

	@Test
	void eachKindIsItsCount() {
		assertEquals(4, Carriers.mpsc(4).count());
		assertEquals(4, Carriers.builtin(4).parallelism());
		assertEquals(Carriers.mpsc(4), Carriers.mpsc(4));
	}

	@Test
	void mpscIsPinnedAndAffineUnlessAskedNotTo() {
		Carriers.Mpsc mpsc = Carriers.mpsc(4);
		assertTrue(mpsc.pinned());
		assertTrue(mpsc.affinity());

		Carriers.Mpsc neither = mpsc.pinned(false).affinity(false);
		assertFalse(neither.pinned());
		assertFalse(neither.affinity());
		assertEquals(4, neither.count());
		assertEquals(mpsc, neither.pinned(true).affinity(true));
	}

	@Test
	void eachSettingIsItsOwn() {
		Carriers.Mpsc unpinned = Carriers.mpsc(4).pinned(false);
		assertTrue(unpinned.affinity());
		Carriers.Mpsc moving = Carriers.mpsc(4).affinity(false);
		assertTrue(moving.pinned());
		assertNotEquals(Carriers.mpsc(4), unpinned);
		assertNotEquals(Carriers.mpsc(4), moving);
		assertNotEquals(unpinned, moving);
	}

	@Test
	void rejectsAnEmptyPool() {
		assertThrows(IllegalArgumentException.class, () -> Carriers.mpsc(0));
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
			ActorSystem.Builder builder = ActorSystem.builder().scheduler(other.scheduler()).carriers(Carriers.mpsc(2));

			IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);

			assertTrue(e.getMessage().contains("both set"), e.getMessage());
		} finally {
			other.shutdown();
		}
	}
}
