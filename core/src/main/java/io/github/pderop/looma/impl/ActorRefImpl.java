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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;

/**
 * The opaque {@link ActorRef} handed out for a live, registered actor: a thin
 * wrapper around its {@link ActorCell} that routes {@code tell} into the cell's
 * mailbox.
 *
 * <p>
 * Package-private, like the rest of the engine: the only caller that needs to
 * walk from a plain {@link ActorRef} down to its {@link ActorCell#dispatcher()}
 * — {@code DefaultActorSystem.homeCarrierIdOf} — lives in this same package. A
 * reference that reaches the API but is not one of these (a {@link PromiseRef},
 * say) is rejected there rather than cast.
 */
final class ActorRefImpl implements ActorRef {

	private final ActorCell cell;

	ActorRefImpl(ActorCell cell) {
		this.cell = cell;
	}

	/**
	 * Returns the cell this reference points to.
	 */
	public ActorCell cell() {
		return cell;
	}

	@Override
	public String name() {
		return cell.name();
	}

	@Override
	public String path() {
		return cell.path();
	}

	@Override
	public void tell(Message message, ActorRef sender) {
		cell.enqueue(Envelope.message(message, sender));
	}

	/**
	 * Creates a single-use {@link PromiseRef} as the message's {@code sender}, then
	 * enqueues the message exactly as {@link #tell(Message, ActorRef)} would. There
	 * is no timeout here: an actor that never replies leaves the future open; the
	 * caller bounds it with {@link CompletableFuture#orTimeout} if they need to.
	 *
	 * <p>
	 * Rejects synchronously, before creating anything, if this actor is already
	 * known to be terminating or stopped, or if the system is shutting down. That
	 * is deliberately a different, stronger guarantee than
	 * {@link #tell(Message, ActorRef)}'s silent drop: a request known to be
	 * unanswerable at call time fails synchronously with
	 * {@link IllegalStateException} rather than being queued to a mailbox that will
	 * simply never deliver a reply. A request that passes this check but whose
	 * target then stops before replying is a different, unavoidable case — the
	 * future stays open, since by then the message has already been handed to the
	 * mailbox.
	 *
	 * <p>
	 * {@code replyType} is carried by the {@link PromiseRef} and checked there, on
	 * the thread the reply arrives on — see
	 * {@link PromiseRef#tell(Message, ActorRef)}. Nothing is cast here.
	 */
	@Override
	public <R extends Message> CompletableFuture<R> ask(Message message, Class<R> replyType) {
		Objects.requireNonNull(replyType, "replyType");
		ActorRuntime runtime = cell.runtime();
		if (runtime.isShuttingDown() || cell.isTerminatingOrStopped()) {
			return CompletableFuture
					.failedFuture(new IllegalStateException("cannot ask a stopped or shutting-down actor: " + path()));
		}
		CompletableFuture<R> future = new CompletableFuture<>();
		PromiseRef<R> promise = new PromiseRef<>(path(), replyType, future);
		cell.enqueue(Envelope.message(message, promise));
		return future;
	}

	@Override
	public String toString() {
		return path();
	}
}
