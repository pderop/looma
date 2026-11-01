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
package io.github.pderop.looma.scheduler.mpsc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/**
 * The CPUs of this process, and the last-level cache each one shares.
 *
 * <p>
 * Carrier {@code i} goes to the {@code i}-th CPU the process may run on. The
 * calls go through {@code sched_setaffinity} and {@code sched_getaffinity}. On
 * a system without them, or when the JVM refuses native calls, nothing is
 * pinned, and {@link #pin} returns false.
 */
final class LinuxCpus {

	/** The bytes of a {@code cpu_set_t}: 1024 CPUs. */
	private static final int CPU_SET_SIZE = 128;

	/** Whether the warning about native access was printed. */
	private static boolean warned;

	private static final MethodHandle SCHED_SET_AFFINITY = lookup("sched_setaffinity");

	private static final MethodHandle SCHED_GET_AFFINITY = lookup("sched_getaffinity");

	/** The CPUs the process may run on, in order. */
	private final int[] cpus;

	/** The cluster of each of those CPUs: CPUs that share a cache have the same. */
	private final int[] clusters;

	private LinuxCpus(int[] cpus) {
		this.cpus = cpus;
		this.clusters = new int[cpus.length];
		Map<String, Integer> caches = new HashMap<>();
		for (int i = 0; i < cpus.length; i++) {
			clusters[i] = caches.computeIfAbsent(lastLevelCache(cpus[i]), key -> caches.size());
		}
	}

	/**
	 * The CPUs the calling thread may run on. If they cannot be read, the first
	 * {@code availableProcessors()} CPUs.
	 */
	static LinuxCpus discover() {
		long[] mask = affinity();
		if (mask == null) {
			int[] all = new int[Runtime.getRuntime().availableProcessors()];
			for (int i = 0; i < all.length; i++) {
				all[i] = i;
			}
			return new LinuxCpus(all);
		}
		return new LinuxCpus(BitSet.valueOf(mask).stream().toArray());
	}

	/**
	 * Pins the calling thread to the CPU of carrier {@code carrier}. Returns
	 * whether it did.
	 */
	boolean pin(int carrier) {
		if (SCHED_SET_AFFINITY == null) {
			// No such call here, as on macOS, or no native access: nothing can be pinned.
			return false;
		}
		if (carrier >= cpus.length) {
			System.err.println("WARNING: mpsc-carrier-" + carrier + " has no CPU of its own (" + cpus.length
					+ " CPUs). It runs on all of them.");
			return false;
		}
		BitSet cpu = new BitSet();
		cpu.set(cpus[carrier]);
		if (!setAffinity(cpu)) {
			System.err.println("WARNING: sched_setaffinity failed. mpsc-carrier-" + carrier + " is not pinned.");
			return false;
		}
		return true;
	}

	/**
	 * The cluster of carrier {@code carrier}, or -1 if it has no CPU of its own.
	 */
	int cluster(int carrier) {
		return carrier < cpus.length ? clusters[carrier] : -1;
	}

	/** The CPUs the calling thread may run on, or null if unknown. */
	static BitSet callerCpus() {
		long[] mask = affinity();
		return mask == null ? null : BitSet.valueOf(mask);
	}

	/** The one CPU the calling thread is pinned to, or -1. */
	static int pinnedCpu() {
		long[] mask = affinity();
		if (mask == null) {
			return -1;
		}
		BitSet cpus = BitSet.valueOf(mask);
		return cpus.cardinality() == 1 ? cpus.nextSetBit(0) : -1;
	}

	/** The CPUs the calling thread may run on, or null. */
	private static long[] affinity() {
		if (SCHED_GET_AFFINITY == null) {
			return null;
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment set = arena.allocate(CPU_SET_SIZE);
			int rc = (int) SCHED_GET_AFFINITY.invoke(0, (long) CPU_SET_SIZE, set);
			return rc < 0 ? null : set.toArray(ValueLayout.JAVA_LONG);
		} catch (Throwable e) {
			return null;
		}
	}

	/**
	 * Lets the calling thread run on {@code cpus} only. Returns whether it did.
	 */
	static boolean setAffinity(BitSet cpus) {
		if (SCHED_SET_AFFINITY == null) {
			return false;
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment set = arena.allocate(CPU_SET_SIZE);
			long[] words = cpus.toLongArray();
			MemorySegment.copy(words, 0, set, ValueLayout.JAVA_LONG, 0,
					Math.min(words.length, CPU_SET_SIZE / Long.BYTES));
			return (int) SCHED_SET_AFFINITY.invoke(0, (long) CPU_SET_SIZE, set) == 0;
		} catch (RuntimeException e) {
			throw e;
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * The CPUs that share the last-level cache of {@code cpu}, as Linux lists them.
	 */
	private static String lastLevelCache(int cpu) {
		String shared = String.valueOf(cpu);
		for (int i = 0;; i++) {
			Path index = Path.of("/sys/devices/system/cpu/cpu" + cpu + "/cache/index" + i);
			if (!Files.exists(index)) {
				return shared;
			}
			if (Integer.parseInt(read(index.resolve("level"), "0")) >= 3) {
				shared = read(index.resolve("shared_cpu_list"), shared);
			}
		}
	}

	private static String read(Path file, String fallback) {
		try {
			return Files.readString(file).trim();
		} catch (Exception e) {
			return fallback;
		}
	}

	private static MethodHandle lookup(String name) {
		Linker linker = Linker.nativeLinker();
		try {
			return linker.defaultLookup().find(name).map(symbol -> linker.downcallHandle(symbol, FunctionDescriptor
					.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)))
					.orElse(null);
		} catch (IllegalCallerException e) {
			// The JVM refuses native calls from this code.
			if (!warned) {
				warned = true;
				System.err.println("WARNING: the JVM refuses native calls from looma-mpsc-scheduler, so the carriers"
						+ " are not pinned. Start it with --enable-native-access=ALL-UNNAMED.");
			}
			return null;
		}
	}
}
