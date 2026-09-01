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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Counts complete HTTP/1.1 request heads in a byte stream, and frames the JSON
 * responses this example writes back.
 *
 * <p>
 * <b>This is not a conformant HTTP parser and must not be mistaken for one.</b>
 * It recognises exactly one thing — a request head terminated by
 * {@code CRLFCRLF} — and it neither validates the request line nor reads a
 * body. That is sufficient because its only client is a benchmark client
 * ({@code wrk}) issuing pipelined {@code GET}s, and because what this example
 * measures is where the work runs, not how well it parses. A request carrying a
 * body would be silently mis-framed.
 *
 * <p>
 * <b>Pipelining is the whole reason this class buffers.</b> One
 * {@code SocketChannel.read} can deliver several complete requests at once
 * (that is what {@code -m <N>} produces), a fraction of one, or a boundary
 * split across two reads. {@link #feed(ByteBuffer)} therefore accumulates,
 * counts every boundary it can see, and keeps whatever tail is left over for
 * the next call.
 *
 * <p>
 * Not thread-safe: one instance belongs to one connection's reader thread.
 */
final class Http1Codec {

	/**
	 * Everything of a 200 that does not depend on the body. Read-only and never
	 * dirtied, so the shared half of the response contributes no coherence traffic
	 * to the measurement; only the body, which is per-request garbage anyway, is
	 * built per write.
	 */
	private static final byte[] RESPONSE_HEAD = ("HTTP/1.1 200 OK\r\n" + "Content-Type: application/json\r\n"
			+ "Content-Length: ").getBytes(StandardCharsets.US_ASCII);

	private static final byte[] HEAD_END = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

	private static final int INITIAL_CAPACITY = 8192;

	/**
	 * Ceiling on the bytes held while waiting for a {@code CRLFCRLF}.
	 *
	 * <p>
	 * Without it the accumulator grows for as long as a peer keeps sending, so
	 * anything that never terminates a head — a stuck client, a port scanner, a TLS
	 * ClientHello arriving on a plaintext port — walks the server into an
	 * {@link OutOfMemoryError}. This binds a public port, so that has to be
	 * bounded. Generous against the readers that feed it, which hand over at most
	 * 16 KiB per call and leave only an unfinished head behind: reaching 64 KiB
	 * means the stream is not the HTTP this class claims to frame.
	 */
	private static final int MAX_PENDING_BYTES = 64 * 1024;

	private byte[] buffer = new byte[INITIAL_CAPACITY];

	private int length;

	/**
	 * Appends {@code in} to this connection's pending bytes and returns how many
	 * complete request heads became visible, consuming them.
	 *
	 * @return the number of requests to dispatch, possibly {@code 0}
	 * @throws IOException
	 *             if the pending head would exceed {@link #MAX_PENDING_BYTES}, i.e.
	 *             the peer is not speaking the HTTP this class frames. Both callers
	 *             already treat an {@link IOException} here as the end of that
	 *             connection, which is the right answer.
	 */
	int feed(ByteBuffer in) throws IOException {
		append(in);

		int requests = 0;
		int consumed = 0;
		for (int i = 3; i < length; i++) {
			if (buffer[i] == '\n' && buffer[i - 1] == '\r' && buffer[i - 2] == '\n' && buffer[i - 3] == '\r') {
				requests++;
				consumed = i + 1;
			}
		}

		/*
		 * Everything up to the last boundary is now accounted for; the tail is an
		 * incomplete head that the next read has to finish. Re-scanning that tail on
		 * the next feed is what makes a boundary split across two reads work -- the
		 * '\r\n\r' stays in the buffer and pairs up with the '\n' that arrives later.
		 */
		if (consumed > 0) {
			System.arraycopy(buffer, consumed, buffer, 0, length - consumed);
			length -= consumed;
		}

		return requests;
	}

	/**
	 * Wraps an already-encoded JSON body into a complete HTTP/1.1 200, ready to be
	 * written.
	 *
	 * <p>
	 * Built per call, on the virtual thread that just produced {@code body}: the
	 * body differs per request now that it comes back out of Jackson, so there is
	 * no shared buffer left to duplicate, and the head is copied out of a constant
	 * that stays clean.
	 *
	 * <p>
	 * A heap buffer rather than a direct one, on purpose: the JDK copies it into
	 * the writing thread's own cached direct buffer on the way to the socket, which
	 * keeps the allocation off the direct-memory pool and on the carrier that is
	 * doing the write.
	 */
	static ByteBuffer jsonResponse(byte[] body) {
		byte[] length = Integer.toString(body.length).getBytes(StandardCharsets.US_ASCII);
		byte[] response = new byte[RESPONSE_HEAD.length + length.length + HEAD_END.length + body.length];

		int offset = 0;
		System.arraycopy(RESPONSE_HEAD, 0, response, offset, RESPONSE_HEAD.length);
		offset += RESPONSE_HEAD.length;
		System.arraycopy(length, 0, response, offset, length.length);
		offset += length.length;
		System.arraycopy(HEAD_END, 0, response, offset, HEAD_END.length);
		offset += HEAD_END.length;
		System.arraycopy(body, 0, response, offset, body.length);

		return ByteBuffer.wrap(response);
	}

	private void append(ByteBuffer in) throws IOException {
		int incoming = in.remaining();
		if (length + incoming > MAX_PENDING_BYTES) {
			throw new IOException("no HTTP request head within " + MAX_PENDING_BYTES + " bytes; closing");
		}
		if (length + incoming > buffer.length) {
			buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, length + incoming));
		}
		in.get(buffer, length, incoming);
		length += incoming;
	}
}
