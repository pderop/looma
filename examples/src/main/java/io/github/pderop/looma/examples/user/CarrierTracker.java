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

import io.github.pderop.looma.spi.ActorScheduler;

/**
 * Counts how often the actor holding one of these came back from an empty
 * mailbox on a different carrier than it left on — the mechanism, observed
 * rather than inferred.
 *
 * <p>
 * Kept out of {@link UserGroup} so the workload reads as the workload.
 * {@link #resumed()} and {@link #parking()} bracket one message.
 */
final class CarrierTracker {

	/**
	 * {@code -Dcache.migrationSample=N}: trace one message in {@code N}, {@code 0}
	 * switches it off. It samples because of the fallback in {@link #mark()}, which
	 * allocates; sampling stays on under the affine scheduler too, where the mark
	 * is free, so both sides carry the same overhead.
	 */
	private static final int SAMPLE = Integer.getInteger("cache.migrationSample", 64);

	private final ActorScheduler scheduler;

	/** Where this actor was when it last went back to its mailbox, or unsampled. */
	private Object parkedOn;

	private long cursor;

	private long traced;

	private long migrated;

	CarrierTracker(ActorScheduler scheduler) {
		this.scheduler = scheduler;
	}

	static String describe() {
		return SAMPLE > 0 ? "1 message in " + SAMPLE : "off";
	}

	/**
	 * Called as a message arrives, before any state is touched: the mailbox was
	 * empty in between, so the loop parked, and which core it came back on is the
	 * entire experiment.
	 */
	void resumed() {
		if (parkedOn != null) {
			traced++;
			if (!parkedOn.equals(mark())) {
				migrated++;
			}
			parkedOn = null;
		}
	}

	/** Called once the reply is sent, to mark the park about to start. */
	void parking() {
		if (SAMPLE > 0 && ++cursor % SAMPLE == 0) {
			parkedOn = mark();
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

	/**
	 * The scheduler answers directly when it has carriers, which costs nothing. The
	 * JDK builtin has none and returns {@code -1} — and that is the run whose
	 * migration rate matters most — so there it falls back to the pool worker's
	 * name, which {@code toString} appends after {@code '@'}.
	 */
	private Object mark() {
		int carrier = scheduler.currentCarrierId();
		if (carrier >= 0) {
			return Integer.valueOf(carrier); // cached below 128: no allocation
		}
		String description = Thread.currentThread().toString();
		int at = description.lastIndexOf('@');
		return at < 0 ? description : description.substring(at + 1);
	}
}
