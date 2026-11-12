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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;

/**
 * {@link BackendClient} against a real {@link ServletBackend}. The first read
 * buffer is 8 KiB, and a servlet asks for up to 16 KiB, so the body has to span
 * more than one buffer.
 */
class BackendClientTest {

	@Test
	void readsABodyBiggerThanTheFirstBuffer() throws Exception {
		try (ServletBackend backend = ServletBackend.start(0, 1)) {
			BackendClient client = BackendClient.connect(new InetSocketAddress("127.0.0.1", backend.port()));
			try {
				byte[] body = new byte[16 * 1024];
				assertEquals(16 * 1024, client.get(16 * 1024, 128, body));
				assertEquals('k', body[0]);
			} finally {
				client.close();
			}
		}
	}

	@Test
	void everySizeIsCutFromTheSameLines() throws Exception {
		try (ServletBackend backend = ServletBackend.start(0, 1)) {
			BackendClient client = BackendClient.connect(new InetSocketAddress("127.0.0.1", backend.port()));
			try {
				// A large body, then a smaller one, then a larger one again, in one buffer.
				byte[] body = new byte[32 * 1024];
				assertEquals(1024, BackendCalls.checksum(body, client.get(4096, 0, body)));
				assertEquals(2, BackendCalls.checksum(body, client.get(10, 0, body)));
				assertEquals(8192, BackendCalls.checksum(body, client.get(32 * 1024, 0, body)));
			} finally {
				client.close();
			}
		}
	}

	@Test
	void refusesABodyBiggerThanTheBuffer() throws Exception {
		try (ServletBackend backend = ServletBackend.start(0, 1)) {
			BackendClient client = BackendClient.connect(new InetSocketAddress("127.0.0.1", backend.port()));
			try {
				assertThrows(IOException.class, () -> client.get(2048, 0, new byte[1024]));
			} finally {
				client.close();
			}
		}
	}
}
