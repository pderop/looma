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
package io.github.pderop.looma.examples.servlet;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Carriers;
import io.github.pderop.looma.Placement;
import io.github.pderop.looma.SpawnOptions;
import io.github.pderop.looma.examples.utils.BackendCpu;
import io.github.pderop.looma.examples.utils.ClientAffinity;
import io.github.pderop.looma.examples.utils.LoadGenerator;

/**
 * The servlet benchmark. Run it with {@code scripts/bench-servlet.sh}.
 * {@code docs/benchmark-servlet.md} explains it.
 *
 * <ul>
 * <li>{@link LoadGenerator} sends {@code servlet.rate} requests per second to
 * {@link Running#handleRequest}.
 * <li>{@code handleRequest} sends each request to the
 * {@link ServletContainerActor} of its session. There is one container per
 * carrier.
 * <li>The container gives the request to one of its two servlets. The servlet
 * asks the container's {@link FraudDetectorActor} for a risk. The detector
 * calls {@link ServletBackend}, which runs in another process, and answers.
 * <li>The servlet then calls the backend too, and answers.
 * </ul>
 *
 * One JVM makes either a measure or a ramp. First, the warm-up: the rate goes
 * up from a tenth of {@code servlet.rate} to {@code servlet.rate}, over
 * {@code servlet.warmupSeconds}. Then:
 *
 * <pre>
 * the measure: servlet.measureSeconds at servlet.rate, then the report
 * the ramp, with servlet.step:
 *   each step, from servlet.rate: servlet.stepSeconds measured, then the report
 *   "not a measurement": the ramp stops
 *   the rate goes up by servlet.step, little by little over
 *   servlet.settleSeconds, unmeasured
 * </pre>
 *
 * {@code servlet.maxRate} stops the ramp after that rate. The other properties
 * are {@code servlet.carriers}, {@code servlet.scheduler} ({@code mpsc},
 * {@code mpsc-unpinned}, {@code mpsc-no-affinity},
 * {@code mpsc-unpinned-no-affinity} or {@code jdk}),
 * {@code servlet.backendHost}, {@code servlet.backendPort},
 * {@code bench.connections}, {@code servlet.sessions},
 * {@code servlet.products}, {@code servlet.cartMax}, {@code servlet.recentMax},
 * {@code servlet.fraudKiB}, {@code servlet.fraudFeatures},
 * {@code servlet.clientCpus}, and {@code servlet.backendPid} with
 * {@code servlet.clkTck} to print the backend's CPU (Linux only).
 */
public final class Main {

	private static final int WARMUP_SECONDS = Integer.getInteger("servlet.warmupSeconds", 10);

	private static final int MEASURE_SECONDS = Integer.getInteger("servlet.measureSeconds", 20);

	private static final int STEP_SECONDS = Integer.getInteger("servlet.stepSeconds", 10);

	private static final int SETTLE_SECONDS = Integer.getInteger("servlet.settleSeconds", 3);

	/**
	 * After a step, the time its last answers have to come in. One that comes later
	 * counts as unanswered: the step is then not a measurement.
	 */
	private static final int LATE_ANSWER_SECONDS = 2;

	private Main() {
	}

	/** A server whose servlets are ready. {@link #close()} stops it. */
	public static final class Running implements AutoCloseable {

		private final ActorSystem system;

		private final ActorRef containers;

		private final int count;

		private Running(ActorSystem system, ActorRef containers, int count) {
			this.system = system;
			this.containers = containers;
			this.count = count;
		}

		/** Every request comes here, and goes to the container of its session. */
		public CompletableFuture<HttpResponse> handleRequest(HttpRequest request) {
			return containers.forKey(request.sid()).ask(request, HttpResponse.class);
		}

		/**
		 * The counts of all the fraud detectors, added up, or null if one did not
		 * answer within 5 s. With {@code reset}, they start again from zero.
		 */
		Messages.FraudStats fraudStats(boolean reset) throws InterruptedException {
			List<CompletableFuture<Messages.FraudStats>> answers = new ArrayList<>();
			for (long i = 0; i < count; i++) {
				// The selector maps key i to member i.
				answers.add(containers.forKey(i).ask(new Messages.FraudSnapshot(reset), Messages.FraudStats.class));
			}
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			Messages.FraudStats total = new Messages.FraudStats(0, 0, 0);
			for (CompletableFuture<Messages.FraudStats> answer : answers) {
				try {
					total = total.plus(answer.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
				} catch (ExecutionException | TimeoutException e) {
					return null;
				}
			}
			return total;
		}

		ActorSystem system() {
			return system;
		}

		@Override
		public void close() throws InterruptedException {
			system.shutdown();
			if (!system.awaitTermination(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("the servlet server did not stop within 10 s");
			}
		}
	}

	/**
	 * Starts the server. Blocks until every container has its backend connections.
	 * Throws if a container cannot connect to the backend. {@code carriers} is the
	 * size of the container pool; it must be at least 2, because the pool has a
	 * selector.
	 */
	public static Running start(InetSocketAddress backend, int carriers, String scheduler, int connections,
			FraudDetectorActor.Settings fraud) throws Exception {
		if (carriers < 2) {
			throw new IllegalArgumentException("servlet.carriers must be at least 2, got " + carriers);
		}
		// First, before any virtual thread starts: the JVM picks its scheduler when
		// the first one starts.
		ActorSystem system = ActorSystem.builder().carriers(carriers(scheduler, carriers)).build();
		try {
			// The server is ready only once every container has its backend
			// connections.
			Startup startup = new Startup(carriers);
			ActorRef containers = system.spawn("containers",
					() -> new ServletContainerActor(backend, connections, fraud, startup), SpawnOptions.pooled(carriers)
							.withSelector(key -> (int) ((Long) key).longValue())
							.withPlacement(Placement.roundRobin()));
			startup.await(Duration.ofSeconds(30));
			return new Running(system, containers, carriers);
		} catch (Exception e) {
			system.shutdown();
			system.awaitTermination(10, TimeUnit.SECONDS);
			throw e;
		}
	}

	public static void main(String[] args) throws Exception {
		int carriers = Integer.getInteger("servlet.carriers", Runtime.getRuntime().availableProcessors());
		String scheduler = System.getProperty("servlet.scheduler", "mpsc");
		int connections = Integer.getInteger("bench.connections", 8);
		int sessions = Integer.getInteger("servlet.sessions", 100_000);
		long rate = Long.getLong("servlet.rate", 0);
		long step = Long.getLong("servlet.step", 0);
		long maxRate = Long.getLong("servlet.maxRate", 0);
		if (rate <= 0 || step < 0 || maxRate < 0) {
			throw new IllegalArgumentException(
					"servlet.rate must be above 0, servlet.step and servlet.maxRate not below");
		}
		List<Integer> clientCpus = ClientAffinity.available()
				? ClientAffinity.parseCpuList(System.getProperty("servlet.clientCpus", ""))
				: List.of();
		InetSocketAddress backend = new InetSocketAddress(System.getProperty("servlet.backendHost", "127.0.0.1"),
				Integer.getInteger("servlet.backendPort", 8081));
		BackendCpu backendCpu = new BackendCpu(Long.getLong("servlet.backendPid", 0),
				Long.getLong("servlet.clkTck", 100));
		FraudDetectorActor.Settings fraud = FraudDetectorActor.Settings.fromProperties();

		Running server = start(backend, carriers, scheduler, connections, fraud);
		// Each request picks a session and a product; a response other than 200 is a
		// failure.
		LoadGenerator<HttpResponse> load = new LoadGenerator<>(
				random -> server.handleRequest(nextRequest(random, sessions)), response -> response.status() == 200,
				clientCpus);

		// The carriers asked for, with their settings.
		System.out.printf(Locale.ROOT, "scheduler    %s, %s%n", server.system().scheduler().getClass().getName(),
				carriers(scheduler, carriers));
		System.out.printf(Locale.ROOT, "poller mode  %s%n", System.getProperty("jdk.pollerMode", "not set"));
		System.out.printf(Locale.ROOT, "sessions     %,d, %d backend connection(s) per container%n", sessions,
				connections);
		System.out.printf(Locale.ROOT,
				"state        %,d products; a cart keeps %d lines, a profile %d viewed products%n", Products.COUNT,
				CartServlet.MAX_LINES, ProfileServlet.MAX_VIEWED);
		System.out.printf(Locale.ROOT, "fraud        a table of %d KiB per container, %d features per check%n",
				fraud.tableKiB(), fraud.features());
		System.out.printf(Locale.ROOT, "load         %,d req/s, %d client thread(s) on CPUs %s%n", rate,
				load.connections(), clientCpus.isEmpty() ? "<floating>" : clientCpus);
		System.out.printf(Locale.ROOT, "backend      %s:%d%n", backend.getHostString(), backend.getPort());
		// scripts/bench-servlet.sh waits for this line before the warm-up.
		if (step > 0) {
			System.out.printf(Locale.ROOT,
					"window       %ds warm-up, then a ramp from %,d req/s, %,d more at each step%s; at each step"
							+ " %ds measured and %ds to go up to it%n%n",
					WARMUP_SECONDS, rate, step, maxRate > 0 ? String.format(Locale.ROOT, ", up to %,d", maxRate) : "",
					STEP_SECONDS, SETTLE_SECONDS);
		} else {
			System.out.printf(Locale.ROOT, "window       %ds warm-up, %ds measured%n%n", WARMUP_SECONDS,
					MEASURE_SECONDS);
		}
		System.out.flush();

		load.setRate(Math.max(1, rate / 10));
		load.start();
		// A first read of the CPU time of the clients, so its code is loaded before the
		// measure.
		load.cpuNanos();
		rampTo(server, load, Math.max(1, rate / 10), rate, WARMUP_SECONDS);

		if (step > 0) {
			ramp(server, load, backendCpu, rate, step, maxRate);
			load.stop();
		} else {
			// scripts/bench-servlet.sh starts perf from this line.
			System.out.printf(Locale.ROOT, "measure      started, %ds%n", MEASURE_SECONDS);
			System.out.flush();
			// The snapshot first: its ask to the detectors stays out of the window.
			Usage usage = Usage.start(server, load, backendCpu);
			LoadGenerator.Window window = load.startMeasuring();
			TimeUnit.SECONDS.sleep(MEASURE_SECONDS);
			load.stopMeasuring();
			usage.stop(server, load, backendCpu);
			// The last answers, for up to 5 s, with no new request.
			load.stop();
			printReport(load.answered(window), usage);
			backendCpu.print(usage.backendTicks, usage.elapsed);
			load.printReport(window, rate, usage.elapsed);
			System.out.flush();
		}
		try {
			server.close();
		} catch (IllegalStateException e) {
			// The report is written. A slow stop does not change it.
			System.err.println("warning: " + e.getMessage());
		}
	}

	/**
	 * The ramp: the rate starts at {@code rate} and goes up by {@code step} at each
	 * step, until a step is not kept up with, or above {@code maxRate} (0: no
	 * limit). The last line says the highest rate kept up with.
	 */
	private static void ramp(Running server, LoadGenerator<?> load, BackendCpu backendCpu, long rate, long step,
			long maxRate) throws InterruptedException {
		long held = 0;
		for (long next = rate; maxRate == 0 || next <= maxRate; next += step) {
			if (next != rate) {
				rampTo(server, load, next - step, next, SETTLE_SECONDS);
			}
			// scripts/bench-servlet.sh shows this line as the ramp goes.
			System.out.printf(Locale.ROOT, "step         %,d req/s, %ds measured%n", next, STEP_SECONDS);
			System.out.flush();
			// The snapshot first: its ask to the detectors stays out of the window.
			Usage usage = Usage.start(server, load, backendCpu);
			LoadGenerator.Window window = load.startMeasuring();
			TimeUnit.SECONDS.sleep(STEP_SECONDS);
			load.stopMeasuring();
			usage.stop(server, load, backendCpu);

			// The late answers of the step. The next step's rate waits for its report.
			TimeUnit.SECONDS.sleep(LATE_ANSWER_SECONDS);
			printReport(load.answered(window), usage);
			backendCpu.print(usage.backendTicks, usage.elapsed);
			boolean keptUp = load.printReport(window, next, usage.elapsed);
			System.out.println();
			System.out.flush();
			if (!keptUp) {
				break;
			}
			held = next;
		}
		System.out.printf(Locale.ROOT, "ramp         kept up with %s%n",
				held > 0 ? String.format(Locale.ROOT, "%,d req/s", held) : "none");
		System.out.flush();
	}

	/**
	 * What a measure used: its time, the CPU of the process and clients, the
	 * backend's, and the counts of the fraud detectors.
	 */
	private static final class Usage {

		/** Null when a detector did not answer in time. */
		Messages.FraudStats fraud;

		private long start;

		private Duration startCpu;

		private long startClientCpu;

		private long startBackend;

		long elapsed;

		long cpu;

		long clientCpu;

		/** -1 when nothing could be read: off Linux, or no pid. */
		long backendTicks;

		static Usage start(Running server, LoadGenerator<?> load, BackendCpu backendCpu) throws InterruptedException {
			server.fraudStats(true);
			Usage usage = new Usage();
			usage.start = System.nanoTime();
			usage.startCpu = processCpu();
			usage.startClientCpu = load.cpuNanos();
			usage.startBackend = backendCpu.ticks();
			return usage;
		}

		void stop(Running server, LoadGenerator<?> load, BackendCpu backendCpu) throws InterruptedException {
			elapsed = System.nanoTime() - start;
			cpu = processCpu().minus(startCpu).toNanos();
			clientCpu = load.cpuNanos() - startClientCpu;
			backendTicks = backendCpu.ticksSince(startBackend);
			fraud = server.fraudStats(false);
		}
	}

	/**
	 * Takes the rate from {@code from} to {@code to} over {@code seconds}, a little
	 * every 100 ms, and ends at {@code to}.
	 *
	 * <p>
	 * Each tick also takes the counts of the fraud detectors, as a measure does.
	 * Otherwise the first measure sends the actors a message they never had. The
	 * JIT then throws away their compiled code, and the server stalls for some 50
	 * ms.
	 */
	private static void rampTo(Running server, LoadGenerator<?> load, long from, long to, int seconds)
			throws InterruptedException {
		int ticks = Math.max(1, seconds * 10);
		for (int i = 1; i <= ticks; i++) {
			server.fraudStats(true);
			load.setRate(from + (to - from) * i / ticks);
			TimeUnit.MILLISECONDS.sleep(100);
		}
	}

	/**
	 * A random session and a random product: half GET /profile, which views the
	 * product, half POST /cart, which adds one to three of it.
	 */
	private static HttpRequest nextRequest(Random random, int sessions) {
		long sid = random.nextInt(sessions);
		int product = random.nextInt(Products.COUNT);
		if (random.nextBoolean()) {
			return new HttpRequest("GET", "/profile?viewed=" + product, sid, null);
		}
		byte[] form = ("product=" + product + "&qty=" + (1 + random.nextInt(3))).getBytes(StandardCharsets.US_ASCII);
		return new HttpRequest("POST", "/cart", sid, form);
	}

	private static Carriers carriers(String scheduler, int count) {
		return switch (scheduler) {
			case "mpsc" -> Carriers.mpsc(count);
			case "mpsc-unpinned" -> Carriers.mpsc(count).pinned(false);
			case "mpsc-no-affinity" -> Carriers.mpsc(count).affinity(false);
			case "mpsc-unpinned-no-affinity" -> Carriers.mpsc(count).pinned(false).affinity(false);
			case "jdk" -> Carriers.builtin(count);
			default -> throw new IllegalArgumentException(
					"servlet.scheduler must be mpsc, mpsc-unpinned, mpsc-no-affinity, mpsc-unpinned-no-affinity or jdk,"
							+ " not '" + scheduler + "'");
		};
	}

	private static Duration processCpu() {
		return ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO);
	}

	/**
	 * The server's CPU is the process's CPU minus the client threads' CPU: the
	 * client threads run in this JVM, but they are the load, not the server.
	 */
	private static void printReport(long answered, Usage usage) {
		long elapsed = usage.elapsed;
		long serverCpu = usage.cpu - usage.clientCpu;
		long requests = Math.max(1, answered);
		System.out.printf(Locale.ROOT, "throughput   %,.0f req/s (answered with a 200)%n", answered / (elapsed / 1e9));
		System.out.printf(Locale.ROOT, "cpu          %.2f us/req (%.2f cores busy) -- server: process minus clients%n",
				serverCpu / 1e3 / requests, serverCpu / (double) elapsed);
		System.out.printf(Locale.ROOT, "client cpu   %.2f us/req (%.2f cores busy)%n", usage.clientCpu / 1e3 / requests,
				usage.clientCpu / (double) elapsed);
		if (usage.fraud == null) {
			System.out.println("migrations   unknown: a fraud detector did not answer in time");
			return;
		}
		long traced = usage.fraud.traced();
		System.out.printf(Locale.ROOT,
				"migrations   %.1f%% of %,d sampled messages of the fraud detectors ran on another carrier than the"
						+ " one before%n",
				traced == 0 ? 0 : 100.0 * usage.fraud.migrated() / traced, traced);
	}
}
