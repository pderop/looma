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
 * A pure marker interface for values sent between actors.
 *
 * <p>
 * Implement it with a {@code record} (or any other type); this project imposes
 * no shape on a message's payload beyond this marker, which exists only to keep
 * {@link ActorRef#tell} and {@link ActorRef#ask} compile-time-safe against
 * passing an arbitrary {@link Object}.
 *
 * <p>
 * A message carries no routing of its own: where it goes is decided entirely by
 * the {@link ActorRef} it is {@code tell}/{@code ask}ed through, so an
 * implementer of this interface never fills in a destination field.
 *
 * <p>
 * A typical message set. Loose records are enough; a sealed protocol is what
 * makes {@code onReceive} exhaustive:
 *
 * <pre>{@code
 * public sealed interface AccountMessage extends Message {
 * 	record Deposit(long cents) implements AccountMessage {
 * 	}
 * 	record Withdraw(long cents) implements AccountMessage {
 * 	}
 * 	record GetBalance() implements AccountMessage {
 * 	}
 * }
 * public record Balance(long cents) implements Message {
 * }
 * }</pre>
 *
 * <p>
 * {@code Message} itself is not sealed: the engine delivers whatever an
 * {@link ActorRef} was handed, and every actor has its own types. Sealing
 * <em>one</em> actor's protocol is an application choice, not an API one.
 */
public interface Message {
}
