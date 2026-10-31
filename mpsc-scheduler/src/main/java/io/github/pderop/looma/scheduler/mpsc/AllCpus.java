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

import java.util.BitSet;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;

/**
 * Makes threads that run on every CPU of the process, even when a pinned
 * carrier starts them.
 *
 * <p>
 * A new thread runs only on the CPUs of the thread that starts it. A thread
 * started from an actor would keep the one CPU of its carrier. The threads made
 * here first take back the CPUs of the thread that installed the scheduler.
 *
 * <p>
 * The install makes it the factory of the common pool, which starts its workers
 * from whichever thread gives it work. It is public for that reason only: the
 * JDK loads it by name.
 */
public final class AllCpus implements ForkJoinPool.ForkJoinWorkerThreadFactory {

	/** The CPUs of the thread that installed the scheduler, or null. */
	private static volatile BitSet cpus;

	/**
	 * Called by the common pool.
	 */
	public AllCpus() {
	}

	@Override
	public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
		return new Worker(pool);
	}

	/** Saves the CPUs of the calling thread, before any carrier is pinned. */
	static void save() {
		cpus = LinuxCpus.callerCpus();
	}

	/** A factory of platform threads that run on every CPU. */
	static ThreadFactory platform() {
		ThreadFactory factory = Thread.ofPlatform().factory();
		return task -> factory.newThread(() -> {
			restore();
			task.run();
		});
	}

	/** Lets the calling thread run on the saved CPUs again. */
	private static void restore() {
		BitSet saved = cpus;
		if (saved != null) {
			LinuxCpus.setAffinity(saved);
		}
	}

	/**
	 * A worker of the common pool. Like the JDK's own, it clears its thread locals
	 * after each task and has the system class loader. Unlike them, its thread
	 * group is the one of the thread that starts it.
	 */
	private static final class Worker extends ForkJoinWorkerThread {

		Worker(ForkJoinPool pool) {
			super(null, pool, false);
			setContextClassLoader(ClassLoader.getSystemClassLoader());
		}

		@Override
		protected void onStart() {
			super.onStart();
			restore();
		}
	}
}
