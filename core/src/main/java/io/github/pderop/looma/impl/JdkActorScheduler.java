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

import java.util.OptionalInt;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import io.github.pderop.looma.spi.ActorScheduler;
import io.github.pderop.looma.spi.ActorSchedulerProvider;

/**
 * The {@link ActorScheduler} used when no {@link ActorSchedulerProvider} is
 * found on the classpath, none reports itself available, or a provider failed
 * to even load — in practice, on a stock JDK, or on a Loom JDK that was never
 * started with {@code -Djdk.virtualThreadScheduler.implClass}.
 *
 * <p>
 * It owns nothing: no pool, no carrier loops. Each actor is one virtual thread
 * created from {@link #vThreadFactory(int)}, which is a plain
 * {@link Thread#ofVirtual() Thread.ofVirtual()} factory. There is no placement
 * to vary, so {@link #carrierCount()} is {@code 1} and every index this
 * scheduler is asked for resolves to that one factory. Continuations resume on
 * whichever worker the JDK's default virtual-thread scheduler picks — that is
 * the baseline a carrier-affine {@link ActorScheduler} is measured against.
 */
final class JdkActorScheduler implements ActorScheduler {

	private final ThreadFactory vThreadFactory = Thread.ofVirtual().factory();

	@Override
	public int carrierCount() {
		return 1;
	}

	@Override
	public ThreadFactory vThreadFactory(int index) {
		return vThreadFactory;
	}

	/**
	 * Always {@code -1}: a JDK virtual thread belongs to no carrier.
	 */
	@Override
	public int currentCarrierId() {
		return -1;
	}

	@Override
	public OptionalInt pinnedCpu() {
		return OptionalInt.empty();
	}

	@Override
	public OptionalInt clusterOf(int index) {
		return OptionalInt.empty();
	}

	@Override
	public void close() {
		// Nothing to release: this scheduler owns no threads.
	}

	@Override
	public boolean awaitClosed(long timeout, TimeUnit unit) {
		return true;
	}
}
