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

/**
 * What a {@link SupervisionStrategy} decides to do about an actor whose
 * {@link Actor#onReceive} threw. Lifecycle hooks are not supervised; see
 * {@link SupervisionStrategy} for the fixed policy each of them carries.
 *
 * <p>
 * Supervision here is <b>self-supervision only</b>: every directive is applied
 * by the failing actor's own strategy ({@link Actor#supervisorStrategy()}, or
 * the one given at spawn time). Unlike Akka, no failure is escalated to a
 * parent, and {@link #RESTART} leaves the actor's children running — see
 * {@link Actor#preRestart} for the exact restart sequence.
 */
public enum Directive {

	/**
	 * Log the failure, discard the message being processed, and keep running with
	 * the same instance and its existing state.
	 */
	RESUME,

	/**
	 * Discard the failing instance in favour of a fresh one, per
	 * {@link Actor#preRestart} / {@link Actor#postRestart}, then resume draining
	 * the mailbox on the new instance. The failed message itself is discarded, not
	 * retried.
	 *
	 * <p>
	 * Children are left running, untouched — so an actor that spawns its children
	 * in {@link Actor#preStart} cannot be restarted: the default
	 * {@link Actor#postRestart} re-runs {@code preStart}, which spawns at a path
	 * the surviving old child still holds, and the restart escalates to
	 * {@link #STOP}. Spawn children on a message instead if the actor is meant to
	 * be restartable.
	 */
	RESTART,

	/**
	 * Stop the actor: run the graceful cascading stop on it and its children, then
	 * unregister it.
	 */
	STOP
}
