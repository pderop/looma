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

import java.io.EOFException;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * One connection to {@link ServletBackend}. It sends one GET and waits for the
 * whole response. The wait blocks on the socket, so the virtual thread parks.
 *
 * <p>
 * A connection carries one request at a time. The padding is an {@code X-Pad}
 * header, so the request has no body and the backend only has to find the empty
 * line. The buffer grows to hold the response.
 */
final class BackendClient implements Closeable {

	/** The header name, in lower case. */
	private static final byte[] CONTENT_LENGTH = "content-length:".getBytes(StandardCharsets.US_ASCII);

	private final SocketChannel channel;

	/** The request line up to the size asked. */
	private static final byte[] REQUEST_START = "GET /data?bytes=".getBytes(StandardCharsets.US_ASCII);

	/** The end of the headers. */
	private static final byte[] REQUEST_END = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

	/** The padding is cut from this: the longest one. */
	private static final byte[] PADDING = "x".repeat(BackendCalls.PAD_MAX).getBytes(StandardCharsets.US_ASCII);

	/** From the end of the size asked to the padding: the same for each request. */
	private final byte[] requestMiddle;

	/**
	 * The request being sent, one at a time. Made once, big enough for the longest
	 * request.
	 */
	private final ByteBuffer request;

	/** The bytes received and not yet used. */
	private ByteBuffer response = ByteBuffer.allocate(8192);

	private BackendClient(SocketChannel channel, InetSocketAddress address) {
		this.channel = channel;
		this.requestMiddle = (" HTTP/1.1\r\nHost: " + address.getHostString() + ":" + address.getPort() + "\r\nX-Pad: ")
				.getBytes(StandardCharsets.US_ASCII);
		// 10: the most digits of an int.
		this.request = ByteBuffer
				.allocateDirect(REQUEST_START.length + 10 + requestMiddle.length + PADDING.length + REQUEST_END.length);
	}

	/** Opens the connection and leaves it blocking. */
	static BackendClient connect(InetSocketAddress address) throws IOException {
		SocketChannel channel = SocketChannel.open();
		try {
			channel.connect(address);
			channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
			// Closing resets the connection, so it leaves no TIME_WAIT behind.
			channel.setOption(StandardSocketOptions.SO_LINGER, 0);
		} catch (IOException | RuntimeException e) {
			try {
				channel.close();
			} catch (IOException ignored) {
				// We report the connect failure, not this one.
			}
			throw e;
		}
		return new BackendClient(channel, address);
	}

	/**
	 * Sends one GET asking for {@code bytes} of body, with {@code pad} bytes of
	 * padding, and waits for the whole response. Copies the body into {@code body}
	 * and returns its length. The caller parses it, so the read cannot be removed.
	 *
	 * @throws IOException
	 *             if the body does not fit in {@code body}
	 */
	int get(int bytes, int pad, byte[] body) throws IOException {
		ByteBuffer request = request(bytes, pad);
		while (request.hasRemaining()) {
			channel.write(request);
		}
		return readResponse(body);
	}

	/**
	 * The request, written in {@link #request}: the fixed parts are made once, the
	 * size and the padding are written in place. Nothing is allocated.
	 */
	private ByteBuffer request(int bytes, int pad) {
		if (pad < 0 || pad > PADDING.length) {
			throw new IllegalArgumentException("pad must be 0 to " + PADDING.length + ", not " + pad);
		}
		request.clear();
		request.put(REQUEST_START);
		putDigits(request, bytes);
		request.put(requestMiddle);
		request.put(PADDING, 0, pad);
		request.put(REQUEST_END);
		return request.flip();
	}

	/** {@code value}, not negative, in decimal. */
	private static void putDigits(ByteBuffer buf, int value) {
		int digits = 1;
		for (int v = value; v >= 10; v /= 10) {
			digits++;
		}
		int at = buf.position() + digits;
		for (int i = 1; i <= digits; i++) {
			buf.put(at - i, (byte) ('0' + value % 10));
			value /= 10;
		}
		buf.position(at);
	}

	private int readResponse(byte[] body) throws IOException {
		while (true) {
			int bodyStart = endOfHeaders();
			if (bodyStart >= 0) {
				int length = contentLength(bodyStart);
				if (length > body.length) {
					throw new IOException("backend body of " + length + " bytes, the buffer holds " + body.length);
				}
				int total = bodyStart + length;
				// A blocking read of a full buffer returns 0. Grow, or this loop never ends
				// and the virtual thread keeps its carrier.
				while (response.position() < total) {
					if (!response.hasRemaining()) {
						grow();
					}
					fill();
				}
				response.flip();
				response.position(bodyStart);
				response.get(body, 0, length);
				response.compact();
				return length;
			}
			if (!response.hasRemaining()) {
				grow();
			}
			fill();
		}
	}

	private void fill() throws IOException {
		if (channel.read(response) < 0) {
			throw new EOFException("backend closed the connection");
		}
	}

	private void grow() {
		ByteBuffer bigger = ByteBuffer.allocate(response.capacity() * 2);
		response.flip();
		bigger.put(response);
		response = bigger;
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
		throw new IOException("backend response has no Content-Length");
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
			throw new IOException("backend response has an unreadable Content-Length");
		}
		return value;
	}

	private static byte lower(byte b) {
		return b >= 'A' && b <= 'Z' ? (byte) (b + ('a' - 'A')) : b;
	}

	public void close() {
		try {
			channel.close();
		} catch (IOException e) {
			// The run is over.
		}
	}
}
