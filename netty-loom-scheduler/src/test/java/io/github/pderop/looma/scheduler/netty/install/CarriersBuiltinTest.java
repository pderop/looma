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
package io.github.pderop.looma.scheduler.netty.install;

import org.junit.jupiter.api.Test;

import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Carriers;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ActorSystem.Builder#carriers(Carriers)} with the JDK builtin, twice in
 * one JVM: the same size is the same pool, another size is refused and the
 * message names the earlier system rather than a flag. A class of its own: it
 * needs a JVM whose scheduler is the builtin.
 */
class CarriersBuiltinTest {

	@Test
	void aSecondBuiltinOfAnotherSizeIsRefused() {
		ActorSystem.builder().carriers(Carriers.builtin(2)).build().shutdown();
		ActorSystem.builder().carriers(Carriers.builtin(2)).build().shutdown();

		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> ActorSystem.builder().carriers(Carriers.builtin(3)).build());

		assertTrue(e.getMessage().contains("already installed"), e.getMessage());
	}
}
