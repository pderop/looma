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
package io.github.pderop.looma.scheduler.mpsc.install;

import org.junit.jupiter.api.Test;

import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Carriers;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Carriers#mpsc(int)} with a {@code maxPoolSize} below its count: the
 * JDK would start fewer carriers, so the install refuses before it sets
 * anything.
 */
class MpscMaxPoolSizeTest {

	@Test
	void isRefusedWhenMaxPoolSizeCapsTheCarriers() {
		System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", "2");

		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> ActorSystem.builder().carriers(Carriers.mpsc(3)).build());

		assertTrue(e.getMessage().contains("maxPoolSize"), e.getMessage());
		assertNull(System.getProperty("jdk.virtualThreadScheduler.useMpsc"));
	}
}
