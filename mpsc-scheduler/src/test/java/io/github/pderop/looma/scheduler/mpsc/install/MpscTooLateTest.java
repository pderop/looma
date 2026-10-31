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
 * {@link Carriers#affine(int)} asked for after a virtual thread already made
 * the JDK settle on its default scheduler: refused, not run on the default.
 */
class MpscTooLateTest {

	@Test
	void isRefusedOnceAVirtualThreadHasRun() throws InterruptedException {
		Thread.ofVirtual().start(() -> {
		}).join();

		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> ActorSystem.builder().carriers(Carriers.affine(2)).build());

		assertTrue(e.getMessage().contains("before"), e.getMessage());
		// The properties it set are removed again.
		assertNull(System.getProperty("jdk.virtualThreadScheduler.useMpsc"));
		assertNull(System.getProperty("jdk.virtualThreadScheduler.mpscSteal"));
		assertNull(System.getProperty("jdk.virtualThreadScheduler.parallelism"));
	}
}
