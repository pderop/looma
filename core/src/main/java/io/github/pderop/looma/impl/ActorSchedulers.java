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
package io.github.pderop.looma.impl;

import java.util.Iterator;
import java.util.Optional;
import java.util.ServiceLoader;

import io.github.pderop.looma.spi.ActorSchedulerProvider;

/**
 * Discovers the one {@link ActorSchedulerProvider} whose scheduler is actually
 * installed in this JVM, once per process.
 *
 * <p>
 * Discovery uses {@link ServiceLoader#load(Class, ClassLoader)} with this
 * class's own defining classloader — deliberately <b>not</b> the thread context
 * classloader overload. The JDK itself resolves
 * {@code io.netty.loom.scheduler.NettyScheduler} through the <i>system</i>
 * classloader while running {@code VirtualThread.<clinit>}, and every
 * deployment recipe this project documents is a flat-classpath one; the TCCL
 * overload would silently miss a provider on setups where the two classloaders
 * differ.
 *
 * <p>
 * <b>A provider that fails to even load is caught and skipped, not just one
 * that reports unavailable.</b> A scheduler module compiled with
 * {@code --enable-preview} loaded on a stock JDK throws
 * {@link UnsupportedClassVersionError} — a {@link LinkageError} — the moment
 * {@link ServiceLoader} tries to resolve its provider class.
 * {@code ServiceLoader} only ever wraps {@link ClassNotFoundException} into
 * {@link java.util.ServiceConfigurationError}; a {@link LinkageError}
 * propagates raw out of the iterator's {@code hasNext()}/{@code next()}, not
 * out of {@link ActorSchedulerProvider#isAvailable()}. That is why the
 * {@code try/catch (Throwable)} below wraps the iterator advance itself:
 * catching only around {@code isAvailable()} would never even see this failure.
 *
 * <p>
 * Skipped means skipped: the walk continues to the next provider rather than
 * ending, so one unloadable provider cannot hide a working one that happens to
 * come after it in the {@link ServiceLoader} order. Falling back to
 * {@link JdkActorScheduler} when nothing at all is usable is the whole point of
 * this class existing.
 */
final class ActorSchedulers {

	private static volatile Optional<ActorSchedulerProvider> discovered;

	private ActorSchedulers() {
	}

	/**
	 * Returns the first {@link ActorSchedulerProvider} found on the classpath whose
	 * {@link ActorSchedulerProvider#isAvailable()} reports {@code true}, or
	 * {@link Optional#empty()} if the classpath holds none that is both loadable
	 * and available. A provider that fails to load, or that throws from
	 * {@code isAvailable()}, is logged and passed over; the walk continues with the
	 * next one.
	 *
	 * <p>
	 * The result is computed once and cached in a {@code static volatile} field:
	 * which scheduler is installed cannot change over the life of a JVM process, so
	 * repeated discovery would only repeat the same {@link ServiceLoader} walk for
	 * the same answer.
	 */
	static Optional<ActorSchedulerProvider> discover() {
		Optional<ActorSchedulerProvider> result = discovered;
		if (result == null) {
			result = discoverUncached();
			discovered = result;
		}
		return result;
	}

	/**
	 * Ceiling on how many provider failures this walk tolerates before giving up.
	 *
	 * <p>
	 * Skipping a failure means going round the loop again, and that only terminates
	 * because {@link ServiceLoader}'s iterator has already consumed the offending
	 * entry by the time it throws — the next {@code hasNext()} resumes at the
	 * following one. This bound is what keeps a hypothetical iterator that does
	 * <em>not</em> advance from spinning forever inside
	 * {@code VirtualThread.<clinit>}-adjacent initialisation, where an infinite
	 * loop would be close to undiagnosable. No real classpath comes near it.
	 */
	private static final int MAX_PROVIDER_FAILURES = 64;

	private static Optional<ActorSchedulerProvider> discoverUncached() {
		Iterator<ActorSchedulerProvider> providers = ServiceLoader
				.load(ActorSchedulerProvider.class, ActorSchedulerProvider.class.getClassLoader()).iterator();
		int failures = 0;
		while (failures <= MAX_PROVIDER_FAILURES) {
			ActorSchedulerProvider provider;
			try {
				if (!providers.hasNext()) {
					return Optional.empty();
				}
				provider = providers.next();
			} catch (Throwable e) {
				failures++;
				logAndSkip(e);
				continue;
			}
			try {
				if (provider.isAvailable()) {
					return Optional.of(provider);
				}
			} catch (Throwable e) {
				failures++;
				logAndSkip(e);
			}
		}
		System.err.println("[looma] giving up on ActorSchedulerProvider discovery after " + MAX_PROVIDER_FAILURES
				+ " failures; falling back to the JDK virtual-thread scheduler");
		return Optional.empty();
	}

	private static void logAndSkip(Throwable e) {
		System.err.println(
				"[looma] skipping an ActorSchedulerProvider that failed to load or to report " + "availability: " + e);
	}
}
