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

import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.SpawnPlacementTest;

/**
 * Runs the shared {@link SpawnPlacementTest} suite against the JDK builtin
 * scheduler, which has exactly one carrier.
 */
class JdkSpawnPlacementTest extends SpawnPlacementTest {

	@Override
	protected ActorSystem newActorSystem() {
		return ActorSystem.builder().build();
	}
}
