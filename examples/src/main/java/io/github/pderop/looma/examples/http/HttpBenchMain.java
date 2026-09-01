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
package io.github.pderop.looma.examples.http;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.spi.ActorScheduler;

/**
 * Entry point of the HTTP/1.1 cache-locality example: starts the server, prints
 * the effective configuration, and waits.
 *
 * <p>
 * <b>What this measures.</b> The same server, under the same cpuset and the
 * same parallelism, on the carrier-affine scheduler and on the JDK builtin
 * scheduler. Every connection is an actor owning a private array; each request
 * walks it along a pointer chase modifying every line, copies it into a
 * per-connection snapshot buffer handed to a forked virtual thread that walks
 * it in turn and blocks on the upstream, then modifies it again when the answer
 * comes back. Nothing is shared, so the only thing that differs between the two
 * runs is <em>which core</em> those lines live on: the affine scheduler brings
 * the actor's loop back to the core that already holds them in its private L2,
 * the JDK builtin does not. See {@link ConnectionActor} for the mechanism.
 *
 * <p>
 * <b>A {@link MockUpstream} must already be running</b> — every request makes a
 * blocking HTTP call to it, which is what puts the per-carrier sub-poller in
 * the measured path. This entry point probes it before binding the listen
 * socket and refuses to start without it, because a server that comes up and
 * answers every request out of a {@code catch} block would look exactly like a
 * healthy one to the load generator.
 *
 * <p>
 * <b>Run it as (a) carrier-affine, (b) carrier + work stealing, (c) JDK builtin
 * control</b> — see {@code scripts/run-http-bench.sh}, which also captures
 * {@code perf stat} and {@code perf c2c}. Which scheduler this process gets is
 * decided entirely by the JVM flags and the classpath: (a) and (b) run with
 * {@code -Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler}
 * and the {@code netty-loom-scheduler} jar on the classpath; (c) without that
 * flag, so discovery finds nothing usable and the JDK builtin scheduler is
 * used. The rule that keeps the comparison honest is that every configuration
 * gets the identical cpuset and parallelism; under {@code taskset} that mostly
 * happens by itself, since {@code availableProcessors()} already reports the
 * restricted count.
 *
 * <p>
 * <b>The controls that can falsify the result.</b> {@code -Dhttp.stateKiB=0}
 * gives every connection a zero-length array, so no request touches any state
 * at all: whatever gap survives that is scheduling overhead, not cache
 * locality. At the other end, a {@code stateKiB} large enough that one
 * carrier's connections no longer fit in its private L2 must make the gap
 * collapse back onto that control — if it does not, the run was not measuring
 * residency. Between the two, the gap should grow roughly linearly with
 * {@code stateKiB}, because the cost of a migration is the number of dirty
 * lines it has to drag across.
 */
public final class HttpBenchMain {

	/**
	 * Comfortably above any connection count this benchmark is run with. The load
	 * generator opens all of its connections at once at start-up, so a backlog
	 * sized to the connection count itself would have some of them refused before
	 * the accept loop drained the queue.
	 */
	private static final int BACKLOG = 1024;

	private HttpBenchMain() {
	}

	public static void main(String[] args) throws Exception {
		int processors = Runtime.getRuntime().availableProcessors();
		int port = Integer.getInteger("http.port", 8080);
		int stateKiB = Integer.getInteger("http.stateKiB", 64);
		URI upstream = URI.create(System.getProperty("http.mockUrl", "http://127.0.0.1:8081/fruits"));

		probeUpstream(upstream);

		ActorSystem system = ActorSystem.builder().build();
		ActorScheduler scheduler = system.scheduler();
		boolean carrier = isCarrierAffine(scheduler);

		Set<SocketChannel> openChannels = ConcurrentHashMap.newKeySet();

		printConfiguration(scheduler, carrier, processors, port, stateKiB, upstream);
		if (carrier) {
			reportCarrierPinning(scheduler);
		}

		Acceptor acceptor = new Acceptor(port, BACKLOG, system, stateKiB, upstream, openChannels);
		acceptor.start();

		System.out.printf("listening on port %d%n", port);

		awaitShutdown(system, acceptor, openChannels);
	}

	private static void printConfiguration(ActorScheduler scheduler, boolean carrier, int processors, int port,
			int stateKiB, URI upstream) {
		System.out.printf("scheduler=%s carrierAffine=%b carriers=%d availableProcessors=%d port=%d%n",
				scheduler.getClass().getName(), carrier, scheduler.carrierCount(), processors, port);
		System.out.printf("stateKiB=%d%n", stateKiB);
		System.out.printf("upstream=%s%n", upstream);
		if (!carrier) {
			System.out.printf("vtParallelism=%s vtMaxPoolSize=%s%n",
					System.getProperty("jdk.virtualThreadScheduler.parallelism", "<default>"),
					System.getProperty("jdk.virtualThreadScheduler.maxPoolSize", "<default>"));
		}
		if (stateKiB == 0) {
			System.out.println("*** dispatch-only control: no state is touched ***");
		}
	}

	/**
	 * Calls the mock once from this (platform) thread, before the listen socket is
	 * bound, so that a missing backend stops the JVM instead of producing a run.
	 *
	 * <p>
	 * Without it a missing mock produces a server that binds, accepts, and answers
	 * every request from {@code ConnectionActor}'s catch block — indistinguishable
	 * from a healthy run at the load generator, and roughly ten times faster.
	 */
	private static void probeUpstream(URI upstream) {
		try (CloseableHttpClient client = HttpClientBuilder.create()
				.setConnectionManager(new BasicHttpClientConnectionManager()).setConnectionManagerShared(false)
				.build()) {
			try (CloseableHttpResponse response = client.execute(new HttpGet(upstream))) {
				if (response.getCode() != 200) {
					throw new IllegalStateException(
							"mock upstream " + upstream + " answered " + response.getCode() + ", expected 200");
				}
				EntityUtils.consume(response.getEntity());
			}
		} catch (Exception e) {
			throw new IllegalStateException("mock upstream " + upstream + " is not reachable -- start it first:\n"
					+ "  java -cp <examples.jar>:<core.jar> " + "io.github.pderop.looma.examples.http.MockUpstream\n"
					+ "(-Dhttp.mockUrl points this server elsewhere; -Dmock.port/-Dmock.thinkMicros configure it)", e);
		}
	}

	/**
	 * Answers whether the discovered scheduler actually places work on carriers, by
	 * asking one of its own virtual threads.
	 *
	 * <p>
	 * Behavioural on purpose. The class name would be a string match against an
	 * implementation this module deliberately does not depend on, and
	 * {@code carrierCount() > 1} is simply wrong: a carrier-affine run with
	 * {@code -Dio.netty.loom.schedulers=1} has exactly one carrier, and would be
	 * reported as the JDK builtin.
	 */
	private static boolean isCarrierAffine(ActorScheduler scheduler) throws InterruptedException {
		AtomicBoolean affine = new AtomicBoolean();
		CountDownLatch probed = new CountDownLatch(1);

		scheduler.vThreadFactory(0).newThread(() -> {
			affine.set(scheduler.currentCarrierId() >= 0);
			probed.countDown();
		}).start();

		if (!probed.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("scheduler probe did not complete within 10s");
		}
		return affine.get();
	}

	/**
	 * Prints the carrier-to-CPU map, and refuses to run if pinning was requested
	 * but silently did nothing.
	 *
	 * <p>
	 * A topology implementation tolerates a host that cannot pin: the FFM lookup of
	 * {@code sched_setaffinity} yields no handle and pinning degrades to a
	 * <b>silent no-op</b>. A benchmark inheriting that would publish
	 * floating-carrier numbers under a "pinned" label, which is the worst failure
	 * available here because the output looks perfectly normal.
	 *
	 * <p>
	 * The probe runs a one-shot virtual thread from each carrier's own factory, so
	 * {@link ActorScheduler#pinnedCpu()} — {@code sched_getaffinity(0, ...)} —
	 * reports that carrier's kernel thread and not this one. Reading only — nothing
	 * in the scheduler is modified.
	 */
	private static void reportCarrierPinning(ActorScheduler scheduler) throws InterruptedException {
		int carriers = scheduler.carrierCount();

		int[] pinnedCpus = new int[carriers];
		Arrays.fill(pinnedCpus, -1);
		CountDownLatch probed = new CountDownLatch(carriers);
		for (int i = 0; i < carriers; i++) {
			int carrierIndex = i;
			scheduler.vThreadFactory(i).newThread(() -> {
				pinnedCpus[carrierIndex] = scheduler.pinnedCpu().orElse(-1);
				probed.countDown();
			}).start();
		}
		if (!probed.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("carrier affinity probe did not complete: " + probed.getCount() + " of "
					+ carriers + " carriers did not respond");
		}

		StringBuilder map = new StringBuilder("carriers=").append(carriers).append(" affinity:");
		int floating = 0;
		for (int i = 0; i < carriers; i++) {
			map.append(' ').append(i).append("->");
			if (pinnedCpus[i] < 0) {
				map.append("float");
				floating++;
			} else {
				map.append("cpu").append(pinnedCpus[i]);
			}
		}
		System.out.println(map);

		boolean topologyRequested = System.getProperty("io.netty.loom.topology") != null;
		if (topologyRequested && floating == carriers) {
			throw new IllegalStateException("-Dio.netty.loom.topology was set but no carrier is pinned: results would "
					+ "be indistinguishable from an unpinned run while claiming to be pinned.");
		}
		if (topologyRequested && floating > 0) {
			System.err.printf("WARNING: %d of %d carriers are not pinned -- check the process cpuset against "
					+ "the carrier count.%n", floating, carriers);
		}
	}

	/**
	 * Blocks until the JVM is asked to exit, then closes every open channel
	 * <em>before</em> awaiting termination.
	 *
	 * <p>
	 * That order is not optional: the engine counts every thread created through
	 * {@code vThreadFactory()} as in-flight work, and each connection has a reader
	 * thread parked in {@code read()} for as long as its socket is open — so
	 * {@code awaitTermination} could never return while any client is still
	 * connected.
	 */
	private static void awaitShutdown(ActorSystem system, Acceptor acceptor, Set<SocketChannel> openChannels)
			throws InterruptedException {
		CountDownLatch stopped = new CountDownLatch(1);
		Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
			acceptor.stop();
			for (SocketChannel channel : openChannels) {
				try {
					channel.close();
				} catch (IOException e) {
					// Shutting down anyway.
				}
			}
			system.shutdown();
			try {
				if (!system.awaitTermination(10, TimeUnit.SECONDS)) {
					System.err.println("actor system did not terminate within 10s");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			stopped.countDown();
		}));
		stopped.await();
	}
}
