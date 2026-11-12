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
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

/**
 * The load of a benchmark, in the server's JVM, open loop. The user and the
 * servlet benchmarks both use it.
 *
 * <pre>
 * LoadGenerator&lt;HttpResponse&gt; load = new LoadGenerator&lt;&gt;(random -&gt; server.handleRequest(nextRequest(random, sessions)),
 * 		response -&gt; response.status() == 200, clientCpus);
 * </pre>
 *
 * <p>
 * It runs one thread per client CPU. Each thread is pinned to its CPU and sends
 * requests at a fixed rate. To send a request, it calls {@code send} with its
 * own generator: {@code send} picks the request and gives it to the server,
 * directly. Nothing goes over the network. An answer that {@code ok} refuses,
 * or an error, counts as a failure.
 *
 * <p>
 * Requests follow a fixed schedule, even when earlier requests have not been
 * answered yet. A thread with too many requests in flight skips its sends, and
 * the run is not a measurement. Latency is measured from the scheduled time, so
 * a late send counts as latency. {@link #setRate} changes the rate while the
 * threads run: the next request leaves at the new interval.
 *
 * <p>
 * The threads are named {@code client-N}. The scripts use this name to leave
 * them out of the perf counters.
 *
 * <p>
 * The actor that answers completes the future on its carrier. So the work done
 * there for the client is kept to the least: take the time, and push the
 * request back to its client thread. The client thread counts it: the latency,
 * the failures, the requests in flight. This way the CPU of the server holds as
 * little client work as possible.
 *
 * @param <R>
 *            the answer of the server
 */
public final class LoadGenerator<R> {

	/**
	 * The most requests a thread may wait for. Beyond that, it skips sends, and the
	 * run is marked as not a measurement.
	 */
	private static final int MAX_IN_FLIGHT = 1000;

	/**
	 * The largest latency recorded. A fixed limit, so the histograms never grow.
	 */
	private static final long HIGHEST_NANOS = TimeUnit.SECONDS.toNanos(60);

	private final Function<Random, CompletableFuture<R>> send;

	private final Predicate<R> ok;

	private final List<Integer> cpus;

	/** The time between two requests of one thread. Read at every request. */
	private volatile long interval;

	private final List<Thread> threads = new ArrayList<>();

	private volatile boolean running = true;

	/**
	 * Where requests are counted. A request is counted in the window that was
	 * current when it was scheduled. The time between two measures is counted too,
	 * then thrown away: this way the counting code is already compiled when a
	 * measure starts.
	 */
	private volatile Window current = new Window();

	/**
	 * {@code send}: picks a request with the generator given, and sends it to the
	 * server. {@code ok}: whether an answer is a success. {@code cpus}: one thread
	 * per CPU; empty for one thread, not pinned.
	 */
	public LoadGenerator(Function<Random, CompletableFuture<R>> send, Predicate<R> ok, List<Integer> cpus) {
		this.send = send;
		this.ok = ok;
		this.cpus = cpus;
	}

	/** The number of client threads. */
	public int connections() {
		return Math.max(1, cpus.size());
	}

	/**
	 * Requests per second, all threads together. Call it before {@link #start} too.
	 */
	public void setRate(long rate) {
		interval = Math.round(1e9 * connections() / rate);
	}

	/** Starts the threads. Returns once each thread is pinned to its CPU. */
	public void start() throws InterruptedException {
		int count = connections();
		CountDownLatch pinned = new CountDownLatch(count);
		AtomicReference<RuntimeException> pinFailure = new AtomicReference<>();
		for (int i = 0; i < count; i++) {
			Integer cpu = cpus.isEmpty() ? null : cpus.get(i);
			Random random = new Random(i);
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
				run(cpu != null, random);
			}));
		}
		pinned.await();
		if (pinFailure.get() != null) {
			pinFailure.get().printStackTrace();
			System.exit(1); // an unpinned thread would distort the results
		}
	}

	/**
	 * Sends one request every {@link #interval} ns. The schedule is fixed: a late
	 * send does not delay the next one.
	 */
	private void run(boolean spin, Random random) {
		Answers answers = new Answers();
		long pending = 0;
		long next = System.nanoTime();
		while (running) {
			pending -= count(answers.takeAll());
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
			if (pending >= MAX_IN_FLIGHT) {
				window.dropped.incrementAndGet();
				continue;
			}
			window.sent.incrementAndGet();
			window.sendLag.recordValue(clamp(System.nanoTime() - scheduled));
			pending++;
			// The request is also what the answer runs, on the carrier of the actor.
			send.apply(random).whenComplete(new Request(scheduled, window, answers));
		}
		// The last answers, for up to 5 s.
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (pending > 0 && System.nanoTime() < deadline) {
			pending -= count(answers.takeAll());
			LockSupport.parkNanos(1_000_000);
		}
	}

	/**
	 * Counts the answered requests of a list, on the client thread. Returns how
	 * many there were.
	 */
	private int count(Request answered) {
		int n = 0;
		for (Request request = answered; request != null; request = request.next) {
			if (request.failed) {
				request.window.failed.incrementAndGet();
			} else {
				request.window.latency.recordValue(clamp(request.answeredAt - request.scheduled));
			}
			n++;
		}
		return n;
	}

	/** From now on, requests are measured, in the window returned. */
	public Window startMeasuring() {
		Window measured = new Window();
		current = measured;
		return measured;
	}

	/** From now on, requests are not measured. */
	public void stopMeasuring() {
		current = new Window();
	}

	/** Stops the threads. Each one waits up to 5 s for its last answers. */
	public void stop() throws InterruptedException {
		running = false;
		for (Thread thread : threads) {
			thread.join();
		}
	}

	/** The CPU time used by the threads, or 0 if the JVM cannot measure it. */
	public long cpuNanos() {
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

	/**
	 * The requests of a measured window that succeeded. Call it once the late
	 * answers of the window are in.
	 */
	public long answered(Window window) {
		return window.latency().getTotalCount();
	}

	/**
	 * Prints the latencies of a measured window, sent at {@code rate}. Call it once
	 * the late answers of the window are in. Returns false if the window is not a
	 * measurement: the threads could not send at the rate, some requests did not
	 * succeed, or the threads sent too late.
	 */
	public boolean printReport(Window window, long rate, long elapsedNanos) {
		Histogram latency = window.latency();
		Histogram lag = window.sendLag.getIntervalHistogram();
		long sent = window.sent.get();
		long answered = latency.getTotalCount();
		long failed = window.failed.get();
		long dropped = window.dropped.get();
		double seconds = elapsedNanos / 1e9;
		System.out.printf(Locale.ROOT,
				"latency      p50 %.1f  p90 %.1f  p99 %.1f  p99.9 %.1f  max %.1f us (from the scheduled send)%n",
				micros(latency, 50), micros(latency, 90), micros(latency, 99), micros(latency, 99.9),
				latency.getMaxValue() / 1e3);
		System.out.printf(Locale.ROOT, "send lag     p50 %.1f  p99 %.1f us (how late the clients sent)%n",
				micros(lag, 50), micros(lag, 99));
		long unanswered = sent - answered - failed;
		boolean keptUp = true;
		if (dropped > 0 || unanswered > 0 || failed > 0 || sent < 0.99 * rate * seconds) {
			System.out.printf(Locale.ROOT,
					"*** did not keep up with %,d req/s: sent %,.0f req/s, %,d dropped, %,d unanswered, %,d failed"
							+ " -- not a measurement ***%n",
					rate, sent / seconds, dropped, unanswered, failed);
			keptUp = false;
		}
		// A late send is still timed from its planned time.
		// But then the measure counts the clients too, not only the server.
		// So the clients may be late by half the time between two sends, at most.
		double maxLagMicros = 1e6 * connections() / rate / 2;
		if (micros(lag, 99) > maxLagMicros) {
			System.out.printf(Locale.ROOT,
					"*** clients did not keep up with %,d req/s: send lag p99 %.1f us, above %.1f us"
							+ " (half the time between two sends of a thread) -- not a measurement ***%n",
					rate, micros(lag, 99), maxLagMicros);
			keptUp = false;
		}
		return keptUp;
	}

	private static long clamp(long nanos) {
		return Math.min(Math.max(0, nanos), HIGHEST_NANOS);
	}

	private static double micros(Histogram histogram, double percentile) {
		return histogram.getValueAtPercentile(percentile) / 1e3;
	}

	/**
	 * One request, from its send to its answer. It is also what runs when the
	 * answer comes, so a request allocates nothing more.
	 */
	private final class Request implements BiConsumer<R, Throwable> {

		private final long scheduled;

		/** The window that was current when the request was scheduled. */
		private final Window window;

		/** Written on the carrier, before the push; read after the take. */
		private long answeredAt;

		private boolean failed;

		/** Where the answer goes back to the client thread. */
		private final Answers answers;

		/** The next request in a list of {@link Answers}. */
		private Request next;

		private Request(long scheduled, Window window, Answers answers) {
			this.scheduled = scheduled;
			this.window = window;
			this.answers = answers;
		}

		/** Runs on the carrier of the actor that answers: the time, and a push. */
		@Override
		public void accept(R reply, Throwable error) {
			answeredAt = System.nanoTime();
			failed = error != null || !ok.test(reply);
			answers.push(this);
		}
	}

	/**
	 * The answered requests of one client thread, not counted yet: a stack that the
	 * carriers push onto, and that the client thread takes whole. A push is one
	 * compare-and-set and allocates nothing.
	 */
	private final class Answers {

		private final AtomicReference<Request> head = new AtomicReference<>();

		void push(Request request) {
			Request top;
			do {
				top = head.get();
				request.next = top;
			} while (!head.compareAndSet(top, request));
		}

		/** Every request pushed so far, or null. */
		Request takeAll() {
			return head.getAndSet(null);
		}
	}

	/** The counters of one window. A Recorder can be written from any thread. */
	public static final class Window {

		private final Recorder latency = new Recorder(HIGHEST_NANOS, 3);

		private Histogram latencyTaken;

		/** How late each request was sent. */
		private final Recorder sendLag = new Recorder(HIGHEST_NANOS, 3);

		private final AtomicLong sent = new AtomicLong();

		private final AtomicLong dropped = new AtomicLong();

		private final AtomicLong failed = new AtomicLong();

		/** Taken once: a Recorder empties itself on each read. */
		private Histogram latency() {
			if (latencyTaken == null) {
				latencyTaken = latency.getIntervalHistogram();
			}
			return latencyTaken;
		}
	}
}
