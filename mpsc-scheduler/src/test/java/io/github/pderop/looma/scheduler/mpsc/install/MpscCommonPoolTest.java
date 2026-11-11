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

import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Carriers;

import static io.github.pderop.looma.scheduler.mpsc.install.LinuxThreads.SELF;
import static io.github.pderop.looma.scheduler.mpsc.install.LinuxThreads.TASKS;
import static io.github.pderop.looma.scheduler.mpsc.install.LinuxThreads.call;
import static io.github.pderop.looma.scheduler.mpsc.install.LinuxThreads.cpusAllowed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The workers of the common pool run on every CPU, even when an actor makes the
 * pool start them. Without the pool's factory, they would keep the one CPU of
 * the actor's carrier.
 *
 * <p>
 * A class of its own, so a JVM of its own: no other test has started a worker
 * from another thread before.
 */
class MpscCommonPoolTest {

	@Test
	void theCommonPoolKeepsEveryCpu() throws Exception {
		assumeTrue(Files.isDirectory(TASKS), "not Linux");
		ActorSystem system = ActorSystem.builder().carriers(Carriers.mpsc(3)).build();
		String all = cpusAllowed(SELF);
		int workers = ForkJoinPool.getCommonPoolParallelism();

		Set<String> seen = call(system.scheduler(), 0, () -> {
			// Each task waits for all the others: the pool must run them on as many
			// workers.
			CountDownLatch together = new CountDownLatch(workers);
			List<CompletableFuture<String>> tasks = IntStream.range(0, workers)
					.mapToObj(i -> CompletableFuture.supplyAsync(() -> {
						together.countDown();
						await(together);
						return cpusAllowed(SELF);
					}))
					.toList();
			return tasks.stream().map(CompletableFuture::join).collect(Collectors.toSet());
		});

		assertEquals(Set.of(all), seen);
		system.shutdown();
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("the pool did not run every task at once");
			}
		} catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}
}
