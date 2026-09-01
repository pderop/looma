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
package io.github.pderop.looma.examples.http;

import java.util.random.RandomGenerator;

/**
 * A pointer chase over an {@code int[]}, for benchmarks that need to expose a
 * memory latency rather than hide it.
 *
 * <p>
 * The array is logically cut into "lines" of 64 bytes = 16 ints. Each line
 * holds:
 *
 * <pre>
 *     slot 0           : the index of the line to visit next
 *     slot 1 (PAYLOAD) : the value actually read and modified
 *     slots 2..15      : padding, so that one logical line = one cache line
 * </pre>
 *
 * <p>
 * {@link #layOutChase} threads those slot 0s into a single random cycle passing
 * through every line exactly once, and {@link #chase} follows it.
 *
 * <p>
 * <b>Why the next line is read out of the current one, instead of drawn from a
 * generator.</b> A CPU does not wait for one load at a time — it keeps a dozen
 * or so in flight at once. But it can only do that when it knows the addresses
 * in advance, and an address drawn from a random number generator <em>is</em>
 * known in advance, because computing it touches no memory. Written that way,
 * four accesses cost one memory latency instead of four:
 *
 * <pre>
 * index drawn from a generator      index read out of the line itself
 * ----------------------------      ---------------------------------
 * t=0    issue load of line 2       t=0     issue load of line 0
 * t=0    issue load of line 0       t=30ns  arrives, holds 3 -&gt; issue line 3
 * t=0    issue load of line 3       t=60ns  arrives, holds 1 -&gt; issue line 1
 * t=0    issue load of line 1       t=90ns  arrives, holds 2 -&gt; issue line 2
 * t=30ns all four have arrived      t=120ns arrives
 * </pre>
 *
 * <p>
 * So the walk is a <b>chase</b>: each line carries the index of the next one,
 * and the loop cannot compute an address until the previous load has come back.
 * The value it modifies lives in the same line as that index, so the
 * read-modify-write costs no second miss.
 *
 * <p>
 * <b>The order is random, never sequential</b>, for a second and independent
 * reason: a contiguous scan is exactly what a hardware prefetcher is built to
 * hide. The L2 streamer would detect an ascending stride and fetch lines — and
 * the read-for-ownership requests that go with them — far enough ahead to erase
 * the very difference the chase exists to expose.
 *
 * <p>
 * <b>A single cycle, rather than a random index per step.</b> An arbitrary
 * permutation used directly as a successor function decomposes into several
 * disjoint cycles, and a walk would spin inside whichever small one it started
 * in — visiting a handful of lines that then sit in L1. Threading one cycle
 * through all of them guarantees the walk covers the whole array, from whatever
 * cursor it happens to start at.
 *
 * <p>
 * Nothing here is thread-safe: an array and its cursor belong to one walker at
 * a time.
 */
final class PointerChase {

	/** One cache line on every CPU these benchmarks target. */
	static final int CACHE_LINE_BYTES = 64;

	/**
	 * The array is walked one cache line at a time, so this is the stride.
	 *
	 * <p>
	 * A Java array's elements begin after its header, so a 64-byte stride is not
	 * guaranteed to be 64-byte <em>aligned</em>: a logical line may straddle two
	 * physical ones. That doubles the lines actually touched, identically for every
	 * walker, so it costs a little absolute precision and nothing at all in a
	 * comparison.
	 */
	static final int INTS_PER_CACHE_LINE = CACHE_LINE_BYTES / Integer.BYTES;

	/**
	 * Slot within a line holding the value read and modified. It shares the line
	 * with the next-line index in slot 0, so touching it costs no second miss.
	 */
	static final int PAYLOAD = 1;

	private PointerChase() {
	}

	/** How many cache lines {@code cacheLines} amounts to. */
	static int cacheLineCount(int[] cacheLines) {
		return cacheLines.length / INTS_PER_CACHE_LINE;
	}

	/**
	 * Threads a single random cycle through every line of {@code cacheLines}: slot
	 * 0 of each line holds the index of the line to visit after it, so following
	 * those indices {@link #cacheLineCount} times visits every line exactly once
	 * and comes back to where it started.
	 *
	 * <p>
	 * Drawing the permutation from a caller-supplied generator lets two walkers be
	 * given different orders while a given run stays reproducible.
	 */
	static void layOutChase(int[] cacheLines, RandomGenerator rnd) {
		int n = cacheLineCount(cacheLines);
		int[] visitOrder = new int[n];

		// start from the identity permutation: visit line 0, then 1, then 2...
		for (int i = 0; i < n; i++) {
			visitOrder[i] = i;
		}

		// Fisher-Yates shuffle: every permutation is equally likely, so the order is
		// genuinely random and not some stride a prefetcher could learn.
		for (int i = n - 1; i > 0; i--) {
			int j = rnd.nextInt(i + 1);
			int swapped = visitOrder[i];
			visitOrder[i] = visitOrder[j];
			visitOrder[j] = swapped;
		}

		// Write that itinerary INTO the data itself: slot 0 of the line visited at
		// step i holds the number of the line visited at step i+1.
		//
		// The modulo only ever fires on the last iteration, where i+1 == n and it
		// yields 0: the last line points back at the first, closing the cycle. Closing
		// it is what lets chase() start anywhere and still cover every line in n steps
		// -- and it removes any special case from the hot loop.
		for (int i = 0; i < n; i++) {
			cacheLines[visitOrder[i] * INTS_PER_CACHE_LINE] = visitOrder[(i + 1) % n];
		}
	}

	/**
	 * Walks every line once from {@code startCursor}, adding {@code delta} to each
	 * payload, and returns the cursor the next walk should pick up from.
	 *
	 * <p>
	 * <b>Read-modify-write, never a plain read</b>, so each visited line is left
	 * <em>dirty</em>: a core that visits it next must take it away rather than
	 * merely share it, which is the coherence cost these benchmarks exist to
	 * expose.
	 *
	 * <p>
	 * Carrying the cursor across calls, rather than resetting it, keeps anything
	 * about the walk from being constant from the JIT's point of view.
	 */
	static int chase(int[] cacheLines, int startCursor, int delta) {
		int n = cacheLineCount(cacheLines);
		int cursor = startCursor;
		for (int i = 0; i < n; i++) {
			cursor = cacheLines[cursor * INTS_PER_CACHE_LINE]; // slot 0: index of the next line
			cacheLines[cursor * INTS_PER_CACHE_LINE + PAYLOAD] += delta;
		}
		return cursor;
	}

	/**
	 * Follows the same chase, reading one payload per line, and returns their sum.
	 * A read-only walk: it leaves no line dirty.
	 *
	 * <p>
	 * The chase lives in the array itself, so a copy of a laid-out array carries it
	 * for free and this needs no generator of its own.
	 */
	static int sumPayloads(int[] cacheLines) {
		int n = cacheLineCount(cacheLines);
		int cursor = 0;
		int sum = 0;
		for (int i = 0; i < n; i++) {
			cursor = cacheLines[cursor * INTS_PER_CACHE_LINE];
			sum += cacheLines[cursor * INTS_PER_CACHE_LINE + PAYLOAD];
		}
		return sum;
	}
}
