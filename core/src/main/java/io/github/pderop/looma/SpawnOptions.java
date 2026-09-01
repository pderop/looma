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

import java.util.Objects;
import java.util.Optional;

/**
 * The optional arguments to {@code spawn}, beyond the mandatory {@code name}
 * and {@code factory}: a {@link Placement} and a {@link SupervisionStrategy}.
 *
 * <p>
 * An instance is immutable and complete the moment it is built: there is no
 * terminal {@code .spawn()} call to forget, and no mutable state that could
 * escape to another thread. Every {@code with}/{@code placedOn}/
 * {@code supervisedBy} method returns a new instance rather than mutating the
 * receiver.
 *
 * <p>
 * "Unset" is a third state for {@link #placement()} and {@link #strategy()},
 * distinct from either field's own default, and is expressed only by not
 * calling the corresponding method — never by passing {@code null}, which every
 * setter rejects with {@link NullPointerException}. This is why those two
 * return {@link Optional}: a single {@link #defaults()} instance cannot itself
 * carry a placement, because the right default differs by surface (
 * {@code roundRobin()} for {@link ActorSystem#spawn}, {@code inherit()} for
 * {@link ActorContext#spawn}) — only the caller resolving it knows which.
 *
 * <p>
 * <b>This type is the growth point for future optional spawn parameters</b> —
 * it exists precisely so that a third one, one day, costs one more method here
 * instead of a third overload on every spawning interface. That is also exactly
 * why it must not become a dumping ground: an addition here should be as
 * genuinely optional and as orthogonal to {@code name}/{@code factory} as
 * placement and supervision already are.
 */
public final class SpawnOptions {

	private static final SpawnOptions DEFAULTS = new SpawnOptions(null, null);

	private final Placement placement;
	private final SupervisionStrategy strategy;

	private SpawnOptions(Placement placement, SupervisionStrategy strategy) {
		this.placement = placement;
		this.strategy = strategy;
	}

	/**
	 * Returns the shared instance with neither a placement nor a strategy set:
	 * every {@code spawn} call falls back to its surface's own default placement
	 * and strategy.
	 */
	public static SpawnOptions defaults() {
		return DEFAULTS;
	}

	/**
	 * Returns a new instance with only {@code placement} set, equivalent to
	 * {@code SpawnOptions.defaults().withPlacement(placement)}.
	 *
	 * @throws NullPointerException
	 *             if {@code placement} is {@code null}
	 */
	public static SpawnOptions placedOn(Placement placement) {
		return DEFAULTS.withPlacement(placement);
	}

	/**
	 * Returns a new instance with only {@code strategy} set, equivalent to
	 * {@code SpawnOptions.defaults().withStrategy(strategy)}.
	 *
	 * @throws NullPointerException
	 *             if {@code strategy} is {@code null}
	 */
	public static SpawnOptions supervisedBy(SupervisionStrategy strategy) {
		return DEFAULTS.withStrategy(strategy);
	}

	/**
	 * Returns a new instance with {@code placement} set, keeping this instance's
	 * strategy (set or unset) unchanged. This instance is not modified.
	 *
	 * @throws NullPointerException
	 *             if {@code placement} is {@code null}
	 */
	public SpawnOptions withPlacement(Placement placement) {
		Objects.requireNonNull(placement, "placement");
		return new SpawnOptions(placement, strategy);
	}

	/**
	 * Returns a new instance with {@code strategy} set, keeping this instance's
	 * placement (set or unset) unchanged. This instance is not modified.
	 *
	 * @throws NullPointerException
	 *             if {@code strategy} is {@code null}
	 */
	public SpawnOptions withStrategy(SupervisionStrategy strategy) {
		Objects.requireNonNull(strategy, "strategy");
		return new SpawnOptions(placement, strategy);
	}

	/**
	 * Returns the placement this instance carries, or {@link Optional#empty()} if
	 * none was set — in which case the spawning surface applies its own default.
	 */
	public Optional<Placement> placement() {
		return Optional.ofNullable(placement);
	}

	/**
	 * Returns the strategy this instance carries, or {@link Optional#empty()} if
	 * none was set — in which case the actor's own
	 * {@link Actor#supervisorStrategy()} applies.
	 */
	public Optional<SupervisionStrategy> strategy() {
		return Optional.ofNullable(strategy);
	}
}
