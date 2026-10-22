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
 * A user: a few counters and one friend.
 *
 * <p>
 * The walk goes from friend to friend. The CPU must read a user before it knows
 * the next one, so it cannot read ahead.
 *
 * <p>
 * Not thread-safe: only the actor that owns the user touches it.
 */
final class User {

	/**
	 * The next user of the walk. Not final: the cycle is closed once all users
	 * exist.
	 *
	 * <p>
	 * An {@code Integer}, held as an {@code Object}. With --enable-preview,
	 * {@code Integer} is a value class, and the JVM may store an {@code Integer}
	 * field as a plain number. Then, at each step, the walk could have to create
	 * the {@code Integer} again to look it up in the map. An {@code Object} field
	 * keeps a reference to the key itself.
	 */
	private Object friend;

	private int visits;

	private long score;

	private long lastVisitNanos;

	Integer friend() {
		return (Integer) friend;
	}

	void setFriend(Integer friend) {
		this.friend = friend;
	}

	int visits() {
		return visits;
	}

	/**
	 * Called at each step of the walk. It writes to the user on purpose: the cache
	 * line becomes dirty, so if the actor moves to another core, that core must
	 * take the line from the old one. This is the cost of a migration.
	 */
	void recordVisit(long nowNanos, int depth) {
		visits++;
		score += depth;
		lastVisitNanos = nowNanos;
	}
}
