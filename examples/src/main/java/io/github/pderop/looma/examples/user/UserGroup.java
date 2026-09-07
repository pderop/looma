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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;

/**
 * One group of users, one per core: it serves the requests for the users it
 * owns, and it is the actor whose placement {@link Main} measures.
 *
 * <p>
 * A request walks {@link #DEPTH} friends out from the user it names, scoring
 * each one. Every step is a lookup whose key came out of the user before it —
 * four dependent loads (boxed key, table slot, node, user), none of which the
 * CPU can start early.
 */
final class UserGroup implements Actor {

	/**
	 * Friends walked per request. Derived, not chosen: a request costs a fixed
	 * wake-up (~5.5 us on a 14900K E-core) plus a friend (~40 ns), and only the
	 * second term carries any locality. At 16 the memory is a tenth of a request
	 * and invisible; at 256 it is two thirds. Set it to 0 for the dispatch-only
	 * control that touches no user at all.
	 */
	static final int DEPTH = 256;

	/**
	 * Users one group owns, i.e. ~640 KiB of map. It must fit in a core's L2 — 4
	 * MiB shared by four cores on an E-core module, 2 MiB private on a P-core — and
	 * all the groups together must not. 8000 satisfies both on either.
	 */
	static final int USERS_PER_GROUP = 8000;

	/** This group's index, and the residue of the id space it owns. */
	private final int index;

	/** How many groups there are, i.e. the stride between two of this one's ids. */
	private final int groups;

	private final CarrierTracker carriers;

	/**
	 * Built in {@link #preStart}. Reached by this actor alone, hence a plain map.
	 */
	private Map<Integer, User> users;

	private long requests;

	UserGroup(int index, int groups, CarrierTracker carriers) {
		this.index = index;
		this.groups = groups;
		this.carriers = carriers;
	}

	/** The id of this group's {@code i}-th user; see {@link #preStart}. */
	static Integer userId(int group, int groups, int i) {
		return group + i * groups;
	}

	/**
	 * Builds this group's users and befriends them.
	 *
	 * <p>
	 * The ids come from the routing rule and nothing else: requests for user
	 * {@code u} go to group {@code u % groups}, so this group owns
	 * {@code index, index + groups, index + 2*groups, ...} and no other group can
	 * name one of them. Boxed once here, and these very instances key the map, so
	 * no lookup in the run ever allocates.
	 *
	 * <p>
	 * Here rather than in the constructor, because {@code preStart} is delivered
	 * through the mailbox and therefore runs on this actor's own carrier: the users
	 * are allocated by the very core that will walk them. It is also guaranteed to
	 * finish before the first request.
	 *
	 * <p>
	 * <b>Two independent shuffles, and they must stay independent.</b> The first
	 * decides where each user lands in the heap, the second decides who befriends
	 * whom. Were the friendships to follow the allocation order, the walk would
	 * step through memory in order — a stride any prefetcher learns, and the whole
	 * point is that it cannot.
	 *
	 * <p>
	 * One cycle through everyone rather than random friendships: an arbitrary
	 * mapping breaks into disjoint cycles, and a walk would spin inside whichever
	 * small one it entered, visiting a handful of users that then sit in L1 and
	 * measure nothing.
	 */
	@Override
	public void preStart(ActorContext context) {
		users = new HashMap<>(USERS_PER_GROUP * 2);

		List<Integer> userIds = new ArrayList<>(USERS_PER_GROUP);
		for (int i = 0; i < USERS_PER_GROUP; i++) {
			userIds.add(userId(index, groups, i));
		}

		List<Integer> allocationOrder = new ArrayList<>(userIds);
		Collections.shuffle(allocationOrder, new Random(1));
		for (Integer id : allocationOrder) {
			users.put(id, new User());
		}

		List<Integer> cycle = new ArrayList<>(userIds);
		Collections.shuffle(cycle, new Random(index));
		for (int i = 0; i < cycle.size(); i++) {
			users.get(cycle.get(i)).setFriend(cycle.get((i + 1) % cycle.size()));
		}
	}

	@Override
	public void onReceive(Message message, ActorRef sender, ActorContext context) {
		switch (message) {
			case Recommend request -> {
				carriers.resumed();
				long checksum = recommendFor(request.userId());
				requests++;
				sender.tell(new Recommended(checksum), context.self());
				carriers.parking();
			}

			case Snapshot snapshot -> {
				sender.tell(new Stats(index, requests, carriers.traced(), carriers.migrated()), context.self());
				if (snapshot.reset()) {
					requests = 0;
					carriers.reset();
				}
			}

			default -> throw new IllegalStateException("user group received unexpected message: " + message);
		}
	}

	/**
	 * Recommends users for {@code userId}: walks {@link #DEPTH} friends out from
	 * them, scoring each user visited.
	 *
	 * <p>
	 * It returns a checksum rather than the users found, and the caller keeps it,
	 * so that the walk cannot be deleted as dead code.
	 */
	private long recommendFor(Integer userId) {
		if (DEPTH == 0) {
			return 0; // dispatch-only control: not one line of the map is touched
		}

		long now = System.nanoTime();
		long checksum = 0;
		User user = users.get(userId);

		for (int step = 0; step < DEPTH; step++) {
			user.recordVisit(now, step);
			checksum += user.visits();
			// The next friend was read out of the user that just arrived: this lookup
			// cannot start any earlier, which is what a graph walk costs.
			user = users.get(user.friend());
		}

		return checksum;
	}

	/**
	 * Recommend users for {@code userId}. The key is the same boxed instance the
	 * map is keyed by, so serving this allocates nothing.
	 */
	record Recommend(Integer userId) implements Message {
	}

	/** The checksum of the walk, which exists so the walk cannot be elided. */
	record Recommended(long checksum) implements Message {
	}

	/**
	 * Asks for the counters, optionally zeroing them. An {@code ask} so the caller
	 * knows the measurement window really opened.
	 */
	record Snapshot(boolean reset) implements Message {
	}

	/** One group's counters. */
	record Stats(int group, long requests, long tracedResumes, long migratedResumes) implements Message {
	}
}
