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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * The backend that every {@link UserGroup} calls before it answers. It is a
 * small HTTP server that answers {@code 200 OK} to everything. It runs in its
 * own process.
 *
 * <pre>
 * java -Dbench.backendPort=8081 -Dbench.backendThreads=2 -cp ... \
 *     io.github.pderop.looma.examples.user.BackendServer
 * </pre>
 *
 * <p>
 * One thread accepts the connections and gives each one to a reactor thread.
 * There are {@code bench.backendThreads} reactors. A reactor reads the requests
 * and writes one response per request, right away. It does not parse the
 * requests: it only counts them.
 */
public final class BackendServer {

	/** The only response this server sends. */
	private static final ByteBuffer RESPONSE = response("ok");

	private static ByteBuffer response(String body) {
		byte[] bytes = ("HTTP/1.1 200 OK\r\n" + "Content-Type: text/plain\r\n" + "Content-Length: " + body.length()
				+ "\r\n" + "\r\n" + body).getBytes(StandardCharsets.US_ASCII);
		ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length);
		direct.put(bytes).flip();
		return direct.asReadOnlyBuffer();
	}

	private static final int READ_BUFFER_BYTES = 16 * 1024;

	private static final int INITIAL_WRITE_BUFFER_BYTES = 4 * 1024;

	private final ServerSocketChannel serverChannel;

	private final Reactor[] reactors;

	private volatile boolean running = true;

	private BackendServer(int port, int backlog, int threads) throws IOException {
		this.serverChannel = ServerSocketChannel.open();
		this.serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
		this.serverChannel.bind(new InetSocketAddress(port), backlog);

		this.reactors = new Reactor[threads];
		for (int i = 0; i < threads; i++) {
			this.reactors[i] = new Reactor(i);
		}
	}

	public static void main(String[] args) throws Exception {
		int port = Integer.getInteger("bench.backendPort", 8081);
		int threads = Integer.getInteger("bench.backendThreads", 1);
		int backlog = 4096;

		BackendServer backend = new BackendServer(port, backlog, threads);
		Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(backend::stop));
		backend.start();

		// The script waits for this line. It is printed after the bind, so the port is
		// ready.
		System.out.printf("backend server listening on port %d -- threads=%d responseBytes=%d%n", port, threads,
				RESPONSE.remaining());
		System.out.flush();
		backend.acceptLoop();
	}

	private void start() {
		for (Reactor reactor : reactors) {
			reactor.start();
		}
	}

	/** Accepts the connections and gives each one to the next reactor. */
	private void acceptLoop() {
		long seq = 0;
		while (running) {
			SocketChannel channel;
			try {
				channel = serverChannel.accept();
			} catch (ClosedChannelException e) {
				// stop() closed the socket: stop accepting.
				return;
			} catch (IOException e) {
				// Only this accept failed: keep going.
				if (!running) {
					return;
				}
				System.err.println("backend server accept failed, continuing: " + e);
				try {
					TimeUnit.MILLISECONDS.sleep(10);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return;
				}
				continue;
			}

			try {
				channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
				channel.configureBlocking(false);
				reactors[(int) (seq++ % reactors.length)].add(channel);
			} catch (IOException | RuntimeException e) {
				System.err.println("backend server dropping a connection it could not hand to a reactor: " + e);
				closeQuietly(channel);
			}
		}
	}

	private static void closeQuietly(SocketChannel channel) {
		try {
			channel.close();
		} catch (IOException e) {
			// Already gone.
		}
	}

	private void stop() {
		running = false;
		try {
			serverChannel.close();
		} catch (IOException e) {
			// Shutting down anyway.
		}
		for (Reactor reactor : reactors) {
			reactor.stop();
		}
	}

	/** A reactor: one thread, one selector, some of the connections. */
	private final class Reactor implements Runnable {

		private final int index;

		private final Selector selector;

		private final ConcurrentLinkedQueue<SocketChannel> pending = new ConcurrentLinkedQueue<>();

		Reactor(int index) throws IOException {
			this.index = index;
			this.selector = Selector.open();
		}

		void start() {
			Thread.ofPlatform().name("backend-" + index).daemon(true).start(this);
		}

		void add(SocketChannel channel) {
			pending.add(channel);
			selector.wakeup();
		}

		/**
		 * Only wakes the reactor up. The reactor then sees that it must stop, and
		 * closes its selector itself.
		 */
		void stop() {
			selector.wakeup();
		}

		@Override
		public void run() {
			try {
				while (running) {
					try {
						selector.select();
						registerPending();
						pollReady();
					} catch (ClosedSelectorException e) {
						return;
					} catch (IOException e) {
						// The selector failed: this reactor and its connections stop.
						if (running) {
							System.err.println("backend server reactor " + index + " stopping on: " + e);
						}
						return;
					}
				}
			} finally {
				try {
					selector.close();
				} catch (IOException e) {
					// Shutting down anyway.
				}
			}
		}

		/**
		 * Registers the new connections. If one fails, only that connection is closed.
		 */
		private void registerPending() {
			SocketChannel channel;
			while ((channel = pending.poll()) != null) {
				try {
					Conn conn = new Conn(channel);
					conn.key = channel.register(selector, SelectionKey.OP_READ, conn);
				} catch (ClosedSelectorException e) {
					throw e;
				} catch (IOException | RuntimeException e) {
					closeQuietly(channel);
				}
			}
		}

		private void pollReady() {
			Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
			while (keys.hasNext()) {
				SelectionKey key = keys.next();
				keys.remove();
				Conn conn = (Conn) key.attachment();
				try {
					if (key.isReadable()) {
						conn.read();
					}
					if (key.isValid() && key.isWritable()) {
						conn.flush();
					}
				} catch (IOException | CancelledKeyException e) {
					conn.close();
				}
			}
		}
	}

	/** One connection: its buffers and its read state. */
	private static final class Conn {

		private final SocketChannel channel;

		private final ByteBuffer in = ByteBuffer.allocateDirect(READ_BUFFER_BYTES);

		private ByteBuffer out = ByteBuffer.allocateDirect(INITIAL_WRITE_BUFFER_BYTES);

		private SelectionKey key;

		/** How many bytes of the empty line that ends a request we have seen so far. */
		private int terminator;

		Conn(SocketChannel channel) {
			this.channel = channel;
		}

		/** Reads what arrived, and answers every complete request. */
		void read() throws IOException {
			in.clear();
			int read = channel.read(in);
			if (read < 0) {
				close();
				return;
			}
			in.flip();
			int requests = countRequests();
			if (requests == 0) {
				return; // the request is not complete yet
			}
			for (int i = 0; i < requests; i++) {
				appendResponse();
			}
			flush();
		}

		/**
		 * Counts the complete requests in the bytes read. A request can be split over
		 * two reads, so the count continues from the last read.
		 */
		private int countRequests() {
			int requests = 0;
			while (in.hasRemaining()) {
				byte b = in.get();
				if (b == ((terminator & 1) == 0 ? '\r' : '\n')) {
					if (++terminator == 4) {
						terminator = 0;
						requests++;
					}
				} else {
					terminator = b == '\r' ? 1 : 0;
				}
			}
			return requests;
		}

		/**
		 * Adds one response to the output buffer. With a buffer, a partial write is not
		 * a problem.
		 */
		private void appendResponse() {
			ByteBuffer response = RESPONSE.duplicate();
			ensureCapacity(response.remaining());
			out.put(response);
		}

		void flush() throws IOException {
			out.flip();
			try {
				channel.write(out);
			} finally {
				out.compact();
			}
			int interest = out.position() == 0 ? SelectionKey.OP_READ : SelectionKey.OP_READ | SelectionKey.OP_WRITE;
			if (key.interestOps() != interest) {
				key.interestOps(interest);
			}
		}

		private void ensureCapacity(int extra) {
			if (out.remaining() >= extra) {
				return;
			}
			ByteBuffer grown = ByteBuffer.allocateDirect(Math.max(out.capacity() * 2, out.position() + extra));
			out.flip();
			grown.put(out);
			out = grown;
		}

		void close() {
			if (key != null) {
				key.cancel();
			}
			closeQuietly(channel);
		}
	}
}
