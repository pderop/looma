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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** {@link NioServer} over a real socket. */
class NioServerTest {

	private static NioServer server() throws IOException {
		return new NioServer("test", 1).route("/hello", request -> NioServer.text("hello"))
				.route("/size", request -> NioServer.text("n=" + request.intParam("n", -1)))
				.start(0);
	}

	@Test
	void answersARoute() throws Exception {
		try (NioServer server = server(); Socket socket = new Socket("127.0.0.1", server.port())) {
			send(socket, "GET /hello HTTP/1.1\r\nHost: x\r\n\r\n");
			assertEquals("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 5\r\n\r\nhello",
					receive(socket, 1));
		}
	}

	@Test
	void anUnknownPathGets404() throws Exception {
		try (NioServer server = server(); Socket socket = new Socket("127.0.0.1", server.port())) {
			send(socket, "GET /nope HTTP/1.1\r\n\r\n");
			assertEquals("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: 9\r\n\r\nnot found",
					receive(socket, 1));
		}
	}

	@Test
	void readsAParamOfTheQuery() throws Exception {
		try (NioServer server = server(); Socket socket = new Socket("127.0.0.1", server.port())) {
			send(socket, "GET /size?a=1&n=1234&nn=5 HTTP/1.1\r\n\r\n");
			assertEquals(body("n=1234"), receive(socket, 1));
			send(socket, "GET /size?nn=5 HTTP/1.1\r\n\r\n");
			assertEquals(body("n=-1"), receive(socket, 1));
			send(socket, "GET /size HTTP/1.1\r\n\r\n");
			assertEquals(body("n=-1"), receive(socket, 1));
		}
	}

	@Test
	void answersRequestsSentTogetherAndInPieces() throws Exception {
		try (NioServer server = server(); Socket socket = new Socket("127.0.0.1", server.port())) {
			send(socket, "GET /size?n=1 HTTP/1.1\r\n\r\nGET /size?n=2 HTTP/1.1\r\n\r\nGET /si");
			Thread.sleep(50);
			send(socket, "ze?n=3 HTTP/1.1\r\n\r\n");
			assertEquals(body("n=1") + body("n=2") + body("n=3"), receive(socket, 3));
		}
	}

	private static String body(String text) {
		return "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + text.length() + "\r\n\r\n" + text;
	}

	private static void send(Socket socket, String text) throws IOException {
		OutputStream out = socket.getOutputStream();
		out.write(text.getBytes(StandardCharsets.US_ASCII));
		out.flush();
	}

	/** Reads {@code responses} responses, each one to the end of its body. */
	private static String receive(Socket socket, int responses) throws IOException {
		socket.setSoTimeout(5000);
		InputStream in = socket.getInputStream();
		ByteArrayOutputStream got = new ByteArrayOutputStream();
		for (int r = 0; r < responses; r++) {
			ByteArrayOutputStream head = new ByteArrayOutputStream();
			while (!head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
				head.write(in.read());
			}
			String text = head.toString(StandardCharsets.US_ASCII);
			int at = text.indexOf("Content-Length: ") + "Content-Length: ".length();
			int length = Integer.parseInt(text.substring(at, text.indexOf('\r', at)));
			got.writeBytes(head.toByteArray());
			got.writeBytes(in.readNBytes(length));
		}
		return got.toString(StandardCharsets.US_ASCII);
	}
}
