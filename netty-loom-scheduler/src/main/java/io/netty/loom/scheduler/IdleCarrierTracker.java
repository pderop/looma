/*
 * Copyright 2026 The Netty VirtualThread Scheduler Project
 *
 * The Netty VirtualThread Scheduler Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
// Modified by the Looma project: wakeFirstIdle() starts its scan at a random member
// instead of the first. Every change is marked "Looma:"; the full diff against the
// original is netty-loom-scheduler/looma-home-queue.patch.
package io.netty.loom.scheduler;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Lock-free bitmap tracking which carriers in a cluster are currently idle.
 * Accepts global carrier IDs and maps them to compact cluster-local indices
 * internally. The bitmap is sized to the cluster member count — no wasted bits.
 */
final class IdleCarrierTracker {

	private static final VarHandle WORDS = MethodHandles.arrayElementVarHandle(long[].class);

	private final EventLoopScheduler[] members;
	private final int[] idToIndex;
	private final int[] indexToId;
	private final long[] words;
	private final int capacity;

	IdleCarrierTracker(EventLoopScheduler[] members) {
		this.members = members;
		this.capacity = members.length;
		this.words = new long[(capacity + 63) >>> 6];
		this.indexToId = new int[capacity];
		int maxId = 0;
		for (int i = 0; i < capacity; i++) {
			indexToId[i] = members[i].id();
			maxId = Math.max(maxId, members[i].id());
		}
		this.idToIndex = new int[maxId + 1];
		java.util.Arrays.fill(idToIndex, -1);
		for (int i = 0; i < capacity; i++) {
			idToIndex[members[i].id()] = i;
		}
	}

	IdleCarrierTracker(int carrierCount) {
		this.members = null;
		this.idToIndex = null;
		this.indexToId = null;
		this.capacity = carrierCount;
		this.words = new long[(carrierCount + 63) >>> 6];
	}

	void markIdle(int carrierId) {
		int idx = toIndex(carrierId);
		int wordIndex = idx >>> 6;
		long bit = 1L << idx;
		long prev;
		do {
			prev = (long) WORDS.getVolatile(words, wordIndex);
		} while (!WORDS.compareAndSet(words, wordIndex, prev, prev | bit));
	}

	void markActive(int carrierId) {
		int idx = toIndex(carrierId);
		int wordIndex = idx >>> 6;
		long bit = 1L << idx;
		long prev;
		do {
			prev = (long) WORDS.getVolatile(words, wordIndex);
		} while (!WORDS.compareAndSet(words, wordIndex, prev, prev & ~bit));
	}

	/**
	 * Returns the global ID of any idle carrier, or -1 if none.
	 */
	int findIdle() {
		for (int w = 0; w < words.length; w++) {
			long word = (long) WORDS.getVolatile(words, w);
			if (word != 0) {
				int idx = (w << 6) + Long.numberOfTrailingZeros(word);
				return idx < capacity ? toId(idx) : -1;
			}
		}
		return -1;
	}

	boolean isIdle(int carrierId) {
		int idx = toIndex(carrierId);
		int wordIndex = idx >>> 6;
		long bit = 1L << idx;
		return ((long) WORDS.getVolatile(words, wordIndex) & bit) != 0;
	}

	boolean wakeFirstIdle(EventLoopScheduler victim) {
		// Looma: start the scan at a random member rather than at the first. From the
		// first, the lowest-numbered idle carrier is the one woken to steal every time,
		// so the first carriers do more of the work than the last ones. From a random
		// member, the work is spread over all of them. With no member, there is nothing
		// to wake.
		if (capacity == 0) {
			return false;
		}
		int start = java.util.concurrent.ThreadLocalRandom.current().nextInt(capacity);
		return wakeFrom(start, victim, WAKE_MEMBER);
	}

	// Looma: the scan of wakeFirstIdle, from the member start.
	//
	// Each member has one bit in words: bit k of word w is member 64 * w + k, and it is 1
	// when that member is idle. The scan reads each word once, starting with the word of
	// start, and comes back to the first word after the last one. A word with no idle
	// member is skipped in one step. The scan stops at the first member that waker wakes.
	//
	// Within a word, it starts at the bit of start, r, and goes round: r, r + 1, ..., 63,
	// then 0, ..., r - 1. For that, it rotates the word right by r: bit r comes to bit 0,
	// and bits 0 to r - 1 go to the top. It then takes the bits from the lowest up, and
	// adds r back to find the member.
	//
	// For example, with 12 members (one word), start = 5, and members 2, 7 and 10 idle:
	//
	//   member   11 10 9 8 7 6 5 4 3 2 1 0
	//   word      0  1 0 0 1 0 0 0 0 1 0 0
	//
	// Rotated right by 5, member 5 is at bit 0: member 7 is at bit 2, member 10 at bit 5,
	// and member 2 at bit 61. From the lowest bit up, the scan tries 7, 10, then 2.
	//
	// With more than 64 members, every word is read from the same bit r, and every idle
	// member is still tried once.
	//
	// Each word is read when the scan gets to it, so the scan sees the idle members of
	// that moment, as the original scan did.
	boolean wakeFrom(int start, EventLoopScheduler victim, Waker waker) {
		int r = start & 63;
		int w = start >>> 6;
		for (int i = 0; i < words.length; i++) {
			long word = Long.rotateRight((long) WORDS.getVolatile(words, w), r);
			while (word != 0) {
				int idx = (w << 6) + ((Long.numberOfTrailingZeros(word) + r) & 63);
				if (idx < capacity && waker.wake(this, idx, victim)) {
					return true;
				}
				word &= word - 1; // clears the lowest bit set
			}
			if (++w == words.length) {
				w = 0;
			}
		}
		return false;
	}

	// Looma: wakes the member at an index. A test gives its own, to see the order of the
	// scan without real carriers.
	interface Waker {
		boolean wake(IdleCarrierTracker tracker, int index, EventLoopScheduler victim);
	}

	private static final Waker WAKE_MEMBER = (tracker, index, victim) -> tracker.members[index]
			.wakeupAsSearcher(victim);

	private int toIndex(int carrierId) {
		return idToIndex != null ? idToIndex[carrierId] : carrierId;
	}

	private int toId(int index) {
		return indexToId != null ? indexToId[index] : index;
	}
}
