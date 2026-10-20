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

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * One connection to {@link BackendServer}. It sends a GET and waits for the
 * response. The wait blocks on the socket, so the virtual thread parks.
 *
 * <p>
 * A connection carries one request at a time. It only understands the responses
 * {@link BackendServer} sends: headers, then a body of {@code Content-Length}
 * bytes.
 */
final class Upstream {

	/** Big enough for several responses. */
	private static final int BUFFER_BYTES = 4096;

	/** The header name, in lower case. */
	private static final byte[] CONTENT_LENGTH = "content-length:".getBytes(StandardCharsets.US_ASCII);

	private final SocketChannel channel;

	/** The GET request, always the same. */
	private final ByteBuffer request;

	/**
	 * The bytes received and not yet used. They may include the start of the next
	 * response.
	 */
	private final ByteBuffer response = ByteBuffer.allocate(BUFFER_BYTES);

	private Upstream(SocketChannel channel, ByteBuffer request) {
		this.channel = channel;
		this.request = request;
	}

	/** Opens the connection and leaves it blocking. */
	static Upstream connect(InetSocketAddress address) throws IOException {
		SocketChannel channel = SocketChannel.open();
		try {
			channel.connect(address);
			channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
		} catch (IOException | RuntimeException e) {
			try {
				channel.close();
			} catch (IOException ignored) {
				// We report the connect failure, not this one.
			}
			throw e;
		}
		String text = "GET /user HTTP/1.1\r\nHost: " + address.getHostString() + ":" + address.getPort() + "\r\n\r\n";
		return new Upstream(channel, ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)));
	}

	/**
	 * Sends one GET and waits for the whole response. Returns its size in bytes;
	 * the caller adds it to its checksum, so the GET cannot be removed.
	 */
	int get() throws IOException {
		request.rewind();
		while (request.hasRemaining()) {
			channel.write(request);
		}
		return readResponse();
	}

	private int readResponse() throws IOException {
		while (true) {
			int bodyStart = endOfHeaders();
			if (bodyStart >= 0) {
				int total = bodyStart + contentLength(bodyStart);
				if (total > response.capacity()) {
					throw new IOException(
							"upstream response of " + total + " bytes does not fit in " + response.capacity());
				}
				while (response.position() < total) {
					fill();
				}
				response.flip();
				response.position(total);
				response.compact();
				return total;
			}
			if (!response.hasRemaining()) {
				throw new IOException("upstream response headers exceed " + response.capacity() + " bytes");
			}
			fill();
		}
	}

	private void fill() throws IOException {
		if (channel.read(response) < 0) {
			throw new EOFException("upstream closed the connection");
		}
	}

	/**
	 * Where the body starts (after the empty line that ends the headers), or -1.
	 */
	private int endOfHeaders() {
		int held = response.position();
		for (int i = 3; i < held; i++) {
			if (response.get(i) == '\n' && response.get(i - 1) == '\r' && response.get(i - 2) == '\n'
					&& response.get(i - 3) == '\r') {
				return i + 1;
			}
		}
		return -1;
	}

	/** The {@code Content-Length} value. The header name is case-insensitive. */
	private int contentLength(int bodyStart) throws IOException {
		for (int i = 0; i <= bodyStart - CONTENT_LENGTH.length; i++) {
			int j = 0;
			while (j < CONTENT_LENGTH.length && lower(response.get(i + j)) == CONTENT_LENGTH[j]) {
				j++;
			}
			if (j == CONTENT_LENGTH.length) {
				return parseInt(i + CONTENT_LENGTH.length, bodyStart);
			}
		}
		throw new IOException("upstream response has no Content-Length");
	}

	private int parseInt(int from, int to) throws IOException {
		int value = -1;
		for (int i = from; i < to; i++) {
			byte b = response.get(i);
			if (b >= '0' && b <= '9') {
				value = (value < 0 ? 0 : value * 10) + (b - '0');
			} else if (value >= 0 || (b != ' ' && b != '\t')) {
				break;
			}
		}
		if (value < 0) {
			throw new IOException("upstream response has an unreadable Content-Length");
		}
		return value;
	}

	private static byte lower(byte b) {
		return b >= 'A' && b <= 'Z' ? (byte) (b + ('a' - 'A')) : b;
	}

	void close() {
		try {
			channel.close();
		} catch (IOException e) {
			// The run is over.
		}
	}
}
