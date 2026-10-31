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
 * Which virtual-thread scheduler carries a process's actors, and how many
 * carriers it has, passed to {@link ActorSystem.Builder#carriers(Carriers)}
 * instead of JVM flags.
 *
 * <ul>
 * <li>{@link #affine(int)}: each actor stays on one carrier. Today the JDK's
 * MPSC scheduler (openjdk/loom pull request 227).
 * <li>{@link #builtin(int)}: the JDK's default scheduler, today a ForkJoinPool.
 * </ul>
 *
 * <p>
 * With {@code affine}, each actor stays on one carrier for life, each carrier
 * is pinned to one CPU (on Linux), and an idle carrier may steal only the
 * threads that actors offload. There are no options: this is the setup the
 * benchmark found the fastest.
 *
 * <p>
 * <b>A scheduler is chosen once per JVM, before its first virtual thread.</b>
 * The JDK reads its scheduler configuration once, while it initialises
 * {@code java.lang.VirtualThread}. So {@code build()} must run before anything
 * in the process has started a virtual thread, and every {@code ActorSystem}
 * built afterwards must ask for the same pool. See
 * {@link ActorSystem.Builder#carriers(Carriers)}.
 *
 * <p>
 * Without {@code carriers(...)}, a process gets the scheduler its JVM flags
 * installed.
 */
public sealed interface Carriers {

	/**
	 * A pool of {@code count} carriers where each actor stays on one carrier. Today
	 * the JDK's MPSC scheduler: each carrier is pinned to one CPU, each actor has
	 * the {@code Thread.Affinity} of its carrier, so it never moves, and an idle
	 * carrier may steal the other threads, from any carrier. Needs the JDK of
	 * openjdk/loom pull request 227.
	 *
	 * @throws IllegalArgumentException
	 *             if {@code count} is less than 1
	 */
	static Affine affine(int count) {
		return new Affine(count);
	}

	/**
	 * The JDK's default scheduler, with its parallelism set to {@code parallelism}.
	 * Its maximum pool size keeps the JDK's default, so the JDK can still add a
	 * thread when a virtual thread blocks its carrier. Never the MPSC scheduler.
	 *
	 * @throws IllegalArgumentException
	 *             if {@code parallelism} is less than 1
	 */
	static Builtin builtin(int parallelism) {
		return new Builtin(parallelism);
	}

	/**
	 * A pool where each actor stays on one carrier: see {@link #affine(int)}.
	 *
	 * @param count
	 *            carriers in the pool
	 */
	record Affine(int count) implements Carriers {

		public Affine {
			if (count < 1) {
				throw new IllegalArgumentException("carrier count must be at least 1, not " + count);
			}
		}
	}

	/**
	 * The JDK's default scheduler with a fixed parallelism: see
	 * {@link #builtin(int)}.
	 *
	 * @param parallelism
	 *            how many carriers run virtual threads at once
	 */
	record Builtin(int parallelism) implements Carriers {

		public Builtin {
			if (parallelism < 1) {
				throw new IllegalArgumentException("parallelism must be at least 1, not " + parallelism);
			}
		}
	}
}
