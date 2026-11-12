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
package io.github.pderop.looma.examples.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/** When a measured window is not a measurement. */
class LoadGeneratorTest {

	/** Answers at once: the clients send on time. */
	@Test
	void clientsOnTimeAreNotFlagged() throws Exception {
		String report = measure(random -> CompletableFuture.completedFuture("ok"), 100);

		assertFalse(report.contains("clients did not keep up"), report);
	}

	/**
	 * Each send blocks its client for 5 ms, five times the time between two sends:
	 * the clients send later and later.
	 */
	@Test
	void clientsThatSendTooLateAreNotAMeasurement() throws Exception {
		String report = measure(random -> {
			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return CompletableFuture.completedFuture("ok");
		}, 1000);

		assertTrue(report.contains("*** clients did not keep up with 1,000 req/s"), report);
	}

	/** One client thread at {@code rate}, a window of 500 ms, and its report. */
	private static String measure(Function<Random, CompletableFuture<String>> send, long rate) throws Exception {
		LoadGenerator<String> load = new LoadGenerator<>(send, answer -> true, List.of());
		load.setRate(rate);
		load.start();
		PrintStream console = System.out;
		ByteArrayOutputStream report = new ByteArrayOutputStream();
		try {
			LoadGenerator.Window window = load.startMeasuring();
			long start = System.nanoTime();
			TimeUnit.MILLISECONDS.sleep(500);
			load.stopMeasuring();
			long elapsed = System.nanoTime() - start;
			load.stop();
			System.setOut(new PrintStream(report, true, StandardCharsets.UTF_8));
			load.printReport(window, rate, elapsed);
		} finally {
			System.setOut(console);
		}
		return report.toString(StandardCharsets.UTF_8);
	}
}
