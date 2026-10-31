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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.github.pderop.looma.spi.ActorScheduler;

/** The threads of this process, as Linux shows them, for the install tests. */
final class LinuxThreads {

	/** Where Linux lists the threads of this process. */
	static final Path TASKS = Path.of("/proc/self/task");

	/** The calling thread, as Linux sees it: for a virtual thread, its carrier. */
	static final Path SELF = Path.of("/proc/thread-self");

	private LinuxThreads() {
	}

	/** Runs {@code task} on a thread of {@code carrier}, and waits. */
	static <T> T call(ActorScheduler scheduler, int carrier, Callable<T> task) throws Exception {
		CompletableFuture<T> result = new CompletableFuture<>();
		scheduler.actorLoopFactory(carrier).newThread(() -> {
			try {
				result.complete(task.call());
			} catch (Throwable e) {
				result.completeExceptionally(e);
			}
		}).start();
		return result.get(10, TimeUnit.SECONDS);
	}

	static Stream<Path> tasks() {
		try {
			return Files.list(TASKS).toList().stream();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static String name(Path task) {
		try {
			return Files.readString(task.resolve("comm")).trim();
		} catch (IOException e) {
			// The thread ended.
			return "";
		}
	}

	/** The line {@code Cpus_allowed_list} of a thread's status. */
	static String cpusAllowed(Path task) {
		try (Stream<String> lines = Files.lines(task.resolve("status"))) {
			return lines.filter(line -> line.startsWith("Cpus_allowed_list:")).findFirst().orElseThrow();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
