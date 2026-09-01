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
package io.github.pderop.looma.scheduler.netty;

import io.github.pderop.looma.spi.ActorScheduler;
import io.github.pderop.looma.spi.ActorSchedulerProvider;
import io.netty.loom.scheduler.EventLoopSchedulerGroup;
import io.netty.loom.scheduler.NettyScheduler;

/**
 * Publishes the carrier-affine scheduler of
 * {@code franz1981/Netty-VirtualThread-Scheduler} to {@code core}'s
 * {@link ActorSchedulerProvider} discovery, registered under
 * {@code META-INF/services/io.github.pderop.looma.spi.ActorSchedulerProvider}.
 *
 * <p>
 * This class is the <b>only</b> compile-time link between {@code looma} and
 * {@code io.netty.loom.*}; nothing in {@code core}, {@code examples} or
 * {@code benchmarks} imports either package. That is what lets those three
 * modules compile at {@code release=25} without {@code --enable-preview} while
 * this one is built on the Loom JDK.
 */
public final class NettyActorSchedulerProvider implements ActorSchedulerProvider {

	/**
	 * Required by {@link java.util.ServiceLoader}.
	 */
	public NettyActorSchedulerProvider() {
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * {@link NettyScheduler#isAvailable()} answers the only question that matters:
	 * whether the JDK actually resolved
	 * {@code jdk.virtualThreadScheduler.implClass} to it. Having this jar on the
	 * classpath is not the same as having the scheduler installed — without the
	 * flag the JDK keeps its own built-in scheduler, and reporting {@code true}
	 * here would hand every actor a carrier pool that nothing else in the JVM uses.
	 */
	@Override
	public boolean isAvailable() {
		try {
			return NettyScheduler.isAvailable();
		} catch (Throwable e) {
			return false;
		}
	}

	@Override
	public ActorScheduler create() {
		return new NettyActorScheduler(EventLoopSchedulerGroup.instance());
	}
}
