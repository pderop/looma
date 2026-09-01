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

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;

/**
 * The single-use, transient {@link ActorRef} that {@link ActorRefImpl#ask}
 * passes as a message's {@code sender}, so that the receiving actor replies to
 * an {@code ask} exactly as it would to any other sender.
 *
 * <p>
 * A {@code PromiseRef} is deliberately <b>not</b> a registered
 * {@link ActorCell}: it is never added to {@link ActorRuntime}'s path registry
 * and owns neither a mailbox nor a dispatcher. {@code ask} sits on the
 * benchmark hot path alongside plain {@code tell}, and a registry entry plus a
 * mailbox per request would make the JDK-vs-carrier comparison measure this
 * engine's own bookkeeping rather than the two schedulers it exists to compare.
 * Instead, {@link #tell(Message, ActorRef)} — the only operation a receiving
 * actor ever performs on it, via {@code sender.tell(reply, self)} — resolves
 * the {@link CompletableFuture} directly, on whatever thread the reply happens
 * to arrive on.
 *
 * <p>
 * Completes at most once: {@link CompletableFuture#complete} /
 * {@link CompletableFuture#completeExceptionally} already guarantee that. Every
 * {@code tell} after the first — a second reply, or one arriving after the
 * caller timed the future out with {@code orTimeout} — is silently dropped and
 * logged at {@link Level#DEBUG}. Package-private: nothing outside {@code ask}'s
 * own implementation in {@link ActorRefImpl} ever needs to name this type.
 *
 * <p>
 * <b>This is where {@code ask}'s reply type is enforced.</b> A reply that is
 * not a {@link #replyType} settles the promise too, exceptionally — see
 * {@link #tell(Message, ActorRef)}.
 *
 * @param <R>
 *            the reply type the caller asked for
 */
final class PromiseRef<R extends Message> implements ActorRef {

	private static final Logger LOG = System.getLogger(PromiseRef.class.getName());

	private static final AtomicLong NEXT_ID = new AtomicLong();

	private final long id = NEXT_ID.getAndIncrement();

	/**
	 * The path of the actor this promise was created to {@code ask} — kept only for
	 * the debug log and the {@link ClassCastException} message below, never
	 * returned by {@link #path()}, which names this transient ref itself.
	 */
	private final String targetPath;

	private final CompletableFuture<R> future;

	/**
	 * The type the caller required the reply to have. Checked on arrival rather
	 * than trusted: without it the cast below would be unchecked, and a wrong reply
	 * would surface as a {@link ClassCastException} at some unrelated later use of
	 * the value instead of at the one place that knows what was expected.
	 */
	private final Class<R> replyType;

	/**
	 * @param targetPath
	 *            the path of the actor being asked, for diagnostics only
	 * @param replyType
	 *            the type the reply must have, from the caller's {@code ask}
	 * @param future
	 *            the future {@link ActorRefImpl#ask} returns to its caller
	 */
	PromiseRef(String targetPath, Class<R> replyType, CompletableFuture<R> future) {
		this.targetPath = targetPath;
		this.replyType = replyType;
		this.future = future;
	}

	@Override
	public String name() {
		return "ask-" + id;
	}

	@Override
	public String path() {
		return "/temp/" + name();
	}

	/**
	 * Settles {@link #future} with {@code message}, the first time this is called.
	 * Every subsequent call — a second reply, or one arriving after the caller
	 * already completed the future (typically {@code orTimeout}) — is dropped and
	 * logged at {@link Level#DEBUG}.
	 *
	 * <p>
	 * A reply that is not a {@link #replyType} fails the future with a
	 * {@link ClassCastException} naming both types. It <b>settles</b> the promise
	 * rather than being ignored: the caller stated what it required, so the honest
	 * answer is that the contract was broken, delivered where that is still
	 * attributable — not a later reply that would silently paper over the first
	 * actor's bug.
	 */
	@Override
	public void tell(Message message, ActorRef sender) {
		boolean settled;
		if (replyType.isInstance(message)) {
			settled = future.complete(replyType.cast(message));
		} else {
			settled = future.completeExceptionally(new ClassCastException("ask to " + targetPath
					+ " expected a reply of " + replyType.getName() + " but the actor replied with "
					+ (message == null ? "null" : message.getClass().getName())));
		}
		if (!settled) {
			LOG.log(Level.DEBUG,
					() -> "dropped a reply to an already-settled ask of " + targetPath + " (" + path() + ")");
		}
	}

	/**
	 * A {@code PromiseRef} is a reply channel, not an actor with a mailbox of its
	 * own: asking it would need somewhere to deliver a reply-to-a-reply, which this
	 * type deliberately does not have.
	 */
	@Override
	public <T extends Message> CompletableFuture<T> ask(Message message, Class<T> replyType) {
		throw new UnsupportedOperationException("a transient ask reply channel cannot itself be asked");
	}

	@Override
	public String toString() {
		return path();
	}
}
