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
package io.github.pderop.looma.impl;

/**
 * The engine's one seam to whoever owns the dispatcher array — {@code
 * DefaultActorSystem}'s {@code dispatchers[]} and round-robin cursor — used by
 * {@link ActorRuntime} to resolve a {@link io.github.pderop.looma.Placement
 * Placement} into an {@link ActorDispatcher}.
 *
 * <p>
 * Every {@link io.github.pderop.looma.Placement Placement} resolves to a
 * carrier <b>id</b> first ({@link ActorRuntime#dispatcherFor}), and only then
 * is that id handed to {@link #dispatcherFor(int)} here — this is the one place
 * that turns a carrier id into the cached factory+id binding every actor on
 * that carrier shares (the loop virtual thread is still per-actor; only the
 * factory is shared).
 *
 * <p>
 * Package-private, and not an SPI: it exists only so that {@link ActorRuntime}
 * — scheduler-agnostic and reusable across every {@code ActorScheduler} — never
 * has to know {@code DefaultActorSystem}'s private nested types.
 */
interface DispatcherSource {

	/**
	 * Returns the number of distinct carriers a dispatcher can be resolved for —
	 * the bound {@link io.github.pderop.looma.Placement.Carrier} is checked
	 * against.
	 */
	int carrierCount();

	/**
	 * Returns the next carrier id from the shared round-robin sequence, advancing
	 * it. Called for a root spawn without an explicit placement, and for
	 * {@link io.github.pderop.looma.Placement#roundRobin()} on either surface.
	 */
	int nextCarrierId();

	/**
	 * Returns the cached dispatcher for a specific carrier id: that carrier's
	 * virtual-thread factory and the id itself. Every actor placed on this carrier
	 * gets the same instance; each still starts its own loop virtual thread from
	 * that factory.
	 *
	 * @param carrierId
	 *            an id already known to be within {@code [0, carrierCount())} — the
	 *            caller has already checked the bound, so an out-of-range id here
	 *            is an engine bug, not a user-facing one
	 */
	ActorDispatcher dispatcherFor(int carrierId);
}
