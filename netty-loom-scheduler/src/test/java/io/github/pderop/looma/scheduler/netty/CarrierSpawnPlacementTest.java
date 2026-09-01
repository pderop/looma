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
package io.github.pderop.looma.scheduler.netty;

import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.SpawnPlacementTest;

/**
 * Runs the shared {@link SpawnPlacementTest} suite against the carrier-affine
 * scheduler, using the JVM-wide carrier pool installed by
 * {@code -Djdk.virtualThreadScheduler.implClass} (see
 * {@code netty-loom-scheduler/pom.xml} for the required JVM args). It runs
 * twice: once under {@code carrier-test} (two carriers, no stealing) and once
 * under {@code workstealing-test} (four carriers, stealing on) — a failure
 * under either is a real failure.
 *
 * <p>
 * Being the shared suite, it only ever asserts {@code Placement.carrier(0)};
 * everything that needs more than one carrier is in
 * {@code CarrierPlacementTest} instead, which runs only here and is not a suite
 * subclass.
 */
class CarrierSpawnPlacementTest extends SpawnPlacementTest {

	@Override
	protected ActorSystem newActorSystem() {
		return ActorSystem.builder().build();
	}
}
