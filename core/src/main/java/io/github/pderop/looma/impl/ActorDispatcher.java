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

import java.util.concurrent.ThreadFactory;

/**
 * The one thing {@code DefaultActorSystem} supplies to the shared engine for
 * one carrier: which virtual-thread factory starts that carrier's actors (and
 * their offloaded threads), and which carrier id this binding represents.
 *
 * <p>
 * This interface is deliberately tiny. It no longer sits on a per-message
 * dispatch hot path — each actor is one virtual thread started once at spawn —
 * so there is no {@code schedule(Runnable)}. Resist growing it; anything that
 * is not placement belongs in {@link ActorRuntime}.
 *
 * <p>
 * <b>An implementation never touches in-flight accounting.</b>
 * {@link ActorRuntime} is the sole owner of the counter
 * {@code ActorSystem.awaitTermination} waits on: it wraps the
 * {@link ThreadFactory} returned by {@link #vThreadFactory()} before it ever
 * reaches an implementation or user code.
 *
 * <p>
 * {@code DefaultActorSystem}'s carrier dispatcher realises the two methods like
 * this, regardless of which {@code io.github.pderop.looma.spi.ActorScheduler}
 * placed the carrier it is bound to:
 *
 * <pre>{@code
 * vThreadFactory -> carrierVThreadFactory  // cached at construction
 * carrierId      -> the carrier this binding represents
 * }</pre>
 */
interface ActorDispatcher {

	/**
	 * Returns the raw factory for this carrier: the actor loop virtual thread, and
	 * every extra virtual thread the actor creates through
	 * {@code ActorContext.vThreadFactory()}, come from here.
	 *
	 * <p>
	 * Callers never see this factory directly — {@link ActorRuntime} wraps it into
	 * the one an {@code ActorContext} hands out, so that every thread it creates is
	 * counted as in-flight work. The actor loop itself is started through the same
	 * wrapping.
	 */
	ThreadFactory vThreadFactory();

	/**
	 * Returns the id of the carrier this binding represents.
	 *
	 * <p>
	 * This is what lets {@link ActorRuntime} answer
	 * {@code ActorSystem.homeCarrierIdOf} / {@code ActorContext.homeCarrierIdOf}
	 * without knowing anything about {@code DefaultActorSystem}'s private nested
	 * types, and what lets it resolve {@link io.github.pderop.looma.Placement
	 * Placement.carrier(int)} through {@link DispatcherSource#dispatcherFor(int)}.
	 */
	int carrierId();
}
