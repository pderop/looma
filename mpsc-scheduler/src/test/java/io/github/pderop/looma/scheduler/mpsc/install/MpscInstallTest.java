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

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Carriers;
import io.github.pderop.looma.spi.ActorScheduler;

import static io.github.pderop.looma.scheduler.mpsc.install.LinuxThreads.SELF;
import static io.github.pderop.looma.scheduler.mpsc.install.LinuxThreads.TASKS;
import static io.github.pderop.looma.scheduler.mpsc.install.LinuxThreads.call;
import static io.github.pderop.looma.scheduler.mpsc.install.LinuxThreads.cpusAllowed;
import static io.github.pderop.looma.scheduler.mpsc.install.LinuxThreads.name;
import static io.github.pderop.looma.scheduler.mpsc.install.LinuxThreads.tasks;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link ActorSystem.Builder#carriers(Carriers)} installing the MPSC scheduler
 * in a JVM started with no scheduler flag. One fresh JVM for this class.
 *
 * <p>
 * Every test asks for {@link #CARRIERS} first: the first one to run installs
 * the pool, and the others find it.
 *
 * <p>
 * On Linux, the pool pins each carrier to one CPU. Elsewhere it cannot, and the
 * carriers float.
 */
class MpscInstallTest {

	private static final Carriers.Affine CARRIERS = Carriers.affine(3);

	private ActorSystem system;

	@AfterEach
	void shutdown() throws InterruptedException {
		if (system != null) {
			system.shutdown();
			assertTrue(system.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test
	void installsThePoolAskedFor() {
		system = ActorSystem.builder().carriers(CARRIERS).build();

		assertEquals(3, system.carrierCount());
		assertEquals("MpscActorScheduler", system.scheduler().getClass().getSimpleName());
		assertEquals("true", System.getProperty("jdk.virtualThreadScheduler.useMpsc"));
		assertEquals("true", System.getProperty("jdk.virtualThreadScheduler.mpscSteal"));
		assertEquals("3", System.getProperty("jdk.virtualThreadScheduler.parallelism"));
	}

	@Test
	void anEqualRequestGetsTheSamePool() {
		ActorSystem.builder().carriers(CARRIERS).build().shutdown();

		system = ActorSystem.builder().carriers(Carriers.affine(3)).build();

		assertEquals(3, system.carrierCount());
	}

	@Test
	void aDifferentRequestIsRefused() {
		ActorSystem.builder().carriers(CARRIERS).build().shutdown();

		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> ActorSystem.builder().carriers(Carriers.affine(2)).build());

		assertTrue(e.getMessage().contains("already installed"), e.getMessage());
	}

	@Test
	void anotherKindOfPoolIsRefused() {
		ActorSystem.builder().carriers(CARRIERS).build().shutdown();

		assertThrows(IllegalStateException.class, () -> ActorSystem.builder().carriers(Carriers.builtin(3)).build());
	}

	@Test
	void discoveryFindsTheInstalledPool() {
		ActorSystem.builder().carriers(CARRIERS).build().shutdown();

		system = ActorSystem.builder().build();

		assertEquals(3, system.carrierCount());
	}

	@Test
	void pinsEachCarrierToItsOwnCpu() throws Exception {
		assumeTrue(Files.isDirectory(TASKS), "not Linux");
		assumeTrue(Runtime.getRuntime().availableProcessors() >= 3, "fewer CPUs than carriers");
		system = ActorSystem.builder().carriers(CARRIERS).build();
		ActorScheduler scheduler = system.scheduler();

		Set<Integer> cpus = new HashSet<>();
		for (int carrier = 0; carrier < 3; carrier++) {
			int[] seen = onCarrier(scheduler, carrier);
			assertEquals(carrier, seen[0], "the thread did not run on its carrier");
			assertTrue(seen[1] >= 0, "carrier " + carrier + " is not pinned");
			cpus.add(seen[1]);
			assertTrue(scheduler.clusterOf(carrier).isPresent(), "carrier " + carrier + " has no cluster");
		}
		assertEquals(3, cpus.size(), "two carriers share a CPU: " + cpus);
	}

	/**
	 * The JDK's poller threads start before the carriers are pinned, so they keep
	 * every CPU of the process. Without that, the first socket read of a carrier
	 * would start them, on that carrier's CPU only.
	 */
	@Test
	void theJdkPollersKeepEveryCpu() throws Exception {
		assumeTrue(Files.isDirectory(TASKS), "not Linux");
		system = ActorSystem.builder().carriers(CARRIERS).build();
		readOnCarrier(system.scheduler(), 0);

		String all = cpusAllowed(SELF);
		List<Path> pollers = tasks().filter(task -> name(task).contains("Poller")).toList();
		assertFalse(pollers.isEmpty(), "no poller thread in this process");
		for (Path poller : pollers) {
			assertEquals(all, cpusAllowed(poller), name(poller) + " lost CPUs");
		}
	}

	/**
	 * The thread that runs the timeouts of {@link CompletableFuture} starts before
	 * the carriers are pinned. Without that, the first timeout an actor sets would
	 * start it on the actor's CPU.
	 */
	@Test
	void theCommonPoolTimerKeepsEveryCpu() throws Exception {
		assumeTrue(Files.isDirectory(TASKS), "not Linux");
		system = ActorSystem.builder().carriers(CARRIERS).build();
		String all = cpusAllowed(SELF);

		String[] seen = call(system.scheduler(), 0,
				() -> new CompletableFuture<String[]>().orTimeout(1, TimeUnit.MILLISECONDS)
						.handle((value, timeout) -> new String[]{Thread.currentThread().getName(), cpusAllowed(SELF)})
						.get(5, TimeUnit.SECONDS));

		assertTrue(seen[0].contains("delayScheduler"), "the timeout ran on " + seen[0]);
		assertEquals(all, seen[1], seen[0] + " lost CPUs");
	}

	/**
	 * The JDK's timer threads for virtual threads start before the carriers are
	 * pinned. Without that, the first sleep on a carrier would start the timer of
	 * that carrier on its CPU.
	 */
	@Test
	void theVirtualThreadTimersKeepEveryCpu() throws Exception {
		assumeTrue(Files.isDirectory(TASKS), "not Linux");
		system = ActorSystem.builder().carriers(CARRIERS).build();
		String all = cpusAllowed(SELF);
		for (int carrier = 0; carrier < 3; carrier++) {
			call(system.scheduler(), carrier, () -> {
				Thread.sleep(1);
				return null;
			});
		}

		// Linux keeps 15 characters of a thread name: the JDK shows
		// "VirtualThread-unparker"
		// as "Virtual..parker".
		List<Path> timers = tasks().filter(task -> name(task).endsWith("parker")).toList();
		assertFalse(timers.isEmpty(), "no timer thread in this process");
		for (Path timer : timers) {
			assertEquals(all, cpusAllowed(timer), name(timer) + " lost CPUs");
		}
	}

	@Test
	void thePlatformThreadFactoryKeepsEveryCpu() throws Exception {
		assumeTrue(Files.isDirectory(TASKS), "not Linux");
		system = ActorSystem.builder().carriers(CARRIERS).build();
		String all = cpusAllowed(SELF);
		ThreadFactory factory = system.scheduler().platformThreadFactory();

		String seen = call(system.scheduler(), 0, () -> {
			CompletableFuture<String> cpus = new CompletableFuture<>();
			factory.newThread(() -> cpus.complete(cpusAllowed(SELF))).start();
			return cpus.get(5, TimeUnit.SECONDS);
		});

		assertEquals(all, seen);
	}

	@Test
	void floatingCarriersHaveNoCluster() throws Exception {
		assumeFalse(Files.isDirectory(TASKS), "Linux pins the carriers");
		system = ActorSystem.builder().carriers(CARRIERS).build();
		ActorScheduler scheduler = system.scheduler();

		assertEquals(OptionalInt.empty(), scheduler.clusterOf(0));
		assertEquals(-1, onCarrier(scheduler, 0)[1]);
	}

	/**
	 * The carrier and the pinned CPU (or -1) seen by a thread of {@code carrier}.
	 */
	private static int[] onCarrier(ActorScheduler scheduler, int carrier) throws Exception {
		CompletableFuture<int[]> seen = new CompletableFuture<>();
		scheduler.mailboxThreadFactory(carrier)
				.newThread(
						() -> seen.complete(new int[]{scheduler.currentCarrierId(), scheduler.pinnedCpu().orElse(-1)}))
				.start();
		return seen.get(5, TimeUnit.SECONDS);
	}

	/**
	 * Waits for a socket read on a thread of {@code carrier}: it needs a poller.
	 */
	private static void readOnCarrier(ActorScheduler scheduler, int carrier) throws Exception {
		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			CompletableFuture<Void> done = new CompletableFuture<>();
			scheduler.mailboxThreadFactory(carrier).newThread(() -> {
				try (Socket socket = new Socket(server.getInetAddress(), server.getLocalPort())) {
					socket.setSoTimeout(50);
					socket.getInputStream().read();
					done.complete(null);
				} catch (SocketTimeoutException e) {
					done.complete(null);
				} catch (Throwable e) {
					done.completeExceptionally(e);
				}
			}).start();
			done.get(5, TimeUnit.SECONDS);
		}
	}
}
