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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import io.github.pderop.looma.examples.utils.NioServer;

/**
 * The backend every servlet calls. It runs in its own process, on a
 * {@link NioServer}: no actor, no virtual thread.
 *
 * <pre>
 * java -Dservlet.backendPort=8081 -Dservlet.backendThreads=2 -cp ... \
 *     io.github.pderop.looma.examples.servlet.ServletBackend
 * </pre>
 *
 * <p>
 * It answers {@code GET /data?bytes=N} with a body of N bytes made of
 * {@code k=1} lines. All bodies are cut from one shared buffer.
 */
public final class ServletBackend implements AutoCloseable {

	private static final int DEFAULT_BYTES = 256;

	/** The body all responses are cut from. See {@link #body(int)}. */
	private static volatile ByteBuffer body = ByteBuffer.allocateDirect(0).asReadOnlyBuffer();

	private final NioServer server;

	private ServletBackend(NioServer server) {
		this.server = server;
	}

	/**
	 * Starts a backend on {@code port} (0 picks one) with {@code threads} reactors.
	 */
	public static ServletBackend start(int port, int threads) throws IOException {
		NioServer server = new NioServer("servlet-backend", threads).route("/data", request -> {
			int size = request.intParam("bytes", DEFAULT_BYTES);
			return body(size > 0 ? size : DEFAULT_BYTES);
		}).start(port);
		return new ServletBackend(server);
	}

	public int port() throws IOException {
		return server.port();
	}

	public static void main(String[] args) throws Exception {
		int port = Integer.getInteger("servlet.backendPort", 8081);
		int threads = Integer.getInteger("servlet.backendThreads", 2);
		ServletBackend backend = start(port, threads);
		Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(backend::close));
		// The script waits for this line. It is printed after the bind.
		System.out.printf("servlet backend listening on port %d%n", backend.port());
		System.out.flush();
		Thread.currentThread().join();
	}

	/** Stops accepting, and closes the connections. */
	@Override
	public void close() {
		server.close();
	}

	/**
	 * The first {@code size} bytes of the shared body. Every body is a run of
	 * {@code k=1} lines, so one buffer serves all sizes. It grows to the largest
	 * size asked, and no further.
	 */
	private static ByteBuffer body(int size) {
		ByteBuffer shared = body;
		if (shared.capacity() < size) {
			shared = grow(size);
		}
		return shared.slice(0, size);
	}

	private static synchronized ByteBuffer grow(int size) {
		if (body.capacity() >= size) {
			return body;
		}
		byte[] line = "k=1\n".getBytes(StandardCharsets.US_ASCII);
		ByteBuffer bigger = ByteBuffer.allocateDirect(Math.max(size, body.capacity() * 2));
		for (int i = 0; i < bigger.capacity(); i++) {
			bigger.put(line[i % line.length]);
		}
		body = bigger.flip().asReadOnlyBuffer();
		return body;
	}
}
