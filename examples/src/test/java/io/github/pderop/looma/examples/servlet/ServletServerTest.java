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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Carriers;
import io.github.pderop.looma.Message;
import io.github.pderop.looma.Placement;
import io.github.pderop.looma.SpawnOptions;
import io.github.pderop.looma.examples.utils.FairPool;
import io.github.pderop.looma.examples.utils.ConnectionPool;

/** Routing by session id, one servlet, and the server end to end. */
class ServletServerTest {

	/** A small table, enough for the tests. */
	private static final FraudDetectorActor.Settings FRAUD = new FraudDetectorActor.Settings(4, 8);

	@Test
	void theFraudDetectorAnswersTheServletAndCountsItsChecks() throws Exception {
		try (FakeBackend backend = new FakeBackend()) {
			InetSocketAddress address = new InetSocketAddress("127.0.0.1", backend.port);
			ConnectionPool<BackendClient> pool = ConnectionPool.open(() -> BackendClient.connect(address), 1,
					Thread.ofVirtual().factory());
			ActorSystem system = ActorSystem.builder().carriers(Carriers.builtin(4)).build();
			try {
				BlockingQueue<Message> seen = new LinkedBlockingQueue<>();
				ActorRef probe = system.spawn("probe", () -> new Probe(seen), SpawnOptions.defaults());
				ActorRef fraud = spawnFraud(system, pool);
				Messages.ServletRequest request = new Messages.ServletRequest(
						new HttpRequest("POST", "/cart", 9L, form("product=1&qty=2")), probe);
				for (int i = 0; i < 3; i++) {
					fraud.tell(new Messages.FraudCheck(request, probe, 1, 2), probe);
					Messages.FraudVerdict verdict = (Messages.FraudVerdict) seen.poll(10, TimeUnit.SECONDS);
					assertEquals(request, verdict.request());
					assertEquals(probe, verdict.container());
					// The backend answers k=1: the account is not flagged.
					assertEquals(0, verdict.risk());
				}
				assertEquals(3, backend.requests.get());
				Messages.FraudStats stats = fraud.ask(new Messages.FraudSnapshot(true), Messages.FraudStats.class)
						.get(10, TimeUnit.SECONDS);
				assertEquals(3, stats.checks());
				assertEquals(0, fraud.ask(new Messages.FraudSnapshot(false), Messages.FraudStats.class)
						.get(10, TimeUnit.SECONDS)
						.checks());
			} finally {
				pool.close();
				system.shutdown();
				assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
			}
		}
	}

	@Test
	void aFraudTableMustBeAPowerOfTwo() {
		assertThrows(IllegalArgumentException.class, () -> new FraudDetectorActor.Settings(100, 32));
		assertThrows(IllegalArgumentException.class, () -> new FraudDetectorActor.Settings(128, -1));
		assertEquals(0, new FraudDetectorActor.Settings(128, 0).features());
	}

	/** The fraud detector a servlet of these tests asks. */
	private static ActorRef spawnFraud(ActorSystem system, ConnectionPool<BackendClient> pool) {
		return system.spawn("fraud", () -> new FraudDetectorActor(pool, FRAUD), SpawnOptions.defaults());
	}

	@Test
	void theSameSidReachesTheSameContainer() throws Exception {
		try (FakeBackend backend = new FakeBackend()) {
			ActorSystem system = ActorSystem.builder().carriers(Carriers.builtin(4)).build();
			try {
				InetSocketAddress address = new InetSocketAddress("127.0.0.1", backend.port);
				// Four containers, two servlets each. The servlets are spawned by the
				// containers' preStart, after spawn returns: wait for them.
				Startup startup = new Startup(4);
				ActorRef pool = system.spawn("containers", () -> new ServletContainerActor(address, 2, FRAUD, startup),
						SpawnOptions.pooled(4)
								.withSelector(key -> (int) ((Long) key).longValue())
								.withPlacement(Placement.roundRobin()));
				startup.await(Duration.ofSeconds(10));
				ActorRef first = pool.forKey(3L);
				ActorRef again = pool.forKey(3L);
				assertEquals(first.path(), again.path());
				assertNotEquals(first.path(), pool.forKey(4L).path());
				assertTrue(system.findActor(first.path() + "/profile").isPresent());
				assertTrue(system.findActor(first.path() + "/cart").isPresent());
				assertTrue(system.findActor(first.path() + "/fraud").isPresent());
			} finally {
				system.shutdown();
				assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
			}
		}
	}

	@Test
	void profileCountsVisitsOfOneSid() throws Exception {
		try (FakeBackend backend = new FakeBackend()) {
			InetSocketAddress address = new InetSocketAddress("127.0.0.1", backend.port);
			ConnectionPool<BackendClient> pool = ConnectionPool.open(() -> BackendClient.connect(address), 1,
					Thread.ofVirtual().factory());
			ActorSystem system = ActorSystem.builder().carriers(Carriers.builtin(4)).build();
			try {
				BlockingQueue<Message> seen = new LinkedBlockingQueue<>();
				ActorRef probe = system.spawn("probe", () -> new Probe(seen), SpawnOptions.defaults());
				ActorRef fraud = spawnFraud(system, pool);
				ActorRef profile = system.spawn("profile", () -> new ServletActor(new ProfileServlet(), pool, fraud),
						SpawnOptions.defaults());
				HttpRequest request = new HttpRequest("GET", "/profile", 9L, null);
				profile.tell(new Messages.ServletRequest(request, probe), probe);
				Messages.ServletResponse first = (Messages.ServletResponse) seen.poll(10, TimeUnit.SECONDS);
				assertTrue(text(first).contains("visits=1"));
				assertTrue(text(first).contains("checksum=1\nrisk=0\n"), text(first));
				profile.tell(new Messages.ServletRequest(request, probe), probe);
				Messages.ServletResponse second = (Messages.ServletResponse) seen.poll(10, TimeUnit.SECONDS);
				assertTrue(text(second).contains("visits=2"));
			} finally {
				// The servlet's container would close it: a call still waiting on it then ends.
				pool.close();
				system.shutdown();
				assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
			}
		}
	}

	@Test
	void anAnswerCancelsItsTimer() throws Exception {
		try (FakeBackend backend = new FakeBackend(false)) {
			InetSocketAddress address = new InetSocketAddress("127.0.0.1", backend.port);
			ConnectionPool<BackendClient> pool = ConnectionPool.open(() -> BackendClient.connect(address), 1,
					Thread.ofVirtual().factory());
			ActorSystem system = ActorSystem.builder().carriers(Carriers.builtin(4)).build();
			try {
				BlockingQueue<Message> seen = new LinkedBlockingQueue<>();
				ActorRef probe = system.spawn("probe", () -> new Probe(seen), SpawnOptions.defaults());
				ActorRef fraud = spawnFraud(system, pool);
				ActorRef profile = system.spawn("profile", () -> new ServletActor(new ProfileServlet(), pool, fraud),
						SpawnOptions.defaults());
				HttpRequest request = new HttpRequest("GET", "/profile", 9L, null);
				profile.tell(new Messages.ServletRequest(request, probe), probe);
				Messages.ServletResponse answer = (Messages.ServletResponse) seen.poll(10, TimeUnit.SECONDS);
				assertEquals(200, answer.response().status());
				// Past the timeout: a timer not cancelled would bring a 504 here.
				assertNull(seen.poll(BackendCalls.TIMEOUT_MILLIS + 500, TimeUnit.MILLISECONDS));
			} finally {
				// The servlet's container would close it: a call still waiting on it then ends.
				pool.close();
				system.shutdown();
				assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
			}
		}
	}

	@Test
	void aSilentBackendGivesA504() throws Exception {
		try (FakeBackend backend = new FakeBackend(true)) {
			InetSocketAddress address = new InetSocketAddress("127.0.0.1", backend.port);
			ConnectionPool<BackendClient> pool = ConnectionPool.open(() -> BackendClient.connect(address), 1,
					Thread.ofVirtual().factory());
			ActorSystem system = ActorSystem.builder().carriers(Carriers.builtin(4)).build();
			try {
				BlockingQueue<Message> seen = new LinkedBlockingQueue<>();
				ActorRef probe = system.spawn("probe", () -> new Probe(seen), SpawnOptions.defaults());
				ActorRef fraud = spawnFraud(system, pool);
				ActorRef cart = system.spawn("cart", () -> new ServletActor(new CartServlet(), pool, fraud),
						SpawnOptions.defaults());
				HttpRequest request = new HttpRequest("POST", "/cart", 9L, form("product=1&qty=1"));
				long start = System.nanoTime();
				cart.tell(new Messages.ServletRequest(request, probe), probe);
				Messages.ServletResponse answer = (Messages.ServletResponse) seen.poll(10, TimeUnit.SECONDS);
				long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
				assertEquals(504, answer.response().status());
				assertTrue(waited >= BackendCalls.TIMEOUT_MILLIS, "504 after " + waited + " ms");
			} finally {
				// The servlet's container would close it: a call still waiting on it then ends.
				pool.close();
				system.shutdown();
				assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
			}
		}
	}

	@Test
	void aTimedOutReadGetsANewConnection() throws Exception {
		try (FakeBackend backend = new FakeBackend(true)) {
			InetSocketAddress address = new InetSocketAddress("127.0.0.1", backend.port);
			ConnectionPool<BackendClient> pool = ConnectionPool.open(() -> BackendClient.connect(address), 1,
					Thread.ofVirtual().factory());
			ActorSystem system = ActorSystem.builder().carriers(Carriers.builtin(4)).build();
			try {
				BlockingQueue<Message> seen = new LinkedBlockingQueue<>();
				ActorRef probe = system.spawn("probe", () -> new Probe(seen), SpawnOptions.defaults());
				ActorRef fraud = spawnFraud(system, pool);
				ActorRef cart = system.spawn("cart", () -> new ServletActor(new CartServlet(), pool, fraud),
						SpawnOptions.defaults());
				cart.tell(new Messages.ServletRequest(new HttpRequest("POST", "/cart", 9L, form("product=1&qty=1")),
						probe), probe);
				Messages.ServletResponse answer = (Messages.ServletResponse) seen.poll(10, TimeUnit.SECONDS);
				assertEquals(504, answer.response().status());
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				while (backend.accepted.get() < 2 && System.nanoTime() < deadline) {
					Thread.sleep(10);
				}
				assertEquals(2, backend.accepted.get());
				assertEquals(1, pool.size());
			} finally {
				pool.close();
				system.shutdown();
				assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
			}
		}
	}

	@Test
	void anExpiredCallDoesNotAskTheBackend() throws Exception {
		try (FakeBackend backend = new FakeBackend(false)) {
			InetSocketAddress address = new InetSocketAddress("127.0.0.1", backend.port);
			ConnectionPool<BackendClient> pool = ConnectionPool.open(() -> BackendClient.connect(address), 1,
					Thread.ofVirtual().factory());
			ActorSystem system = ActorSystem.builder().carriers(Carriers.builtin(4)).build();
			try {
				BlockingQueue<Message> seen = new LinkedBlockingQueue<>();
				ActorRef probe = system.spawn("probe", () -> new Probe(seen), SpawnOptions.defaults());
				ActorRef fraud = spawnFraud(system, pool);
				ActorRef cart = system.spawn("cart", () -> new ServletActor(new CartServlet(), pool, fraud),
						SpawnOptions.defaults());
				// The only connection is taken: the call waits for it until its timer.
				BackendClient held = pool.take(new FairPool.Waiter<>());
				cart.tell(new Messages.ServletRequest(new HttpRequest("POST", "/cart", 9L, form("product=1&qty=1")),
						probe), probe);
				Messages.ServletResponse answer = (Messages.ServletResponse) seen.poll(10, TimeUnit.SECONDS);
				assertEquals(504, answer.response().status());
				pool.give(held);
				Thread.sleep(300);
				assertEquals(0, backend.requests.get());
			} finally {
				pool.close();
				system.shutdown();
				assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
			}
		}
	}

	@Test
	void aReplacedConnectionLeavesThePool() throws Exception {
		try (FakeBackend backend = new FakeBackend(false)) {
			InetSocketAddress address = new InetSocketAddress("127.0.0.1", backend.port);
			ConnectionPool<BackendClient> pool = ConnectionPool.open(() -> BackendClient.connect(address), 1,
					Thread.ofVirtual().factory());
			try {
				pool.give(pool.replace(pool.take(new FairPool.Waiter<>())));
				pool.give(pool.replace(pool.take(new FairPool.Waiter<>())));
				assertEquals(1, pool.size());
			} finally {
				pool.close();
			}
		}
	}

	@Test
	void cartDropsItsOldestLine() throws Exception {
		try (ServletBackend backend = ServletBackend.start(0, 1)) {
			try (Main.Running server = Main.start(new InetSocketAddress("127.0.0.1", backend.port()), 4, "jdk", 2,
					FRAUD)) {
				HttpResponse cart = null;
				for (int product = 0; product <= CartServlet.MAX_LINES; product++) {
					cart = server
							.handleRequest(new HttpRequest("POST", "/cart", 3L, form("product=" + product + "&qty=1")))
							.get(10, TimeUnit.SECONDS);
				}
				assertTrue(text(cart).contains("items=" + CartServlet.MAX_LINES + "\n"));
				assertFalse(text(cart).contains("product-0 "));
				assertTrue(text(cart).contains("product-" + CartServlet.MAX_LINES + " x1"));
			}
		}
	}

	@Test
	void serverAnswersProfileAndCart() throws Exception {
		try (ServletBackend backend = ServletBackend.start(0, 1)) {
			try (Main.Running server = Main.start(new InetSocketAddress("127.0.0.1", backend.port()), 4, "jdk", 4,
					FRAUD)) {
				HttpRequest profile = new HttpRequest("GET", "/profile", 42L, null);
				HttpResponse first = server.handleRequest(profile).get(10, TimeUnit.SECONDS);
				assertEquals(200, first.status());
				assertTrue(text(first).contains("visits=1"));
				HttpResponse second = server.handleRequest(new HttpRequest("GET", "/profile?viewed=5", 42L, null))
						.get(10, TimeUnit.SECONDS);
				assertTrue(text(second).contains("visits=2"));
				assertTrue(text(second).contains("product-5 " + Products.get(5).price() + "\n"));
				// 6, then 5 again: 5 moves back to the top, once.
				server.handleRequest(new HttpRequest("GET", "/profile?viewed=6", 42L, null)).get(10, TimeUnit.SECONDS);
				HttpResponse third = server.handleRequest(new HttpRequest("GET", "/profile?viewed=5", 42L, null))
						.get(10, TimeUnit.SECONDS);
				assertTrue(text(third).contains("visits=4\nproduct-5 " + Products.get(5).price() + "\nproduct-6 "
						+ Products.get(6).price() + "\nchecksum="), text(third));

				HttpResponse cart = server.handleRequest(new HttpRequest("POST", "/cart", 42L, form("product=7&qty=2")))
						.get(10, TimeUnit.SECONDS);
				assertEquals(200, cart.status());
				assertTrue(text(cart).contains("items=1"));
				// The same product again: the same line, more of it.
				cart = server.handleRequest(new HttpRequest("POST", "/cart", 42L, form("product=7&qty=1")))
						.get(10,
								TimeUnit.SECONDS);
				long price = Products.get(7).price();
				assertTrue(text(cart).contains("product-7 x3 " + price * 3 + "\n"), text(cart));
				assertTrue(text(cart).contains("items=1"));
				assertTrue(text(cart).contains("total=" + price * 3 + "\n"));
				assertEquals(400, server.handleRequest(new HttpRequest("POST", "/cart", 42L, form("product=7")))
						.get(10, TimeUnit.SECONDS)
						.status());
				for (String viewed : new String[]{"-5", "-1", "abc"}) {
					assertEquals(400,
							server.handleRequest(new HttpRequest("GET", "/profile?viewed=" + viewed, 42L, null))
									.get(10, TimeUnit.SECONDS)
									.status(),
							viewed);
				}
				assertEquals(404, server.handleRequest(new HttpRequest("GET", "/nope", 42L, null))
						.get(10, TimeUnit.SECONDS)
						.status());
				assertEquals(405, server.handleRequest(new HttpRequest("POST", "/profile", 42L, null))
						.get(10, TimeUnit.SECONDS)
						.status());
			}
		}
	}

	@Test
	void anUnreachableBackendStopsTheStart() throws Exception {
		int port;
		try (ServerSocket free = new ServerSocket(0)) {
			port = free.getLocalPort();
		}
		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> Main.start(new InetSocketAddress("127.0.0.1", port), 4, "jdk", 2, FRAUD));
		assertTrue(failure.getMessage().contains("a container could not start"), failure.getMessage());
	}

	private static String text(Messages.ServletResponse response) {
		return text(response.response());
	}

	private static String text(HttpResponse response) {
		return new String(response.body(), StandardCharsets.US_ASCII);
	}

	/** Records whatever it is sent. */
	private static final class Probe implements Actor {

		private final BlockingQueue<Message> seen;

		private Probe(BlockingQueue<Message> seen) {
			this.seen = seen;
		}

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) {
			seen.add(message);
		}
	}

	/**
	 * Accepts connections and answers each request with a four-byte body,
	 * {@code k=1}. Enough for a servlet to connect and to parse a checksum. A
	 * silent backend reads the requests and never answers.
	 */
	private static final class FakeBackend implements AutoCloseable {

		private final ServerSocket socket;

		private final Thread thread;

		private final boolean silent;

		private final AtomicInteger accepted = new AtomicInteger();

		private final AtomicInteger requests = new AtomicInteger();

		private volatile boolean running = true;

		private FakeBackend() throws IOException {
			this(false);
		}

		private FakeBackend(boolean silent) throws IOException {
			this.silent = silent;
			socket = new ServerSocket(0);
			int port = socket.getLocalPort();
			thread = Thread.ofPlatform().daemon(true).start(() -> serve());
			this.port = port;
		}

		private final int port;

		private void serve() {
			while (running) {
				try {
					Socket connection = socket.accept();
					accepted.incrementAndGet();
					Thread.ofPlatform().daemon(true).start(() -> answer(connection));
				} catch (IOException e) {
					return;
				}
			}
		}

		private void answer(Socket connection) {
			byte[] response = ("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nk=1\n").getBytes(StandardCharsets.US_ASCII);
			try (Socket open = connection;
					InputStream in = open.getInputStream();
					OutputStream out = open.getOutputStream()) {
				byte[] buf = new byte[8192];
				int seen = 0;
				while (true) {
					int n = in.read(buf, seen, buf.length - seen);
					if (n < 0) {
						return;
					}
					seen += n;
					int end = headerEnd(buf, seen);
					if (end < 0) {
						if (seen == buf.length) {
							return;
						}
						continue;
					}
					requests.incrementAndGet();
					if (!silent) {
						out.write(response);
						out.flush();
					}
					int rest = seen - end;
					System.arraycopy(buf, end, buf, 0, rest);
					seen = rest;
				}
			} catch (IOException e) {
				// The client closed.
			}
		}

		private static int headerEnd(byte[] buf, int seen) {
			for (int i = 3; i < seen; i++) {
				if (buf[i] == '\n' && buf[i - 1] == '\r' && buf[i - 2] == '\n' && buf[i - 3] == '\r') {
					return i + 1;
				}
			}
			return -1;
		}

		@Override
		public void close() throws IOException {
			running = false;
			socket.close();
			try {
				thread.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private static byte[] form(String text) {
		return text.getBytes(StandardCharsets.US_ASCII);
	}
}
