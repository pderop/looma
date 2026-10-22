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
package io.github.pderop.looma.examples.user;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

/**
 * Simulates the HTTP server that receives the requests.
 *
 * <p>
 * It runs one thread per client CPU. Each thread is pinned to its CPU and sends
 * requests at a fixed rate. To send a request, it picks a random user and calls
 * {@link RequestHandler#handleRequest}. Nothing goes over the network.
 *
 * <p>
 * Requests follow a fixed schedule, even when earlier requests have not been
 * answered yet. Latency is measured from the scheduled time, so a late send
 * counts as latency.
 *
 * <p>
 * The threads are named {@code client-N}. The script uses this name to leave
 * them out of the perf counters.
 */
final class FrontendServer {

	/** The application: what the server calls for each request. */
	@FunctionalInterface
	interface RequestHandler {

		CompletableFuture<UserGroup.Recommended> handleRequest(int userId);
	}

	/**
	 * The most requests a thread may wait for. Beyond that, it skips sends, and the
	 * run is marked as not a measurement.
	 */
	private static final int MAX_IN_FLIGHT = 1000;

	/**
	 * The largest latency recorded. A fixed limit, so the histograms never grow.
	 */
	private static final long HIGHEST_NANOS = TimeUnit.SECONDS.toNanos(60);

	private final RequestHandler handler;

	private final int users;

	private final long rate;

	private final List<Integer> cpus;

	private final List<Thread> threads = new ArrayList<>();

	private final List<AtomicLong> inFlight = new ArrayList<>();

	private volatile boolean running = true;

	/**
	 * Where requests are counted. A request is counted in the window that was
	 * current when it was scheduled. The warm-up is counted too, then thrown away:
	 * this way the counting code is already compiled when the measure starts.
	 */
	private volatile Window current = new Window();

	private Window measured;

	/**
	 * {@code users}: requests pick a user between 0 and {@code users - 1}.
	 * {@code rate}: requests per second, all threads together. {@code cpus}: one
	 * thread per CPU.
	 */
	FrontendServer(RequestHandler handler, int users, long rate, List<Integer> cpus) {
		this.handler = handler;
		this.users = users;
		this.rate = rate;
		this.cpus = cpus;
	}

	int connections() {
		return Math.max(1, cpus.size());
	}

	/** Starts the threads. Returns once each thread is pinned to its CPU. */
	void start() throws InterruptedException {
		int count = connections();
		long interval = Math.round(1e9 * count / rate);
		CountDownLatch pinned = new CountDownLatch(count);
		AtomicReference<RuntimeException> pinFailure = new AtomicReference<>();
		for (int i = 0; i < count; i++) {
			Integer cpu = cpus.isEmpty() ? null : cpus.get(i);
			Random random = new Random(i);
			AtomicLong pending = new AtomicLong();
			inFlight.add(pending);
			threads.add(Thread.ofPlatform().name("client-" + i).start(() -> {
				try {
					if (cpu != null) {
						ClientAffinity.pinCurrentThread(cpu);
					}
				} catch (RuntimeException e) {
					pinFailure.compareAndSet(null, e);
					return;
				} finally {
					pinned.countDown();
				}
				send(interval, cpu != null, random, pending);
			}));
		}
		pinned.await();
		if (pinFailure.get() != null) {
			pinFailure.get().printStackTrace();
			System.exit(1); // an unpinned thread would distort the results
		}
	}

	/**
	 * Sends one request every {@code interval} ns. The schedule is fixed: a late
	 * send does not delay the next one.
	 */
	private void send(long interval, boolean spin, Random random, AtomicLong pending) {
		long next = System.nanoTime();
		while (running) {
			// Wait until the time of the next request.
			// Sleeping is not precise: the thread may wake up too late,
			// and the request would leave late.
			// Looping on the clock is precise, but keeps the CPU busy.
			// - If this thread has a CPU of its own, keeping it busy
			// harms nobody: loop.
			// - Otherwise, sleep until 50 us before the time, then loop
			// for the rest.
			long wait;
			while ((wait = next - System.nanoTime()) > 0) {
				if (!spin && wait > 50_000) {
					LockSupport.parkNanos(wait - 50_000);
				} else {
					Thread.onSpinWait();
				}
			}
			long scheduled = next;
			next += interval;
			Window window = current;
			if (pending.get() >= MAX_IN_FLIGHT) {
				window.dropped.incrementAndGet();
				continue;
			}
			window.sent.incrementAndGet();
			window.sendLag.recordValue(clamp(System.nanoTime() - scheduled));
			pending.incrementAndGet();
			handler.handleRequest(random.nextInt(users)).whenComplete((reply, error) -> {
				if (error != null) {
					window.failed.incrementAndGet();
				} else {
					window.latency.recordValue(clamp(System.nanoTime() - scheduled));
				}
				pending.decrementAndGet();
			});
		}
	}

	/** From now on, requests are measured. */
	void startMeasuring() {
		measured = new Window();
		current = measured;
	}

	/** From now on, requests are not measured. */
	void stopMeasuring() {
		current = new Window();
	}

	/** Stops the threads, then waits up to 5 s for the last answers. */
	void stop() throws InterruptedException {
		running = false;
		for (Thread thread : threads) {
			thread.join();
		}
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		for (AtomicLong pending : inFlight) {
			while (pending.get() > 0 && System.nanoTime() < deadline) {
				LockSupport.parkNanos(1_000_000);
			}
		}
	}

	/** The CPU time used by the threads, or 0 if the JVM cannot measure it. */
	long cpuNanos() {
		ThreadMXBean mx = ManagementFactory.getThreadMXBean();
		if (!mx.isThreadCpuTimeSupported()) {
			return 0;
		}
		long total = 0;
		for (Thread thread : threads) {
			total += Math.max(0, mx.getThreadCpuTime(thread.threadId()));
		}
		return total;
	}

	/** Prints the latencies of the measured window. Call it after {@link #stop}. */
	void printReport(long elapsedNanos) {
		Histogram latency = measured.latency.getIntervalHistogram();
		Histogram lag = measured.sendLag.getIntervalHistogram();
		long sent = measured.sent.get();
		long answered = latency.getTotalCount();
		long failed = measured.failed.get();
		long dropped = measured.dropped.get();
		double seconds = elapsedNanos / 1e9;
		System.out.printf(Locale.ROOT,
				"latency      p50 %.1f  p90 %.1f  p99 %.1f  p99.9 %.1f  max %.1f us (from the scheduled send)%n",
				micros(latency, 50), micros(latency, 90), micros(latency, 99), micros(latency, 99.9),
				latency.getMaxValue() / 1e3);
		System.out.printf(Locale.ROOT, "send lag     p50 %.1f  p99 %.1f us (how late the clients sent)%n",
				micros(lag, 50), micros(lag, 99));
		long unanswered = sent - answered - failed;
		if (dropped > 0 || unanswered > 0 || failed > 0 || sent < 0.99 * rate * seconds) {
			System.out.printf(Locale.ROOT,
					"*** did not keep up with %,d req/s: sent %,.0f req/s, %,d dropped, %,d unanswered, %,d failed"
							+ " -- not a measurement ***%n",
					rate, sent / seconds, dropped, unanswered, failed);
		}
	}

	private static long clamp(long nanos) {
		return Math.min(Math.max(0, nanos), HIGHEST_NANOS);
	}

	private static double micros(Histogram histogram, double percentile) {
		return histogram.getValueAtPercentile(percentile) / 1e3;
	}

	/** The counters of one window. A Recorder can be written from any thread. */
	private static final class Window {

		private final Recorder latency = new Recorder(HIGHEST_NANOS, 3);

		/** How late each request was sent. */
		private final Recorder sendLag = new Recorder(HIGHEST_NANOS, 3);

		private final AtomicLong sent = new AtomicLong();

		private final AtomicLong dropped = new AtomicLong();

		private final AtomicLong failed = new AtomicLong();
	}
}
