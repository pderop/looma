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
package io.github.pderop.looma.spi;

import java.util.ServiceLoader;

/**
 * A {@link ServiceLoader}-discoverable factory for one {@link ActorScheduler}.
 *
 * <p>
 * {@code core} ships no implementation of this interface: the only one in this
 * project lives in the {@code netty-loom-scheduler} module, registered under
 * {@code META-INF/services/io.github.pderop.looma.spi.ActorSchedulerProvider}.
 * A third-party scheduler is added to a process the same way — implement both
 * SPI interfaces and register the provider — without {@code core} ever
 * depending on it at compile time.
 */
public interface ActorSchedulerProvider {

	/**
	 * Returns whether this provider's scheduler is actually installed in this JVM —
	 * e.g. whether the JDK's virtual thread scheduler SPI currently resolves to it.
	 * Must not throw: a provider that cannot tell reports {@code false} rather than
	 * let discovery abort for every other provider on the classpath.
	 */
	boolean isAvailable();

	/**
	 * Creates a new {@link ActorScheduler} view over this provider's scheduler.
	 * Called only after {@link #isAvailable()} returned {@code true}, and may be
	 * called more than once — once per {@code ActorSystem} that chooses this
	 * provider.
	 */
	ActorScheduler create();
}
