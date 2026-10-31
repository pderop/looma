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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import io.github.pderop.looma.Carriers;
import io.github.pderop.looma.spi.ActorScheduler;
import io.github.pderop.looma.spi.ActorSchedulerProvider;

/**
 * Publishes the JDK's MPSC scheduler (openjdk/loom pull request 227) to
 * {@code core}'s {@link ActorSchedulerProvider} discovery.
 *
 * <p>
 * The MPSC scheduler is the JDK's builtin one when
 * {@code -Djdk.virtualThreadScheduler.useMpsc=true} is set. Like any scheduler
 * property, it is read once, before the first virtual thread.
 */
public final class MpscActorSchedulerProvider implements ActorSchedulerProvider {

	static final String USE_MPSC = "jdk.virtualThreadScheduler.useMpsc";

	static final String STEAL = "jdk.virtualThreadScheduler.mpscSteal";

	static final String PARALLELISM = "jdk.virtualThreadScheduler.parallelism";

	static final String IMPL_CLASS = "jdk.virtualThreadScheduler.implClass";

	static final String COMMON_FACTORY = "java.util.concurrent.ForkJoinPool.common.threadFactory";

	/** What {@link #install(Carriers.Affine)} installed in this JVM, or null. */
	private static Carriers.Affine installed;

	/** The carriers, once found. Static because the scheduler is. */
	private static MpscPool pool;

	/**
	 * Required by {@link java.util.ServiceLoader}.
	 */
	public MpscActorSchedulerProvider() {
	}

	/** Whether the JDK runs the MPSC scheduler. Never throws. */
	@Override
	public boolean isAvailable() {
		try {
			return Boolean.getBoolean(USE_MPSC) && MpscPool.running();
		} catch (Throwable e) {
			return false;
		}
	}

	/**
	 * A system found by discovery, in a JVM where the flags chose the MPSC
	 * scheduler. Its carriers are not pinned by Looma.
	 */
	@Override
	public ActorScheduler create() {
		synchronized (MpscActorSchedulerProvider.class) {
			if (pool == null) {
				pool = MpscPool.probe(Integer.getInteger(PARALLELISM, Runtime.getRuntime().availableProcessors()),
						false);
			}
			return new MpscActorScheduler(pool);
		}
	}

	@Override
	public ActorScheduler create(Carriers.Affine carriers) {
		synchronized (MpscActorSchedulerProvider.class) {
			return new MpscActorScheduler(pool);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * Sets the properties, then starts the JDK's pollers, then checks that the
	 * carriers are the MPSC ones and that there are as many as asked. Pins each
	 * carrier to one CPU last.
	 *
	 * <p>
	 * A new thread runs only on the CPUs of the thread that starts it. The JDK
	 * starts some of its threads from the thread that first needs them: the
	 * pollers, the timers of virtual threads, the timer of the common pool. So they
	 * are started here, before any carrier is pinned: otherwise they would stay on
	 * one carrier's CPU. The common pool starts its workers all the time: its
	 * factory gives them back every CPU.
	 */
	@Override
	public boolean install(Carriers.Affine carriers) {
		synchronized (MpscActorSchedulerProvider.class) {
			if (installed != null) {
				if (!installed.equals(carriers)) {
					throw new IllegalStateException("this JVM's MPSC pool is already installed as " + installed
							+ " and cannot also be " + carriers);
				}
				return true;
			}
			if (System.getProperty(IMPL_CLASS) != null) {
				throw new IllegalStateException("-D" + IMPL_CLASS + " is set, so this JVM runs another scheduler:"
						+ " cannot install " + carriers);
			}
			if (System.getProperty(USE_MPSC) != null) {
				throw new IllegalStateException("-D" + USE_MPSC + " is on the command line, so the scheduler is"
						+ " configured there: cannot also configure it as " + carriers
						+ ". Drop the -D flags or the carriers(...) call");
			}
			List<String> added = set(carriers);
			try {
				startJdkPollers();
				if (!MpscPool.running()) {
					throw new IllegalStateException("this JVM does not run the MPSC scheduler: a JDK without it, or a"
							+ " virtual thread started before " + carriers + " could be installed");
				}
			} catch (IllegalStateException e) {
				added.forEach(System::clearProperty);
				throw e;
			} catch (Throwable e) {
				added.forEach(System::clearProperty);
				throw new IllegalStateException("could not install " + carriers + ": " + e, e);
			}
			// From here on the JDK runs this scheduler for good: the properties stay.
			AllCpus.save();
			startCommonPoolTimer();
			pool = MpscPool.probe(carriers.count(), true);
			checkCommonPool();
			installed = carriers;
			return true;
		}
	}

	/**
	 * Sets the properties {@code carriers} stands for, after checking that none is
	 * set to another value. Returns those it added.
	 */
	private static List<String> set(Carriers.Affine carriers) {
		Map<String, String> properties = new LinkedHashMap<>();
		properties.put(PARALLELISM, Integer.toString(carriers.count()));
		properties.put(STEAL, "true");
		properties.put(USE_MPSC, "true");
		properties.forEach((key, value) -> {
			String current = System.getProperty(key);
			if (current != null && !current.equals(value)) {
				throw new IllegalStateException("-D" + key + "=" + current + " is already set, and conflicts with "
						+ carriers + ", which sets it to " + value);
			}
		});
		// The application may have its own factory for the common pool: it stays.
		properties.put(COMMON_FACTORY, AllCpus.class.getName());
		List<String> added = new ArrayList<>();
		properties.forEach((key, value) -> {
			if (System.getProperty(key) == null) {
				System.setProperty(key, value);
				added.add(key);
			}
		});
		return added;
	}

	/**
	 * Starts the JDK's poller threads from the calling thread, which may still run
	 * on every CPU. Loading the class {@code sun.nio.ch.Poller} starts them.
	 *
	 * <p>
	 * It must run before the carriers are pinned. Otherwise the first carrier that
	 * reads a socket starts them, and they keep only that carrier's CPU.
	 *
	 * <p>
	 * It works in every poller mode, because it runs outside the JDK's own set-up
	 * of virtual threads. When it loads {@code Poller}, virtual threads can already
	 * start, and some modes need them.
	 */
	private static void startJdkPollers() {
		try {
			Class.forName("sun.nio.ch.Poller");
		} catch (ClassNotFoundException e) {
			// Nothing to do: the JDK starts its pollers later, as usual.
		}
	}

	/**
	 * Starts the thread of the common pool that runs the timeouts of
	 * {@link CompletableFuture}, from the calling thread. The first timeout starts
	 * it, and it never stops. The JDK makes it without the pool's factory.
	 */
	private static void startCommonPoolTimer() {
		new CompletableFuture<Void>().orTimeout(1, TimeUnit.NANOSECONDS);
	}

	/**
	 * Warns if the common pool does not use {@link AllCpus}, while the carriers are
	 * pinned and the application did not give the pool a factory of its own. The
	 * common pool reads its factory once: it ran before this install.
	 */
	private static void checkCommonPool() {
		if (pool.cluster(0) >= 0 && AllCpus.class.getName().equals(System.getProperty(COMMON_FACTORY))
				&& !(ForkJoinPool.commonPool().getFactory() instanceof AllCpus)) {
			System.err.println("WARNING: the common pool started before the MPSC scheduler was installed. Its"
					+ " threads started from an actor run on one CPU only.");
		}
	}
}
