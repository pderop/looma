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

import org.junit.jupiter.api.Test;

import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Carriers;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ActorSystem.Builder#carriers(Carriers)} in a JVM whose command line
 * already installs the scheduler, as every execution running this package does:
 * refused, since the pool may already be running on the command line's values
 * and nothing readable says which.
 */
class CarriersCommandLineTest {

	@Test
	void isRefusedWhenTheCommandLineAlreadyInstallsTheScheduler() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> ActorSystem.builder().carriers(Carriers.affine(2)).build());

		assertTrue(e.getMessage().contains("command line"), e.getMessage());
	}

	@Test
	void theBuiltinIsRefusedWhenTheCommandLineInstallsAnotherScheduler() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> ActorSystem.builder().carriers(Carriers.builtin(2)).build());

		assertTrue(e.getMessage().contains("implClass"), e.getMessage());
	}
}
