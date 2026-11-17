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
package io.github.pderop.looma.examples.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class SkewedLoadTest {

	private static final int REQUESTS = 200_000;

	@Test
	void carrierZeroGetsItsShare() {
		// 20 actors on 10 carriers: carrier 0 runs actors 0 and 10.
		int[] perActor = pick(new SkewedLoad(100_000, 20, 10, 50), 20);
		assertEquals(0.25, share(perActor[0]), 0.005);
		assertEquals(0.25, share(perActor[10]), 0.005);
		for (int a = 0; a < 20; a++) {
			if (a % 10 != 0) {
				assertEquals(0.5 / 18, share(perActor[a]), 0.003);
			}
		}
	}

	@Test
	void noSkewSpreadsEvenly() {
		int[] perActor = pick(new SkewedLoad(100_000, 10, 10, 0), 10);
		for (int count : perActor) {
			assertEquals(0.1, share(count), 0.003);
		}
	}

	@Test
	void noSkewPicksAsBefore() {
		// The even load draws the same keys as a plain nextInt(keys).
		SkewedLoad load = new SkewedLoad(1000, 10, 10, 0);
		Random a = new Random(7);
		Random b = new Random(7);
		for (int i = 0; i < 1000; i++) {
			assertEquals(b.nextInt(1000), load.next(a));
		}
	}

	@Test
	void everyKeyCanBePicked() {
		// 105 keys on 10 actors: actors 0 to 4 have 11 keys, the others 10.
		SkewedLoad load = new SkewedLoad(105, 10, 5, 50);
		Random random = new Random(42);
		boolean[] seen = new boolean[105];
		for (int i = 0; i < REQUESTS; i++) {
			seen[(int) load.next(random)] = true;
		}
		for (int k = 0; k < 105; k++) {
			assertTrue(seen[k], "key " + k);
		}
	}

	@Test
	void wrongSettingsAreRefused() {
		// Not a multiple of the carriers.
		assertThrows(IllegalArgumentException.class, () -> new SkewedLoad(1000, 15, 10, 50));
		// A share out of 0 to 99.
		assertThrows(IllegalArgumentException.class, () -> new SkewedLoad(1000, 10, 10, 100));
		assertThrows(IllegalArgumentException.class, () -> new SkewedLoad(1000, 10, 10, -1));
		// Not more than the even share: 10% for 10 carriers.
		assertThrows(IllegalArgumentException.class, () -> new SkewedLoad(1000, 10, 10, 10));
		// One carrier.
		assertThrows(IllegalArgumentException.class, () -> new SkewedLoad(1000, 1, 1, 50));
		// Fewer keys than actors.
		assertThrows(IllegalArgumentException.class, () -> new SkewedLoad(5, 10, 10, 50));
		// The even load takes them.
		new SkewedLoad(1000, 1, 1, 0);
		new SkewedLoad(1000, 10, 10, 11);
	}

	@Test
	void describeSaysTheShares() {
		assertEquals("none: each of the 10 groups (one per carrier) gets the same share of the requests",
				new SkewedLoad(1000, 10, 10, 0).describe("groups"));
		assertEquals("carrier 0 gets 50% of the requests: its 2 of the 20 containers (2 per carrier), 25.0% each;"
				+ " the 18 others 2.8% each", new SkewedLoad(1000, 20, 10, 50).describe("containers"));
	}

	private static double share(int count) {
		return count / (double) REQUESTS;
	}

	/** REQUESTS keys, counted per actor. */
	private static int[] pick(SkewedLoad load, int actors) {
		Random random = new Random(42);
		int[] perActor = new int[actors];
		for (int i = 0; i < REQUESTS; i++) {
			long key = load.next(random);
			assertTrue(key >= 0 && key < load.keys());
			perActor[(int) (key % actors)]++;
		}
		return perActor;
	}
}
