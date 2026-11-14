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

import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.spi.ActorScheduler;

/**
 * The JDK's default scheduler, with mailbox threads kept alive. It runs the
 * keep-alive path on any JDK.
 */
final class KeepAliveScheduler implements ActorScheduler {

	private final ActorScheduler jdk = new JdkActorScheduler();

	/** A builder of a system on this scheduler. */
	static ActorSystem.Builder builder() {
		return ActorSystem.builder().scheduler(new KeepAliveScheduler());
	}

	@Override
	public boolean keepMailboxThreadsAlive() {
		return true;
	}

	@Override
	public int carrierCount() {
		return jdk.carrierCount();
	}

	@Override
	public ThreadFactory vThreadFactory(int index) {
		return jdk.vThreadFactory(index);
	}

	@Override
	public int currentCarrierId() {
		return jdk.currentCarrierId();
	}

	@Override
	public OptionalInt pinnedCpu() {
		return jdk.pinnedCpu();
	}

	@Override
	public OptionalInt clusterOf(int index) {
		return jdk.clusterOf(index);
	}

	@Override
	public void close() {
		jdk.close();
	}

	@Override
	public boolean awaitClosed(long timeout, TimeUnit unit) throws InterruptedException {
		return jdk.awaitClosed(timeout, unit);
	}
}
