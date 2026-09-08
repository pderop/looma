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
 * and {@code factory}: a {@link Placement}, a {@link SupervisionStrategy} and a
 * {@link #poolSize()}.
 *
 * <p>
 * An instance is immutable and complete the moment it is built: there is no
 * terminal {@code .spawn()} call to forget, and no mutable state that could
 * escape to another thread. Every {@code with}/{@code placedOn}/
 * {@code supervisedBy} method returns a new instance rather than mutating the
 * receiver.
 *
 * <p>
 * {@link #poolSize()} is the odd one out and deliberately not an
 * {@link Optional}: unlike a placement, its default — {@code 1}, a single actor
 * — is the same on every spawning surface, so there is nothing for a caller to
 * resolve differently and no need for a third state.
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
 * <b>This type is the growth point for optional spawn parameters</b> — it
 * exists precisely so that one more costs one method here instead of an
 * overload on every spawning interface. {@link #poolSize()} is that promise
 * being kept: it arrived after placement and supervision, and cost exactly one
 * method here and no new surface anywhere else. That is also exactly why this
 * must not become a dumping ground: an addition here should be as genuinely
 * optional and as orthogonal to {@code name}/{@code factory} as the three
 * already are.
 */
public final class SpawnOptions {

	private static final SpawnOptions DEFAULTS = new SpawnOptions(null, null, 1);

	private final Placement placement;
	private final SupervisionStrategy strategy;
	private final int poolSize;

	private SpawnOptions(Placement placement, SupervisionStrategy strategy, int poolSize) {
		this.placement = placement;
		this.strategy = strategy;
		this.poolSize = poolSize;
	}

	/**
	 * Returns the shared instance with neither a placement nor a strategy set:
	 * every {@code spawn} call falls back to its surface's own default placement
	 * and strategy, and spawns a single actor rather than a pool.
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
	 * Returns a new instance with only {@code poolSize} set, equivalent to
	 * {@code SpawnOptions.defaults().withPoolSize(poolSize)}.
	 *
	 * @throws IllegalArgumentException
	 *             if {@code poolSize} is less than {@code 1}
	 */
	public static SpawnOptions pooled(int poolSize) {
		return DEFAULTS.withPoolSize(poolSize);
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
		return new SpawnOptions(placement, strategy, poolSize);
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
		return new SpawnOptions(placement, strategy, poolSize);
	}

	/**
	 * Returns a new instance with {@code poolSize} set, keeping this instance's
	 * placement and strategy (set or unset) unchanged. This instance is not
	 * modified.
	 *
	 * <p>
	 * A {@code poolSize} above {@code 1} makes {@code spawn} create that many
	 * sibling actors from the same {@code factory} and return one routing
	 * {@link ActorRef} over them — see {@link #poolSize()}.
	 *
	 * @throws IllegalArgumentException
	 *             if {@code poolSize} is less than {@code 1}
	 */
	public SpawnOptions withPoolSize(int poolSize) {
		if (poolSize < 1) {
			throw new IllegalArgumentException("poolSize must be >= 1, got " + poolSize);
		}
		return new SpawnOptions(placement, strategy, poolSize);
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

	/**
	 * Returns how many actors one {@code spawn} creates from the same factory —
	 * {@code 1} unless {@link #withPoolSize(int)} says otherwise, and never less.
	 *
	 * <p>
	 * Above {@code 1}, {@code spawn} creates that many ordinary sibling actors,
	 * named {@code name-0} … {@code name-(poolSize-1)}, and returns a single
	 * routing {@link ActorRef} that spreads {@code tell} and {@code ask} over them
	 * round-robin. See
	 * {@link ActorSystem#spawn(String, java.util.function.Supplier, SpawnOptions)}
	 * for the full contract of that reference.
	 *
	 * <p>
	 * <b>The placement applies to each actor of the pool, exactly as if they had
	 * been spawned one at a time.</b> That single rule gives both shapes of pool,
	 * because the placements already differ: {@link Placement#inherit()} and
	 * {@link Placement#carrier(int)} name one carrier, so the whole pool lands
	 * there together, while {@link Placement#roundRobin()} means "the next
	 * carrier", so the pool spreads one actor per carrier. A pool therefore needs
	 * no placement of its own, and this option decides nothing about placement:
	 *
	 * <pre>{@code
	 * // a child pool, all of it on the parent's carrier (inherit, the default
	 * // there)
	 * context.spawn("io", Io::new, SpawnOptions.pooled(4));
	 *
	 * // the same pool, spread one actor per carrier
	 * context.spawn("io", Io::new, SpawnOptions.pooled(4).withPlacement(Placement.roundRobin()));
	 * }</pre>
	 *
	 * <p>
	 * A consequence worth stating: a pool has no single home carrier to report, so
	 * {@code homeCarrierIdOf} rejects a pool reference — ask one of its actors,
	 * each an ordinary actor at its own path.
	 */
	public int poolSize() {
		return poolSize;
	}
}
