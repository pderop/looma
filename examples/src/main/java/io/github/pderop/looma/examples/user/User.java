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

/**
 * One user in the map its {@link UserGroup} serves from: a few counters, plus
 * one friend.
 *
 * <p>
 * {@link #friend()} is what makes this a memory workload rather than a
 * benchmark trick: a recommendation walks the social graph, so the next lookup
 * cannot start until the current user has arrived.
 *
 * <p>
 * Not thread-safe, and it does not need to be: only the group that owns this
 * user can reach it, and a group is an actor — one message at a time.
 */
final class User {

	/**
	 * A friend, and the next user a walk visits. Not {@code final} only because a
	 * cycle cannot be closed before every user in it exists.
	 */
	private Integer friend;

	private int visits;

	private long score;

	private long lastVisitNanos;

	Integer friend() {
		return friend;
	}

	void setFriend(Integer friend) {
		this.friend = friend;
	}

	int visits() {
		return visits;
	}

	/**
	 * One step of a walk: reached, {@code depth} friends from where it started,
	 * now.
	 *
	 * <p>
	 * A write rather than a read on purpose — it leaves the line dirty, so a core
	 * that picks this user up next must take the line away from the previous owner
	 * rather than merely share it. That is the traffic a migration causes.
	 */
	void recordVisit(long nowNanos, int depth) {
		visits++;
		score += depth;
		lastVisitNanos = nowNanos;
	}
}
