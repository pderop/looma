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
package io.github.pderop.looma.scheduler.mpsc.nonative;

import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Carriers;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Carriers#affine(int)} in a JVM that refuses native calls, started with
 * {@code --illegal-native-access=deny}: the system runs, and its carriers are
 * not pinned.
 */
class MpscNoNativeAccessTest {

	@Test
	void runsWithoutPinning() throws Exception {
		ActorSystem system = ActorSystem.builder().carriers(Carriers.affine(2)).build();

		assertEquals(2, system.carrierCount());
		assertEquals(OptionalInt.empty(), system.scheduler().clusterOf(0));
		CompletableFuture<OptionalInt> cpu = new CompletableFuture<>();
		system.scheduler().mailboxThreadFactory(0).newThread(() -> cpu.complete(system.scheduler().pinnedCpu()))
				.start();
		assertEquals(OptionalInt.empty(), cpu.get(5, TimeUnit.SECONDS));
		system.shutdown();
	}
}
