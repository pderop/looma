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

import java.nio.ByteBuffer;

import io.github.pderop.looma.examples.utils.NioServer;

/**
 * The backend that every {@link UserGroupActor} calls before it answers. It
 * runs in its own process, on a {@link NioServer}. It answers {@code GET /user}
 * with {@code ok}.
 *
 * <pre>
 * java -Dbench.backendPort=8081 -Dbench.backendThreads=1 -cp ... \
 *     io.github.pderop.looma.examples.user.BackendServer
 * </pre>
 */
public final class BackendServer {

	/** The only body this server sends. */
	private static final ByteBuffer OK = NioServer.text("ok");

	private BackendServer() {
	}

	public static void main(String[] args) throws Exception {
		int port = Integer.getInteger("bench.backendPort", 8081);
		int threads = Integer.getInteger("bench.backendThreads", 1);
		NioServer server = new NioServer("backend", threads).route("/user", request -> OK).start(port);
		Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(server::close));
		// The script waits for this line. It is printed after the bind, so the port is
		// ready.
		System.out.printf("backend server listening on port %d -- threads=%d%n", port, threads);
		System.out.flush();
		Thread.currentThread().join();
	}
}
