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
 * Counts how often an actor runs a message on another carrier than the message
 * before it. One message in {@value #SAMPLE} is checked.
 *
 * <p>
 * Between the two messages the actor may have parked, if its mailbox was empty,
 * or gone straight on, if the next message was already there. Only a park lets
 * it move, so at high rates, where actors park less often, the share goes down.
 *
 * <p>
 * The carrier is read from the thread name: a virtual thread's
 * {@code toString()} ends with {@code @} and the name of its carrier. We do not
 * ask the scheduler: it would give the actor's home carrier, which does not
 * change when the actor is stolen.
 */
final class CarrierTracker {

	private static final int SAMPLE = 64;

	private long parks;

	private long traced;

	private long migrated;

	/** The carrier of the checked message, or null. */
	private String parkedOn;

	/** Call at the end of a message. */
	void parking() {
		if (++parks % SAMPLE == 0) {
			parkedOn = carrier();
		}
	}

	/** Call at the start of the next message. */
	void resumed() {
		if (parkedOn != null) {
			traced++;
			if (!parkedOn.equals(carrier())) {
				migrated++;
			}
			parkedOn = null;
		}
	}

	long traced() {
		return traced;
	}

	long migrated() {
		return migrated;
	}

	void reset() {
		traced = 0;
		migrated = 0;
		parkedOn = null;
	}

	private static String carrier() {
		String description = Thread.currentThread().toString();
		return description.substring(description.lastIndexOf('@') + 1);
	}
}
