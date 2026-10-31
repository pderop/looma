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

import java.lang.reflect.Method;
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

	static final String MAX_POOL_SIZE = "jdk.virtualThreadScheduler.maxPoolSize";

	static final String IMPL_CLASS = "jdk.virtualThreadScheduler.implClass";

	static final String COMMON_FACTORY = "java.util.concurrent.ForkJoinPool.common.threadFactory";

	/** What {@link #install(Carriers.Affine)} installed in this JVM, or null. */
	private static Carriers.Affine installed;

	/** The carriers, once found. Static because the scheduler is. */
	private static MpscPool pool;

	/**
	 * Why an install failed after the JDK started the scheduler, or null. Nothing
	 * can be undone then, so every later install fails the same way.
	 */
	private static IllegalStateException failure;

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
				// As the JDK counts its carriers.
				int count = Integer.getInteger(PARALLELISM, Runtime.getRuntime().availableProcessors());
				Integer max = Integer.getInteger(MAX_POOL_SIZE);
				pool = MpscPool.probe(max == null ? count : Math.min(count, max), false);
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
	 * scheduler is the MPSC one, and only then sets the factory of the common pool.
	 * Then finds the carriers, checks that there are as many as asked, and pins
	 * each one to one CPU last. If a step fails once the JDK runs the scheduler,
	 * nothing can be undone: this install, and every later one, throws.
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
			if (failure != null) {
				throw new IllegalStateException("an earlier install of " + carriers + " failed in this JVM, after the"
						+ " JDK started the scheduler: " + failure.getMessage(), failure);
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
				// Only now: a failed install must not leave its factory in the common pool.
				if (System.getProperty(COMMON_FACTORY) == null) {
					System.setProperty(COMMON_FACTORY, AllCpus.class.getName());
					added.add(COMMON_FACTORY);
				}
			} catch (IllegalStateException e) {
				added.forEach(System::clearProperty);
				throw e;
			} catch (Throwable e) {
				added.forEach(System::clearProperty);
				throw new IllegalStateException("could not install " + carriers + ": " + e, e);
			}
			// From here on the JDK runs this scheduler for good: the properties stay.
			try {
				AllCpus.save();
				startCommonPoolTimer();
				boolean commonPool = adoptCommonPool();
				pool = MpscPool.probe(carriers.count(), true);
				if (!commonPool && pool.cluster(0) >= 0) {
					System.err.println("WARNING: the common pool does not use " + AllCpus.class.getName() + ": it"
							+ " started before the MPSC scheduler was installed, or the system class loader cannot"
							+ " load looma-mpsc-scheduler. Its threads started from an actor run on one CPU only.");
				}
			} catch (RuntimeException e) {
				failure = e instanceof IllegalStateException ise
						? ise
						: new IllegalStateException("could not install " + carriers + ": " + e, e);
				throw failure;
			}
			installed = carriers;
			return true;
		}
	}

	/**
	 * Sets the properties {@code carriers} stands for, after checking that none is
	 * set to another value, and that no {@code maxPoolSize} caps the carriers.
	 * Returns those it added.
	 */
	private static List<String> set(Carriers.Affine carriers) {
		String max = System.getProperty(MAX_POOL_SIZE);
		if (max != null && !(max.matches("[0-9]{1,9}") && Integer.parseInt(max) >= carriers.count())) {
			throw new IllegalStateException("-D" + MAX_POOL_SIZE + "=" + max + " is set: the JDK would not start the "
					+ carriers.count() + " carriers of " + carriers);
		}
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
	 * Whether the workers of the common pool take back the saved CPUs: the pool
	 * uses {@link AllCpus}, or the application gave it a factory of its own, which
	 * is its choice. The common pool reads its factory once, so it may have started
	 * before this install. And the JDK loads the factory with the system class
	 * loader: under another class loader, the pool has its own copy of
	 * {@link AllCpus}, which needs the CPUs too.
	 */
	private static boolean adoptCommonPool() {
		if (!AllCpus.class.getName().equals(System.getProperty(COMMON_FACTORY))) {
			return true;
		}
		ForkJoinPool.ForkJoinWorkerThreadFactory factory = ForkJoinPool.commonPool().getFactory();
		if (factory instanceof AllCpus) {
			return true;
		}
		if (!factory.getClass().getName().equals(AllCpus.class.getName())) {
			return false;
		}
		try {
			Method save = factory.getClass().getDeclaredMethod("save");
			save.setAccessible(true);
			save.invoke(null);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}
}
