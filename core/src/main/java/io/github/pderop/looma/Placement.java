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
package io.github.pderop.looma;

/**
 * Which carrier a newly spawned actor should call home, passed to {@code spawn}
 * through {@link SpawnOptions#placedOn(Placement)}.
 *
 * <p>
 * A placement is resolved exactly once, at spawn time: it decides the actor's
 * home carrier, and that carrier is then fixed for the actor's entire lifetime,
 * exactly like the round-robin-root / inherit-from-parent placement every actor
 * already gets by default (see {@link ActorContext}'s class javadoc). Nothing
 * in this project ever re-places a running actor.
 *
 * <ul>
 * <li>{@link #inherit()} — the child's home carrier is its parent's.
 * Meaningless on {@link ActorSystem#spawn}, which has no parent to inherit
 * from, and throws {@link IllegalArgumentException} there.
 * <li>{@link #roundRobin()} — the next carrier from the system-wide round-robin
 * counter, the same counter every root spawn without an explicit placement
 * already advances.
 * <li>{@link #carrier(int)} — a specific carrier id, bounds-checked at spawn
 * time against the system's {@code carrierCount()}.
 * </ul>
 *
 * <p>
 * Placing a child away from its parent's carrier is not free: every
 * parent-to-child (and child-to-parent) {@code tell} becomes a cross-carrier
 * hop instead of the same-carrier delivery the default gives for nothing. Reach
 * for an explicit placement when the actor's own workload benefits from a
 * specific or spread-out carrier assignment (e.g. an acceptor fanning
 * connections out one per carrier), not by default. Under the JDK builtin
 * scheduler {@code carrierCount()} is {@code 1}, so every placement resolves to
 * that one factory.
 *
 * <p>
 * <b>{@link #inherit()} inherits the parent's home carrier</b> — the factory
 * that starts the child's own loop virtual thread — never a shared run loop.
 * Every actor has a loop of its own.
 */
public sealed interface Placement {

	/**
	 * A child inherits its parent's home carrier. Not valid on
	 * {@link ActorSystem#spawn}, which has no parent.
	 */
	record Inherit() implements Placement {
	}

	/**
	 * The next carrier from the system-wide round-robin counter.
	 */
	record RoundRobin() implements Placement {
	}

	/**
	 * A specific carrier, identified by its id.
	 *
	 * @param carrierId
	 *            the carrier id; must be non-negative, enforced by this record's
	 *            own canonical constructor regardless of how the record is built.
	 *            The upper bound (against a system's {@code carrierCount()}) can
	 *            only be checked once a system is known, so it is checked at spawn
	 *            time instead.
	 */
	record Carrier(int carrierId) implements Placement {

		public Carrier {
			if (carrierId < 0) {
				throw new IllegalArgumentException("carrierId must be >= 0, got " + carrierId);
			}
		}
	}

	/**
	 * Returns a placement that puts the actor on its parent's home carrier.
	 */
	static Placement inherit() {
		return new Inherit();
	}

	/**
	 * Returns a placement that puts the actor on the next carrier from the
	 * system-wide round-robin counter.
	 */
	static Placement roundRobin() {
		return new RoundRobin();
	}

	/**
	 * Returns a placement that puts the actor on a specific carrier.
	 *
	 * @param carrierId
	 *            the carrier id; must be non-negative
	 * @throws IllegalArgumentException
	 *             if {@code carrierId} is negative
	 */
	static Placement carrier(int carrierId) {
		return new Carrier(carrierId);
	}
}
