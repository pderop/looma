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
	void mpscIsNeitherPinnedNorAffineUnlessAsked() {
		Carriers.Mpsc mpsc = Carriers.mpsc(4);
		assertFalse(mpsc.pinned());
		assertFalse(mpsc.affine());

		Carriers.Mpsc both = mpsc.pinned(true).affine(true);
		assertTrue(both.pinned());
		assertTrue(both.affine());
		assertEquals(4, both.count());
		assertEquals(mpsc, both.pinned(false).affine(false));
	}

	@Test
	void eachSettingIsItsOwn() {
		Carriers.Mpsc pinned = Carriers.mpsc(4).pinned(true);
		assertFalse(pinned.affine());
		Carriers.Mpsc affine = Carriers.mpsc(4).affine(true);
		assertFalse(affine.pinned());
		assertNotEquals(Carriers.mpsc(4), pinned);
		assertNotEquals(Carriers.mpsc(4), affine);
		assertNotEquals(pinned, affine);
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
