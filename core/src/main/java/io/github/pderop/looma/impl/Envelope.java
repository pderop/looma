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

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;

/**
 * One entry in an {@link ActorCell}'s mailbox.
 *
 * <p>
 * An envelope carries exactly one of two things, never both and never neither,
 * enforced by the canonical constructor:
 * <ul>
 * <li>a {@code message} to deliver to the actor via {@code onReceive},
 * attributed to {@code sender}; or
 * <li>a {@code lifecycle} step — a plain {@link Runnable} — to run instead of
 * delivering anything.
 * </ul>
 *
 * <p>
 * The lifecycle case is what lets every actor life-cycle transition (its first
 * start, a restart's before/after hooks, its final stop) share the exact same
 * mailbox and loop as an ordinary message: a lifecycle step is simply queued
 * like any envelope and runs with the same exclusivity guarantee. This is also
 * why the two kinds must stay distinguishable at the point a cell decides
 * whether to accept an incoming envelope at all: an actor that is winding down
 * stops accepting new messages, but it must keep accepting (and must never
 * drop) the lifecycle envelope that runs its own shutdown step — otherwise that
 * step, and everything chained after it, would never run.
 * {@link #isLifecycle()} is the cheap, branch-free way to tell the two apart at
 * that decision point.
 */
record Envelope(Message message, ActorRef sender, Runnable lifecycle) {

	Envelope {
		if ((message == null) == (lifecycle == null)) {
			throw new IllegalArgumentException("an envelope must carry exactly one of message or lifecycle");
		}
	}

	/**
	 * Creates an ordinary message envelope.
	 */
	static Envelope message(Message message, ActorRef sender) {
		return new Envelope(message, sender, null);
	}

	/**
	 * Creates a lifecycle envelope: {@code step} runs in place of a message
	 * delivery, under the same mailbox exclusivity guarantee.
	 */
	static Envelope lifecycle(Runnable step) {
		return new Envelope(null, null, step);
	}

	/**
	 * Returns whether this envelope is a lifecycle step rather than a message.
	 */
	boolean isLifecycle() {
		return lifecycle != null;
	}
}
