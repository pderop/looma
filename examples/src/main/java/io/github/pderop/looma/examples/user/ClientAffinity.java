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
package io.github.pderop.looma.examples.user;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pins a client thread to one CPU, away from the carrier CPUs.
 *
 * <p>
 * The script starts the JVM on the carrier CPUs only, so every JVM thread runs
 * there: carriers, GC, JIT. Each client thread then moves itself to its own
 * client CPU, with {@code sched_setaffinity}.
 *
 * <p>
 * Linux only. Elsewhere, {@link #available()} returns false and the client
 * threads are not pinned.
 */
final class ClientAffinity {

	/** The size of a CPU set: 1024 CPUs, one bit each. */
	private static final int CPU_SET_SIZE = 128;

	private static final Optional<MethodHandle> SCHED_SET_AFFINITY = Linker.nativeLinker().defaultLookup()
			.find("sched_setaffinity").map(symbol -> Linker.nativeLinker().downcallHandle(symbol, FunctionDescriptor
					.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)));

	private ClientAffinity() {
	}

	static boolean available() {
		return SCHED_SET_AFFINITY.isPresent();
	}

	/**
	 * Pins the calling thread to {@code cpu}.
	 *
	 * @throws IllegalStateException
	 *             if the kernel refuses, for example for a CPU outside the cgroup
	 */
	static void pinCurrentThread(int cpu) {
		if (cpu < 0 || cpu >= CPU_SET_SIZE * Byte.SIZE) {
			throw new IllegalArgumentException("cpu out of range: " + cpu);
		}
		MethodHandle setAffinity = SCHED_SET_AFFINITY
				.orElseThrow(() -> new IllegalStateException("sched_setaffinity is not available here"));
		int rc;
		try (Arena arena = Arena.ofConfined()) {
			var cpuSet = arena.allocate(CPU_SET_SIZE);
			cpuSet.set(ValueLayout.JAVA_BYTE, cpu / Byte.SIZE, (byte) (1 << (cpu % Byte.SIZE)));
			rc = (int) setAffinity.invokeExact(0, (long) CPU_SET_SIZE, cpuSet);
		} catch (Throwable e) {
			throw new IllegalStateException("sched_setaffinity failed for CPU " + cpu, e);
		}
		if (rc != 0) {
			throw new IllegalStateException("sched_setaffinity refused CPU " + cpu + " (rc=" + rc + ")");
		}
	}

	/** Parses a CPU list like {@code "8-11,14"}, as taskset writes it. */
	static List<Integer> parseCpuList(String list) {
		List<Integer> cpus = new ArrayList<>();
		for (String part : list.split(",")) {
			String range = part.strip();
			if (range.isEmpty()) {
				continue;
			}
			int dash = range.indexOf('-');
			int from = Integer.parseInt(dash < 0 ? range : range.substring(0, dash));
			int to = dash < 0 ? from : Integer.parseInt(range.substring(dash + 1));
			for (int cpu = from; cpu <= to; cpu++) {
				cpus.add(cpu);
			}
		}
		return cpus;
	}
}
