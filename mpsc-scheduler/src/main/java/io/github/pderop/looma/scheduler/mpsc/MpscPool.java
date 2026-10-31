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

import java.util.concurrent.atomic.AtomicReference;

/**
 * The JDK's MPSC carriers, as Looma sees them: one {@link Thread.Affinity} per
 * carrier, and the CPU topology when the carriers are pinned.
 *
 * <p>
 * An affinity does not say which carrier it lands on. So the pool starts one
 * virtual thread per affinity and reads the name of its carrier.
 */
final class MpscPool {

	/** The name the JDK gives each MPSC carrier, followed by its id. */
	static final String CARRIER_PREFIX = "mpsc-carrier-";

	/** Carrier id to the affinity that runs on it. */
	private final Thread.Affinity[] homes;

	/** The CPUs the carriers are pinned to, or null if they float. */
	private final LinuxCpus cpus;

	private MpscPool(Thread.Affinity[] homes, LinuxCpus cpus) {
		this.homes = homes;
		this.cpus = cpus;
	}

	/**
	 * Finds the {@code count} carriers of the MPSC scheduler, and pins each one to
	 * a CPU if {@code pinned}.
	 *
	 * @throws IllegalStateException
	 *             if the scheduler in force is not the MPSC one, or has another
	 *             number of carriers
	 */
	static MpscPool probe(int count, boolean pinned) {
		Thread.Affinity.Group group = Thread.Affinity.newGroup();
		Thread.Affinity[] homes = new Thread.Affinity[count];
		for (int i = 0; i < count; i++) {
			Thread.Affinity affinity = group.newAffinity();
			int id = carrierOf(affinity);
			if (id < 0 || id >= count || homes[id] != null) {
				throw new IllegalStateException("the MPSC scheduler does not have " + count + " carriers: affinity " + i
						+ " ran on carrier " + id);
			}
			homes[id] = affinity;
		}
		LinuxCpus cpus = null;
		if (pinned) {
			LinuxCpus found = LinuxCpus.discover();
			for (int id = 0; id < count; id++) {
				int carrier = id;
				// Mounted on the carrier: sched_setaffinity(0) pins that carrier's own thread.
				run(homes[id], () -> found.pin(carrier));
			}
			cpus = found;
		}
		return new MpscPool(homes, cpus);
	}

	/** Whether the current scheduler is the MPSC one. */
	static boolean running() {
		try {
			return carrierOf(null) >= 0;
		} catch (RuntimeException e) {
			return false;
		}
	}

	int count() {
		return homes.length;
	}

	Thread.Affinity home(int carrier) {
		return homes[carrier];
	}

	/** The last-level-cache cluster of a carrier, or -1 if the carriers float. */
	int cluster(int carrier) {
		return cpus == null ? -1 : cpus.cluster(carrier);
	}

	/**
	 * The id of the MPSC carrier the current thread runs on, or -1. Read from
	 * {@code toString()}, which ends with {@code @} and the carrier's name.
	 */
	static int currentCarrier() {
		Thread current = Thread.currentThread();
		if (!current.isVirtual()) {
			return -1;
		}
		String description = current.toString();
		int at = description.lastIndexOf('@');
		if (at < 0 || !description.startsWith(CARRIER_PREFIX, at + 1)) {
			return -1;
		}
		try {
			return Integer.parseInt(description.substring(at + 1 + CARRIER_PREFIX.length()));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** The carrier a virtual thread with {@code affinity} runs on, or -1. */
	private static int carrierOf(Thread.Affinity affinity) {
		int[] id = new int[1];
		run(affinity, () -> id[0] = currentCarrier());
		return id[0];
	}

	/** Runs {@code task} on a virtual thread with {@code affinity}, and waits. */
	private static void run(Thread.Affinity affinity, Runnable task) {
		Thread.Builder.OfVirtual builder = Thread.ofVirtual();
		if (affinity != null) {
			builder.affinity(affinity);
		}
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread thread = builder.start(() -> {
			try {
				task.run();
			} catch (Throwable e) {
				failure.set(e);
			}
		});
		try {
			thread.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while probing the MPSC carriers", e);
		}
		if (failure.get() != null) {
			throw new IllegalStateException("probing the MPSC carriers failed", failure.get());
		}
	}
}
