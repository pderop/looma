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

	/** What {@link #install(Carriers.Mpsc)} installed in this JVM, or null. */
	private static Carriers.Mpsc installed;

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
	public ActorScheduler create(Carriers.Mpsc carriers) {
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
	 * The JDK starts its poller threads from the thread that first needs them. A
	 * new thread runs only on the CPUs of the thread that starts it. So the pollers
	 * are started here, before any carrier is pinned: otherwise they would stay on
	 * one carrier's CPU.
	 */
	@Override
	public boolean install(Carriers.Mpsc carriers) {
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
			pool = MpscPool.probe(carriers.count(), true);
			installed = carriers;
			return true;
		}
	}

	/**
	 * Sets the properties {@code carriers} stands for, after checking that none is
	 * set to another value. Returns those it added.
	 */
	private static List<String> set(Carriers.Mpsc carriers) {
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

	private static void startJdkPollers() {
		try {
			Class.forName("sun.nio.ch.Poller");
		} catch (ClassNotFoundException e) {
			// Nothing to do: the JDK starts its pollers later, as usual.
		}
	}
}
