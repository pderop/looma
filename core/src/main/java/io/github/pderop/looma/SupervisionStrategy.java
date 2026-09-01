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
 * Decides what happens to an actor whose {@link Actor#onReceive} threw.
 *
 * <p>
 * An actor's effective strategy is either the one passed explicitly at
 * {@code spawn} time, via
 * {@link SpawnOptions#supervisedBy(SupervisionStrategy)}
 * ({@link ActorContext#spawn(String, java.util.function.Supplier, SpawnOptions)},
 * {@link ActorSystem#spawn(String, java.util.function.Supplier, SpawnOptions)})
 * — held for the life of the actor — or, absent that,
 * {@link Actor#supervisorStrategy()}, which is called afresh on every failure.
 * A strategy that counts failures therefore belongs on the {@code spawn} call,
 * not in a {@code supervisorStrategy()} that allocates one per failure.
 *
 * <p>
 * <b>Only {@link Actor#onReceive} is supervised.</b> Each lifecycle hook has
 * its own fixed policy instead, and no strategy is consulted for any of them: a
 * throwing {@link Actor#preStart} stops the actor, a throwing
 * {@link Actor#postStop} is logged and changes nothing, and a throwing
 * {@link Actor#preRestart}/{@link Actor#postRestart} turns the restart it was
 * part of into a {@link Directive#STOP}.
 *
 * <p>
 * A strategy that itself throws while deciding, and a {@link Directive#RESTART}
 * whose instance {@link java.util.function.Supplier} throws or returns
 * {@code null}, are both treated as {@link Directive#STOP} and logged.
 */
@FunctionalInterface
public interface SupervisionStrategy {

	/**
	 * Decides how to react to a failure.
	 *
	 * <p>
	 * A strategy is a lambda:
	 *
	 * <pre>{@code
	 * SupervisionStrategy restartOnIo = cause -> cause instanceof IOException ? Directive.RESTART : Directive.STOP;
	 * }</pre>
	 *
	 * @param cause
	 *            the throwable that was caught — a {@link Throwable}, not a
	 *            {@code RuntimeException}, so that a checked exception (declared by
	 *            {@link Actor#onReceive}) or an {@link Error} also reaches
	 *            supervision instead of silently killing the actor's loop.
	 */
	Directive decide(Throwable cause);
}
