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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;

/**
 * An actor that owns a group of users. There is one group per carrier.
 *
 * <p>
 * A request takes two messages:
 *
 * <pre>
 * Recommend          start a virtual thread that GETs {@link BackendServer}
 * UpstreamAnswered   walk the friend graph, then answer the client
 * </pre>
 *
 * While the GET runs, the mailbox is empty, so the actor parks. When the answer
 * arrives, the scheduler resumes the actor on some carrier. If it is the same
 * carrier as before, the users are still in that core's cache. If not, they
 * must be read again from memory or from another core's cache. This is what the
 * benchmark measures.
 */
final class UserGroup implements Actor {

	/** Friends walked per request ({@code -Dbench.depth}). 0 walks none. */
	static final int DEPTH = Integer.getInteger("bench.depth", 256);

	/**
	 * Users per group ({@code -Dbench.usersPerGroup}). 8000 users take about 640
	 * KiB, which fits in a core's L2 cache.
	 */
	static final int USERS_PER_GROUP = Integer.getInteger("bench.usersPerGroup", 8000);

	/** Prints only the first failed GET, not one per request. */
	private static final AtomicBoolean FAILURE_REPORTED = new AtomicBoolean();

	/** The number of this group. */
	private final int index;

	/** The number of groups. */
	private final int groups;

	private final InetSocketAddress backend;

	private final CarrierTracker carriers = new CarrierTracker();

	/** Only this actor uses it, so a plain HashMap is enough. */
	private Map<Integer, User> users;

	/**
	 * Connections to the backend, all opened at start. A GET borrows one and gives
	 * it back. When all are in use, it waits.
	 */
	private static final int CONNECTIONS = 32;

	/** Every connection opened, to close them all at the end. */
	private final ConcurrentLinkedQueue<Upstream> connections = new ConcurrentLinkedQueue<>();

	private final BlockingQueue<Upstream> idle = new ArrayBlockingQueue<>(CONNECTIONS);

	private long requests;

	private long upstreamFailures;

	UserGroup(int index, int groups, InetSocketAddress backend) {
		this.index = index;
		this.groups = groups;
		this.backend = backend;
	}

	/**
	 * Creates the users of this group. Their ids are {@code index},
	 * {@code index + groups}, {@code index + 2 * groups}, and so on: the ids that
	 * {@link Main#handleRequest} sends here.
	 *
	 * <p>
	 * This runs on the actor's carrier, so the users are created by the core that
	 * reads them.
	 *
	 * <p>
	 * The users are created in a random order, and the friendships follow another
	 * random order. So the walk jumps around in memory, and the CPU cannot guess
	 * the next address. The friendships form one big cycle, so the walk never loops
	 * on a few users.
	 */
	@Override
	public void preStart(ActorContext context) throws IOException {
		users = new HashMap<>(USERS_PER_GROUP * 2);
		List<Integer> ids = new ArrayList<>(USERS_PER_GROUP);
		for (int i = 0; i < USERS_PER_GROUP; i++) {
			ids.add(index + i * groups);
		}

		List<Integer> allocationOrder = new ArrayList<>(ids);
		Collections.shuffle(allocationOrder, new Random(1));
		for (Integer id : allocationOrder) {
			users.put(id, new User());
		}

		List<Integer> cycle = new ArrayList<>(ids);
		Collections.shuffle(cycle, new Random(index));
		for (int i = 0; i < cycle.size(); i++) {
			users.get(cycle.get(i)).setFriend(cycle.get((i + 1) % cycle.size()));
		}

		for (int i = 0; i < CONNECTIONS; i++) {
			Upstream connection = Upstream.connect(backend);
			connections.add(connection);
			idle.add(connection);
		}
	}

	@Override
	public void postStop(ActorContext context) {
		connections.forEach(Upstream::close);
	}

	@Override
	public void onReceive(Message message, ActorRef sender, ActorContext context) {
		switch (message) {
			case Recommend request -> {
				carriers.resumed();
				get(request.userId(), sender, context);
				carriers.parking();
			}

			case UpstreamAnswered answered -> {
				carriers.resumed();
				if (answered.bytes() < 0) {
					upstreamFailures++;
				}
				requests++;
				answered.client().tell(new Recommended(walk(answered.userId()) + answered.bytes()), context.self());
				carriers.parking();
			}

			case Snapshot snapshot -> {
				sender.tell(new Stats(requests, upstreamFailures, carriers.traced(), carriers.migrated()),
						context.self());
				if (snapshot.reset()) {
					requests = 0;
					upstreamFailures = 0;
					carriers.reset();
				}
			}

			default -> throw new IllegalStateException("user group received unexpected message: " + message);
		}
	}

	/**
	 * Starts a virtual thread that GETs the backend, then sends the answer back to
	 * this actor. The answer is the response size, or -1 if the GET failed.
	 */
	private void get(Integer userId, ActorRef client, ActorContext context) {
		ActorRef self = context.self();
		context.vThreadFactory().newThread(() -> {
			int bytes;
			Upstream connection;
			try {
				connection = idle.take();
			} catch (InterruptedException e) {
				return; // only at shutdown
			}
			try {
				bytes = connection.get();
			} catch (IOException | RuntimeException e) {
				// Any failure is a failed GET: the request is answered, and counted.
				if (!FAILURE_REPORTED.getAndSet(true)) {
					System.err.println("upstream GET failed (further failures are only counted): " + e);
				}
				bytes = -1;
				connection = replace(connection);
			} finally {
				idle.add(connection);
			}
			self.tell(new UpstreamAnswered(userId, client, bytes), self);
		}).start();
	}

	/**
	 * Closes a connection that failed: part of a response may still be in it, and
	 * the next GET would read it as its own. Returns a new connection, or the
	 * closed one if the backend cannot be reached: a GET on it then fails at once
	 * and is counted, and tries to reconnect again.
	 */
	private Upstream replace(Upstream failed) {
		failed.close();
		try {
			Upstream fresh = Upstream.connect(backend);
			connections.add(fresh);
			return fresh;
		} catch (IOException e) {
			return failed;
		}
	}

	/**
	 * Walks {@link #DEPTH} friends from {@code userId} and updates each one.
	 * Returns a checksum, so the JIT cannot remove the walk.
	 */
	private long walk(Integer userId) {
		if (DEPTH == 0) {
			return 0;
		}
		long now = System.nanoTime();
		long checksum = 0;
		User user = users.get(userId);
		for (int step = 0; step < DEPTH; step++) {
			user.recordVisit(now, step);
			checksum += user.visits();
			user = users.get(user.friend());
		}
		return checksum;
	}

	record Recommend(Integer userId) implements Message {
	}

	record Recommended(long checksum) implements Message {
	}

	/** {@code bytes}: the response size, or -1 if the GET failed. */
	record UpstreamAnswered(Integer userId, ActorRef client, int bytes) implements Message {
	}

	/** Asks for the counters, and resets them if {@code reset} is true. */
	record Snapshot(boolean reset) implements Message {
	}

	record Stats(long requests, long upstreamFailures, long tracedMessages, long migratedMessages) implements Message {
	}
}
