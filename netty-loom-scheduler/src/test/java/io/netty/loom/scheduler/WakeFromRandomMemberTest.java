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
package io.netty.loom.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Looma: the order in which {@link IdleCarrierTracker#wakeFrom} tries the idle members.
 * It reads the words from the word of the start, and goes round. In each word, it goes
 * from the bit of the start, r, up to bit 63, then from bit 0 up to r - 1. It tries each
 * idle member once, and stops at the first one that wakes.
 */
class WakeFromRandomMemberTest {

	/** Marks these members idle. */
	private static IdleCarrierTracker tracker(int members, int... idle) {
		var tracker = new IdleCarrierTracker(members);
		for (int i : idle) {
			tracker.markIdle(i);
		}
		return tracker;
	}

	/** The members tried from start, when none of them wakes. */
	private static List<Integer> tried(IdleCarrierTracker tracker, int start) {
		var tried = new ArrayList<Integer>();
		assertFalse(tracker.wakeFrom(start, null, (t, index, victim) -> {
			tried.add(index);
			return false;
		}));
		return tried;
	}

	@Test
	void oneWordGoesUpFromStartThenWraps() {
		var tracker = tracker(12, 2, 7, 10);
		assertEquals(List.of(7, 10, 2), tried(tracker, 5));
		assertEquals(List.of(7, 10, 2), tried(tracker, 7));
		assertEquals(List.of(2, 7, 10), tried(tracker, 0));
		assertEquals(List.of(2, 7, 10), tried(tracker, 11));
	}

	@Test
	void severalWordsEachFromTheBitOfStart() {
		// Words: 0 holds 3, 1 holds 64 (bit 0), 69 (bit 5), 70 (bit 6), 100 (bit 36),
		// 2 holds 129 (bit 1).
		var tracker = tracker(130, 3, 64, 69, 70, 100, 129);
		// From 70: word 1 from bit 6 (70, 100, then 64, 69), word 2, word 0.
		assertEquals(List.of(70, 100, 64, 69, 129, 3), tried(tracker, 70));
		// From 101, bit 37: word 1 has no idle member from bit 37 up, so 64, 69, 70, 100.
		assertEquals(List.of(64, 69, 70, 100, 129, 3), tried(tracker, 101));
		// From 0: every word from bit 0.
		assertEquals(List.of(3, 64, 69, 70, 100, 129), tried(tracker, 0));
	}

	@Test
	void startOnTheFirstBitOfAWord() {
		var tracker = tracker(130, 3, 64, 69, 129);
		assertEquals(List.of(64, 69, 129, 3), tried(tracker, 64));
		assertEquals(List.of(129, 3, 64, 69), tried(tracker, 128));
	}

	@Test
	void stopsAtTheFirstMemberThatWakes() {
		var tracker = tracker(12, 2, 7, 10);
		var tried = new ArrayList<Integer>();
		assertTrue(tracker.wakeFrom(5, null, (t, index, victim) -> {
			tried.add(index);
			return index == 10;
		}));
		assertEquals(List.of(7, 10), tried);
	}

	@Test
	void noIdleMemberTriesNothing() {
		assertEquals(List.of(), tried(tracker(12), 5));
		assertEquals(List.of(), tried(tracker(130), 70));
	}

	@Test
	void noMemberWakesNothing() {
		assertFalse(new IdleCarrierTracker(0).wakeFirstIdle(null));
	}

	@Test
	void everyIdleMemberOnceFromEveryStart() {
		var random = new Random(1);
		for (int members : new int[]{1, 12, 63, 64, 65, 130, 200}) {
			for (int round = 0; round < 20; round++) {
				var idle = new ArrayList<Integer>();
				for (int i = 0; i < members; i++) {
					if (random.nextInt(3) == 0) {
						idle.add(i);
					}
				}
				var tracker = tracker(members, idle.stream().mapToInt(Integer::intValue).toArray());
				int words = (members + 63) / 64;
				for (int start = 0; start < members; start++) {
					// The words from the word of start, round; in each, the idle members
					// from the bit of start up, then those below it.
					int r = start % 64;
					var expected = new ArrayList<Integer>();
					for (int k = 0; k < words; k++) {
						int w = (start / 64 + k) % words;
						for (int i : idle) {
							if (i / 64 == w && i % 64 >= r) {
								expected.add(i);
							}
						}
						for (int i : idle) {
							if (i / 64 == w && i % 64 < r) {
								expected.add(i);
							}
						}
					}
					assertEquals(expected, tried(tracker, start), members + " members, start " + start);
				}
			}
		}
	}
}
