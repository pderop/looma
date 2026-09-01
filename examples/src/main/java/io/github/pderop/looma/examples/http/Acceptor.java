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
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.github.pderop.looma.ActorSystem;

import static io.github.pderop.looma.Placement.carrier;
import static io.github.pderop.looma.SpawnOptions.placedOn;

/**
 * Accepts connections on a platform thread and spawns one
 * {@link ConnectionActor} per connection.
 *
 * <p>
 * <b>Each connection actor is a root actor spawned with an explicit
 * {@link io.github.pderop.looma.Placement#carrier(int)
 * Placement.carrier(int)}</b>, not the round-robin default — the round-robin
 * cursor is one sequence shared with every other {@code ActorSystem.spawn} in
 * the process, so relying on it would only spread connections evenly if nothing
 * else in the process ever spawned a root. An explicit placement makes
 * connection <i>n</i> land on carrier {@code n % carriers} on every run — the
 * connections, and therefore the per-connection state they each own, spread
 * evenly over the carriers reproducibly. Each connection is one virtual thread
 * for life; under a carrier-affine scheduler that thread is home-affine to the
 * chosen carrier. Nothing routes on anything client-driven, and nothing could:
 * {@code wrk} sends identical headers on every connection ({@code -H} is
 * global) and never echoes a {@code Set-Cookie}.
 *
 * <p>
 * The array a connection's requests actually walk is allocated in the actor's
 * own {@code preStart}, which runs on its home carrier — not here. That is what
 * makes the first-touch claim true even though this thread is the one that
 * calls the constructor.
 *
 * <p>
 * A platform thread, not a virtual one: the accept loop has nothing to gain
 * from a carrier, and keeping it off the carriers keeps it out of the
 * measurement.
 */
final class Acceptor implements Runnable {

	private final ServerSocketChannel serverChannel;

	private final ActorSystem system;

	private final int carrierCount;

	private final int stateKiB;

	private final URI upstream;

	private final Set<SocketChannel> openChannels;

	private volatile boolean running = true;

	Acceptor(int port, int backlog, ActorSystem system, int stateKiB, URI upstream, Set<SocketChannel> openChannels)
			throws IOException {
		this.system = system;
		this.carrierCount = system.carrierCount();
		this.stateKiB = stateKiB;
		this.upstream = upstream;
		this.openChannels = openChannels;
		this.serverChannel = ServerSocketChannel.open();
		this.serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
		this.serverChannel.bind(new InetSocketAddress(port), backlog);
	}

	Thread start() {
		Thread thread = Thread.ofPlatform().name("http-acceptor").daemon(false).start(this);
		return thread;
	}

	@Override
	public void run() {
		int seq = 0;
		while (running) {
			SocketChannel channel;
			try {
				channel = serverChannel.accept();
			} catch (ClosedChannelException e) {
				/*
				 * The listen socket is gone -- normally because stop() closed it out from under
				 * this blocking accept, which is how the loop is meant to end. Nothing can be
				 * accepted afterwards, so this is the one failure that does end the loop.
				 */
				return;
			} catch (IOException e) {
				/*
				 * Anything else is about THIS accept, not about the listen socket: a hit
				 * file-descriptor limit, a client that reset between SYN and accept. Returning
				 * here would leave the port bound and every subsequent connection queued in the
				 * backlog forever -- a server that looks alive to the client and serves
				 * nothing, which a benchmark would faithfully record as server behaviour. Log,
				 * pause briefly so a persistent error cannot spin this thread at 100% on a core
				 * the measurement shares, and carry on.
				 */
				if (!running) {
					return;
				}
				System.err.println("accept failed, continuing: " + e);
				if (!pauseAfterFailedAccept()) {
					return;
				}
				continue;
			}

			/*
			 * Monotonic and never reused, whether or not this connection makes it to an
			 * actor: it names a root actor, and a root name is taken for as long as that
			 * cell stays registered -- a cell whose preStart fails unregisters itself
			 * asynchronously, so recycling the number would race that removal. It also
			 * seeds the actor's random walk, which is what makes a run reproducible, and it
			 * is the connection's arrival order, which floorMod turns into this
			 * connection's explicit carrier below.
			 */
			int connectionIndex = seq++;
			try {
				channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
				openChannels.add(channel);
				/*
				 * Math.floorMod, not %: connectionIndex is an int and wraps to negative past
				 * Integer.MAX_VALUE connections. With %, a negative connectionIndex would hand
				 * carrier(...) a negative id, which throws IllegalArgumentException -- caught
				 * below, so every connection would be silently dropped for as long as
				 * connectionIndex stays negative (the next ~2^31 accepts). floorMod never
				 * returns negative, so the mapping stays valid across the wrap.
				 */
				system.spawn("conn-" + connectionIndex,
						() -> new ConnectionActor(channel, connectionIndex, stateKiB, upstream, openChannels),
						placedOn(carrier(Math.floorMod(connectionIndex, carrierCount))));
			} catch (IOException | RuntimeException e) {
				/*
				 * This connection never reached an actor, so nothing else will ever close it --
				 * and it is not yet reliably in openChannels for the shutdown sweep to catch.
				 * Drop it here, and keep accepting.
				 */
				System.err.println("dropping a connection that could not be given an actor: " + e);
				openChannels.remove(channel);
				try {
					channel.close();
				} catch (IOException ignored) {
					// Already gone.
				}
			}
		}
	}

	/**
	 * Sleeps briefly after a failed {@code accept}.
	 *
	 * @return {@code false} if the wait was interrupted, in which case the caller
	 *         must stop accepting
	 */
	private boolean pauseAfterFailedAccept() {
		try {
			TimeUnit.MILLISECONDS.sleep(10);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** Stops the accept loop; the pending {@code accept()} fails out of it. */
	void stop() {
		running = false;
		try {
			serverChannel.close();
		} catch (IOException e) {
			// Shutting down anyway.
		}
	}
}
