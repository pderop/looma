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

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tells the server when every container has opened its backend connections, or
 * why one could not. A container whose start fails stops, and nothing would
 * route around it: so the server does not start.
 */
final class Startup {

	private final CountDownLatch pending;

	private final AtomicReference<Exception> failure = new AtomicReference<>();

	/** {@code containers} is the number of containers that will report. */
	Startup(int containers) {
		this.pending = new CountDownLatch(containers);
	}

	/** A container is ready. */
	void ready() {
		pending.countDown();
	}

	/** A container could not start. The first failure is the one reported. */
	void failed(Exception cause) {
		failure.compareAndSet(null, cause);
		pending.countDown();
	}

	/**
	 * Waits for every container. Throws if one failed, or if they are not all ready
	 * in time.
	 */
	void await(Duration timeout) throws InterruptedException {
		if (!pending.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
			throw new IllegalStateException("the containers were not ready within " + timeout.toSeconds() + " s");
		}
		Exception cause = failure.get();
		if (cause != null) {
			throw new IllegalStateException("a container could not start: " + cause.getMessage(), cause);
		}
	}
}
