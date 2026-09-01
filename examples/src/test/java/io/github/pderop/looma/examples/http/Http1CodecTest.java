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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Framing is the only part of this example with real edge cases, and it is
 * driven by a client that pipelines — so it is tested here in isolation rather
 * than through the server, where a mis-frame would surface as a hung
 * connection.
 *
 * <p>
 * Needs no actor system and no scheduler, so it belongs to the default
 * {@code test} task.
 */
class Http1CodecTest {

	private static final String GET = "GET /hot HTTP/1.1\r\nHost: localhost\r\nCookie: sid=1\r\n\r\n";

	private final Http1Codec codec = new Http1Codec();

	@Test
	void framesOneRequest() {
		assertEquals(1, feed(GET));
	}

	@Test
	void framesSeveralPipelinedRequestsInOneRead() {
		assertEquals(3, feed(GET + GET + GET));
	}

	@Test
	void holdsAPartialRequestUntilItCompletes() {
		assertEquals(0, feed(GET.substring(0, 20)));
		assertEquals(1, feed(GET.substring(20)));
	}

	@Test
	void framesABoundarySplitAcrossTwoReads() {
		// The first read ends mid-CRLFCRLF: the tail must be kept and re-scanned.
		String head = GET.substring(0, GET.length() - 1);
		assertEquals(0, feed(head));
		assertEquals(1, feed("\n"));
	}

	@Test
	void keepsTheTailOfAPipelinedBatchForTheNextRead() {
		assertEquals(2, feed(GET + GET + GET.substring(0, 10)));
		assertEquals(1, feed(GET.substring(10)));
	}

	@Test
	void growsBeyondItsInitialBuffer() {
		StringBuilder batch = new StringBuilder();
		int requests = 400; // ~20 KiB, past the 8 KiB initial capacity
		batch.repeat(GET, requests);
		assertEquals(requests, feed(batch.toString()));
	}

	@Test
	void reportsNothingForAnEmptyRead() {
		assertEquals(0, feed(""));
	}

	@Test
	void refusesToBufferAStreamThatNeverTerminatesAHead() {
		// A peer that sends and sends without ever closing a head -- a stuck client, a
		// scanner, TLS on a plaintext port. The accumulator must not follow it up.
		// Fed in the 16 KiB chunks the real readers deliver; 16 of them is 256 KiB,
		// well past the ceiling, so this says "it stops" without pinning the exact
		// byte.
		String chunk = "x".repeat(16 * 1024);

		UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> {
			for (int i = 0; i < 16; i++) {
				feed(chunk);
			}
		});
		assertEquals(IOException.class, thrown.getCause().getClass());
	}

	private int feed(String bytes) {
		try {
			return codec.feed(ByteBuffer.wrap(bytes.getBytes(StandardCharsets.US_ASCII)));
		} catch (IOException e) {
			// The production callers close the connection on this; a test that feeds valid
			// HTTP must never see it, so surfacing it as a failure is the whole point.
			throw new UncheckedIOException(e);
		}
	}
}
