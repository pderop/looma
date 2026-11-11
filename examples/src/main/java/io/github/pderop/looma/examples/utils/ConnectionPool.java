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

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Connections to a backend, shared by the virtual threads of some actors: a
 * group of the user benchmark, a container of the servlet benchmark. All are
 * opened at start. A call borrows one and gives it back. When all are in use,
 * it waits.
 *
 * <pre>
 * ConnectionPool&lt;BackendClient&gt; pool = ConnectionPool.open(() -&gt; BackendClient.connect(backend), 8,
 * 		context.vThreadFactory());
 * </pre>
 *
 * <p>
 * A call takes the connection given back last. So only the connections in use
 * at the same time are touched, and they stay in the caches. The call that
 * waits the longest gets the next connection: see {@link FairPool}.
 *
 * @param <C>
 *            a connection
 */
public final class ConnectionPool<C extends Closeable> {

	/** Opens one connection. */
	@FunctionalInterface
	public interface Connector<C> {

		C connect() throws IOException;
	}

	private final Connector<C> connector;

	/** Every connection opened, to close them all at the end. */
	private final ConcurrentLinkedQueue<C> all = new ConcurrentLinkedQueue<>();

	private final FairPool<C> idle = new FairPool<>();

	/**
	 * Set by {@link #close}. A call that fails after it closes its connection
	 * instead of opening a new one.
	 */
	private volatile boolean closed;

	private ConnectionPool(Connector<C> connector) {
		this.connector = connector;
	}

	/**
	 * Opens {@code connections} connections on a thread of {@code threads}, and
	 * waits for them. Throws if one cannot be opened.
	 */
	public static <C extends Closeable> ConnectionPool<C> open(Connector<C> connector, int connections,
			ThreadFactory threads) throws Exception {
		ConnectionPool<C> pool = new ConnectionPool<>(connector);
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Exception> failure = new AtomicReference<>();
		threads.newThread(() -> {
			try {
				for (int i = 0; i < connections; i++) {
					C connection = connector.connect();
					pool.all.add(connection);
					pool.idle.give(connection);
				}
			} catch (IOException | RuntimeException e) {
				failure.set(e);
			} finally {
				done.countDown();
			}
		}).start();
		done.await();
		if (failure.get() != null) {
			pool.close();
			throw failure.get();
		}
		return pool;
	}

	/**
	 * The free connection given back last. Waits if all are in use: {@code waiter}
	 * is the place of the call in the queue.
	 */
	public C take(FairPool.Waiter<C> waiter) throws InterruptedException {
		return idle.take(waiter);
	}

	/**
	 * Gives a connection back. Even closed, it goes back: a call waiting for one
	 * then fails at once instead of waiting forever.
	 */
	public void give(C connection) {
		idle.give(connection);
	}

	/**
	 * A new connection in place of one that failed: part of an answer may still be
	 * in it, and the next call would read it as its own. Once the pool is closed,
	 * or if the connect fails, it returns the failed one, closed: the next call on
	 * it fails at once.
	 */
	public C replace(C failed) {
		closeQuietly(failed);
		if (closed) {
			return failed;
		}
		try {
			C fresh = connect();
			all.add(fresh);
			all.remove(failed);
			// close may have closed the others while this one was opening.
			if (closed) {
				closeQuietly(fresh);
			}
			return fresh;
		} catch (IOException e) {
			return failed;
		}
	}

	/**
	 * Opens a connection. If the thread is interrupted while it connects, for
	 * example by the timer of its call, it connects once more: the call has failed,
	 * but the pool still needs a good connection for the next one.
	 */
	private C connect() throws IOException {
		try {
			return connector.connect();
		} catch (ClosedByInterruptException e) {
			Thread.interrupted();
			return connector.connect();
		}
	}

	/** How many connections the pool would close. */
	public int size() {
		return all.size();
	}

	/** Closes every connection. */
	public void close() {
		closed = true;
		all.forEach(ConnectionPool::closeQuietly);
	}

	private static void closeQuietly(Closeable connection) {
		try {
			connection.close();
		} catch (IOException e) {
			// Closed anyway.
		}
	}
}
