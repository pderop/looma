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

/**
 * Lets the tests of another module count the timers a system still holds. The
 * count is package-private in the engine.
 */
public final class TimerProbe {

	private TimerProbe() {
	}

	/** The number of timers waiting in the wheels of {@code system}. */
	public static int pendingTimers(ActorSystem system) {
		return ((DefaultActorSystem) system).pendingTimers();
	}

	/** True if the calling mailbox thread has bound the wheel of its carrier. */
	public static boolean mailboxThreadBindsItsCarrier() {
		return CarrierTimer.callerCarrier() != null;
	}
}
