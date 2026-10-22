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

/**
 * Which virtual-thread scheduler carries a process's actors, and how it is
 * sized, passed to {@link ActorSystem.Builder#carriers(Carriers)} instead of
 * JVM flags.
 *
 * <ul>
 * <li>{@link #affine(int)} — the carrier-affine scheduler: a fixed pool of
 * carriers, each actor bound to one for life. Needs a provider of it on the
 * classpath (in this project, {@code looma-netty-scheduler} on a Loom JDK
 * started with {@code --enable-preview}); {@code build()} throws when there is
 * none rather than falling back.
 * <li>{@link #builtin(int)} — the JDK's own scheduler, with its parallelism
 * fixed: the control an affine run is compared against.
 * </ul>
 *
 * <p>
 * <b>A scheduler is chosen once per JVM, before its first virtual thread.</b>
 * The JDK reads its scheduler configuration exactly once, while initialising
 * {@code java.lang.VirtualThread}, and the carrier pool is a process-wide
 * singleton created at that moment. So {@code build()} must run before anything
 * in the process — this library, another one, the JDK itself — has started a
 * virtual thread, and every {@code ActorSystem} built afterwards must ask for
 * the same pool or none at all. Only {@link Affine#actorsStayHome()} may differ
 * between two systems: it is a choice of each system, not of the pool. Both
 * rules are checked where they can be: see
 * {@link ActorSystem.Builder#carriers(Carriers)}.
 *
 * <p>
 * This is a configuration that <em>used</em> to be JVM flags, and still can be:
 * a process started with the flags and built without {@code carriers(...)} gets
 * the scheduler the flags installed, discovered as before.
 */
public sealed interface Carriers {

	/**
	 * The carrier-affine scheduler with {@code count} carriers, not pinned to CPUs
	 * and without work stealing. Refine with {@link Affine#pinned()} and
	 * {@link Affine#workStealing(WorkStealing)}.
	 *
	 * @throws IllegalArgumentException
	 *             if {@code count} is less than 1
	 */
	static Affine affine(int count) {
		return new Affine(count, false, WorkStealing.OFF, true);
	}

	/**
	 * The JDK builtin scheduler, with its parallelism set to {@code parallelism}.
	 * Its maximum pool size keeps the JDK's default, so the JDK can still add a
	 * thread when a virtual thread blocks its carrier.
	 *
	 * @throws IllegalArgumentException
	 *             if {@code parallelism} is less than 1
	 */
	static Builtin builtin(int parallelism) {
		return new Builtin(parallelism);
	}

	/**
	 * Whether, and how far, an idle carrier may run work queued on another one.
	 * Stealing recovers the CPU a strictly affine pool leaves idle and pays for it
	 * in exactly the locality affinity exists to keep.
	 */
	enum WorkStealing {

		/** Every carrier runs only its own queue. */
		OFF,

		/**
		 * Stealing between carriers that share a last-level cache only. Needs
		 * {@link Affine#pinned()}: without a CPU topology every carrier is in one
		 * cluster, which makes this {@link #GLOBAL}.
		 */
		CLUSTER_LOCAL,

		/**
		 * Stealing between any two carriers. Only one idle carrier at a time searches
		 * for work in the whole pool: with pinned carriers on several L3s, prefer
		 * {@link #CLUSTER_LOCAL}, which has one per L3.
		 */
		GLOBAL
	}

	/**
	 * A carrier-affine pool.
	 *
	 * @param count
	 *            carriers in the pool, each one an actor can call home
	 * @param pinnedToCpus
	 *            whether each carrier is pinned to a CPU of the process's affinity
	 *            mask, in ascending order — carrier {@code i} to the {@code i}-th
	 *            CPU — and grouped by the last-level cache those CPUs share. Only
	 *            takes effect where the provider can pin (Linux); elsewhere the
	 *            carriers float
	 * @param workStealing
	 *            whether and how far idle carriers steal
	 * @param actorsStealable
	 *            whether stealing may take an actor's loop as well, or only the
	 *            other virtual threads — the ones an actor creates through
	 *            {@code ActorContext.vThreadFactory()} for I/O or for work that
	 *            does not need its state. {@code false} keeps every actor on its
	 *            home carrier, with its state in that core's cache, and leaves
	 *            stealing to balance the rest. Moot without stealing
	 */
	record Affine(int count, boolean pinnedToCpus, WorkStealing workStealing,
			boolean actorsStealable) implements Carriers {

		public Affine {
			if (count < 1) {
				throw new IllegalArgumentException("carrier count must be at least 1, not " + count);
			}
			if (workStealing == null) {
				throw new NullPointerException("workStealing");
			}
		}

		/** The same pool, each carrier pinned to one CPU. */
		public Affine pinned() {
			return new Affine(count, true, workStealing, actorsStealable);
		}

		/** The same pool, with {@code workStealing}. */
		public Affine workStealing(WorkStealing workStealing) {
			return new Affine(count, pinnedToCpus, workStealing, actorsStealable);
		}

		/**
		 * The same pool, its actor loops never stolen: stealing, if enabled, takes only
		 * the other virtual threads.
		 */
		public Affine actorsStayHome() {
			return new Affine(count, pinnedToCpus, workStealing, false);
		}
	}

	/**
	 * The JDK builtin scheduler with a fixed parallelism.
	 *
	 * @param parallelism
	 *            the builtin pool's parallelism: how many carriers run virtual
	 *            threads at once
	 */
	record Builtin(int parallelism) implements Carriers {

		public Builtin {
			if (parallelism < 1) {
				throw new IllegalArgumentException("parallelism must be at least 1, not " + parallelism);
			}
		}
	}
}
