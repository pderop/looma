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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * A small HTTP server on NIO, for the backends of the benchmarks. No actor, no
 * virtual thread.
 *
 * <pre>
 * NioServer server = new NioServer("backend", 2).route("/user", request -&gt; OK).start(8081);
 * </pre>
 *
 * <p>
 * Each route is a path and a handler. The handler gets the request and returns
 * the body of the response. The server sends it with {@code 200 OK}. A path
 * with no route gets {@code 404 Not Found}.
 *
 * <p>
 * One thread accepts the connections and gives each one to a reactor thread. A
 * reactor reads the requests and answers each one as soon as its headers have
 * arrived. It does not read a request body: the clients send GETs. A connection
 * can carry many requests, one after the other.
 */
public final class NioServer implements AutoCloseable {

	/** Answers one request. */
	@FunctionalInterface
	public interface Handler {

		/**
		 * The body of the response. The server copies it without moving its position:
		 * the same buffer can be returned to every request, from every reactor.
		 */
		ByteBuffer handle(Request request);
	}

	/**
	 * One request, read where it lies in the connection's buffer. It is only valid
	 * during the call to the handler.
	 */
	public static final class Request {

		private ByteBuffer buf;

		private int queryFrom;

		private int queryTo;

		private Request() {
		}

		/**
		 * The value of {@code name} in the query, as a number. {@code defaultValue} if
		 * it is not there, or has no digits.
		 */
		public int intParam(String name, int defaultValue) {
			int at = queryFrom;
			while (at < queryTo) {
				int end = indexOf(buf, at, queryTo, (byte) '&');
				if (end < 0) {
					end = queryTo;
				}
				if (isParam(at, end, name)) {
					return digits(at + name.length() + 1, end, defaultValue);
				}
				at = end + 1;
			}
			return defaultValue;
		}

		/**
		 * True if the bytes from {@code from} to {@code to} start with {@code name=}.
		 */
		private boolean isParam(int from, int to, String name) {
			if (to - from <= name.length() || buf.get(from + name.length()) != '=') {
				return false;
			}
			for (int i = 0; i < name.length(); i++) {
				if (buf.get(from + i) != name.charAt(i)) {
					return false;
				}
			}
			return true;
		}

		private int digits(int from, int to, int defaultValue) {
			int value = 0;
			boolean digit = false;
			for (int i = from; i < to; i++) {
				byte c = buf.get(i);
				if (c < '0' || c > '9') {
					break;
				}
				digit = true;
				value = value * 10 + (c - '0');
			}
			return digit ? value : defaultValue;
		}
	}

	private static final byte[] HEAD_200 = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "
			.getBytes(StandardCharsets.US_ASCII);

	private static final byte[] HEAD_404 = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: "
			.getBytes(StandardCharsets.US_ASCII);

	private static final byte[] END_OF_HEAD = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

	private static final ByteBuffer NOT_FOUND = text("not found");

	private static final int READ_BUFFER_BYTES = 16 * 1024;

	private static final int INITIAL_WRITE_BUFFER_BYTES = 4 * 1024;

	private static final int BACKLOG = 4096;

	private final String name;

	private final Reactor[] reactors;

	private final List<String> paths = new ArrayList<>();

	private final List<Handler> handlers = new ArrayList<>();

	/** The routes, fixed by {@link #start(int)}. */
	private byte[][] routePaths;

	private Handler[] routeHandlers;

	private ServerSocketChannel serverChannel;

	private volatile boolean running = true;

	/**
	 * A server with {@code reactors} reactor threads. {@code name} names its
	 * threads and its messages.
	 */
	public NioServer(String name, int reactors) {
		if (reactors < 1) {
			throw new IllegalArgumentException("reactors must be at least 1: " + reactors);
		}
		this.name = name;
		this.reactors = new Reactor[reactors];
	}

	/** A body made of {@code text}, to return from a handler. */
	public static ByteBuffer text(String text) {
		byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
		return ByteBuffer.allocateDirect(bytes.length).put(bytes).flip().asReadOnlyBuffer();
	}

	/**
	 * Sends the requests for {@code path} to {@code handler}. The path does not
	 * include the query. Call it before {@link #start(int)}.
	 */
	public NioServer route(String path, Handler handler) {
		if (serverChannel != null) {
			throw new IllegalStateException("add the routes before start");
		}
		paths.add(path);
		handlers.add(handler);
		return this;
	}

	/** Listens on {@code port} (0 picks one), and starts the threads. */
	public NioServer start(int port) throws IOException {
		if (serverChannel != null) {
			throw new IllegalStateException("already started");
		}
		routePaths = new byte[paths.size()][];
		for (int i = 0; i < routePaths.length; i++) {
			routePaths[i] = paths.get(i).getBytes(StandardCharsets.US_ASCII);
		}
		routeHandlers = handlers.toArray(new Handler[0]);
		serverChannel = ServerSocketChannel.open();
		serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
		serverChannel.bind(new InetSocketAddress(port), BACKLOG);
		for (int i = 0; i < reactors.length; i++) {
			reactors[i] = new Reactor(i);
			reactors[i].start();
		}
		Thread.ofPlatform().name(name + "-accept").daemon(true).start(this::acceptLoop);
		return this;
	}

	/** The port it listens on. */
	public int port() throws IOException {
		return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
	}

	/** Stops accepting, and makes the reactors close their connections. */
	@Override
	public void close() {
		running = false;
		try {
			if (serverChannel != null) {
				serverChannel.close();
			}
		} catch (IOException e) {
			// Shutting down anyway.
		}
		for (Reactor reactor : reactors) {
			if (reactor != null) {
				reactor.wakeup();
			}
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
				// close() closed the socket: stop accepting.
				return;
			} catch (IOException e) {
				// Only this accept failed: keep going.
				if (!running) {
					return;
				}
				System.err.println(name + " accept failed, continuing: " + e);
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
				System.err.println(name + " dropping a connection it could not hand to a reactor: " + e);
				closeQuietly(channel);
			}
		}
	}

	/** The handler of the path from {@code from} to {@code to}, or null. */
	private Handler handler(ByteBuffer buf, int from, int to) {
		for (int r = 0; r < routePaths.length; r++) {
			byte[] path = routePaths[r];
			if (path.length != to - from) {
				continue;
			}
			int i = 0;
			while (i < path.length && buf.get(from + i) == path[i]) {
				i++;
			}
			if (i == path.length) {
				return routeHandlers[r];
			}
		}
		return null;
	}

	/**
	 * Where {@code b} is in {@code buf}, between {@code from} and {@code to}, or
	 * -1.
	 */
	private static int indexOf(ByteBuffer buf, int from, int to, byte b) {
		for (int i = from; i < to; i++) {
			if (buf.get(i) == b) {
				return i;
			}
		}
		return -1;
	}

	private static void closeQuietly(SocketChannel channel) {
		try {
			channel.close();
		} catch (IOException e) {
			// Already gone.
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
			Thread.ofPlatform().name(name + "-" + index).daemon(true).start(this);
		}

		void add(SocketChannel channel) {
			pending.add(channel);
			selector.wakeup();
		}

		/** The reactor then sees that it must stop, and closes its selector itself. */
		void wakeup() {
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
							System.err.println(name + " reactor " + index + " stopping on: " + e);
						}
						return;
					}
				}
			} finally {
				// Close the connections too: a client still waiting on one then gets an end
				// of stream instead of waiting forever.
				try {
					for (SelectionKey key : selector.keys()) {
						((Conn) key.attachment()).close();
					}
				} catch (ClosedSelectorException e) {
					// Already closed.
				}
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
				} catch (IOException e) {
					conn.close();
				} catch (RuntimeException e) {
					// A handler failed, or the key was cancelled: one connection fails, not the
					// reactor.
					if (key.isValid()) {
						System.err.println(name + " closing a connection on: " + e);
					}
					conn.close();
				}
			}
		}
	}

	/** One connection: its buffers, and the request being answered. */
	private final class Conn {

		private final SocketChannel channel;

		private final Request request = new Request();

		private ByteBuffer in = ByteBuffer.allocateDirect(READ_BUFFER_BYTES);

		private ByteBuffer out = ByteBuffer.allocateDirect(INITIAL_WRITE_BUFFER_BYTES);

		private SelectionKey key;

		Conn(SocketChannel channel) {
			this.channel = channel;
		}

		/** Reads what arrived, and answers every complete request. */
		void read() throws IOException {
			if (!in.hasRemaining()) {
				growIn();
			}
			int read = channel.read(in);
			if (read < 0) {
				close();
				return;
			}
			while (respondOne()) {
				// One read can carry several requests.
			}
			flush();
		}

		/**
		 * Answers the first complete request in {@code in}. False if none is complete.
		 */
		private boolean respondOne() {
			in.flip();
			int end = endOfHeaders(in);
			if (end < 0) {
				in.compact();
				return false;
			}
			// The request line: METHOD SP path[?query] SP version.
			int pathFrom = indexOf(in, in.position(), end, (byte) ' ') + 1;
			int lineEnd = indexOf(in, pathFrom, end, (byte) ' ');
			if (pathFrom == 0 || lineEnd < 0) {
				lineEnd = pathFrom;
			}
			int query = indexOf(in, pathFrom, lineEnd, (byte) '?');
			int pathTo = query < 0 ? lineEnd : query;
			Handler handler = handler(in, pathFrom, pathTo);
			if (handler == null) {
				appendResponse(HEAD_404, NOT_FOUND);
			} else {
				request.buf = in;
				request.queryFrom = query < 0 ? lineEnd : query + 1;
				request.queryTo = lineEnd;
				try {
					appendResponse(HEAD_200, handler.handle(request));
				} finally {
					request.buf = null;
				}
			}
			in.position(end);
			in.compact();
			return true;
		}

		/** Just after the empty line that ends the headers, or -1. */
		private static int endOfHeaders(ByteBuffer buf) {
			for (int i = buf.position() + 3; i < buf.limit(); i++) {
				if (buf.get(i) == '\n' && buf.get(i - 1) == '\r' && buf.get(i - 2) == '\n' && buf.get(i - 3) == '\r') {
					return i + 1;
				}
			}
			return -1;
		}

		/**
		 * Adds one response to {@code out}. With a buffer, a partial write is not a
		 * problem.
		 */
		private void appendResponse(byte[] head, ByteBuffer body) {
			int size = body.remaining();
			ensureCapacity(head.length + 10 + END_OF_HEAD.length + size);
			out.put(head);
			putDecimal(size);
			out.put(END_OF_HEAD);
			out.put(out.position(), body, body.position(), size);
			out.position(out.position() + size);
		}

		/** Writes {@code value} in decimal, without making a string. */
		private void putDecimal(int value) {
			int digits = 1;
			for (int v = value; v >= 10; v /= 10) {
				digits++;
			}
			int at = out.position() + digits;
			for (int v = value, i = 1; i <= digits; v /= 10, i++) {
				out.put(at - i, (byte) ('0' + v % 10));
			}
			out.position(at);
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

		private void growIn() {
			ByteBuffer grown = ByteBuffer.allocateDirect(in.capacity() * 2);
			in.flip();
			grown.put(in);
			in = grown;
		}

		void close() {
			if (key != null) {
				key.cancel();
			}
			closeQuietly(channel);
		}
	}
}
