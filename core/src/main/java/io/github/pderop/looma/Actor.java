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

import java.util.Optional;

/**
 * An actor: a piece of state whose messages are processed one at a time.
 *
 * <p>
 * The {@link ActorSystem} guarantees that
 * {@link #onReceive(Message, ActorRef, ActorContext)} is never invoked
 * concurrently for the same actor instance, so implementations may keep mutable
 * state without any synchronization. The four lifecycle hooks below run under
 * that same guarantee, interleaved with message handling: never concurrently
 * with it, and never concurrently with each other.
 *
 * <p>
 * Each actor is one virtual thread for life; messages and lifecycle hooks run
 * on that loop. See {@link ActorContext#vThreadFactory()} for the per-scheduler
 * affinity guarantee of extra threads created for blocking work.
 *
 * <p>
 * An actor's {@link ActorContext} is threaded through every hook's parameter
 * list, including the very first one ({@link #preStart(ActorContext)}), rather
 * than injected once through a setter — so there is no code path on which a
 * hook or {@link #onReceive} can run before its context exists.
 *
 * <p>
 * A complete actor:
 *
 * <pre>{@code
 * record Greet(String who) implements Message {
 * }
 * record Greeting(String text) implements Message {
 * }
 *
 * class Greeter implements Actor {
 * 	private int greeted; // no synchronization: one message at a time
 *
 * 	public void onReceive(Message message, ActorRef sender, ActorContext context) {
 * 		switch (message) {
 * 			case Greet g -> {
 * 				greeted++;
 * 				if (sender != null) {
 * 					sender.tell(new Greeting("hello " + g.who()), context.self());
 * 				}
 * 			}
 * 			default -> {
 * 			} // unknown messages are this actor's business
 * 		}
 * 	}
 * }
 * }</pre>
 */
public interface Actor {

	/**
	 * Called once, before this actor processes its first message, with the context
	 * it will keep for its whole lifetime (or, after a {@link Directive#RESTART},
	 * the context of the fresh instance created by the
	 * {@link java.util.function.Supplier} passed at spawn time).
	 *
	 * <p>
	 * Runs on the actor's own loop virtual thread, through the same mailbox as
	 * {@link #onReceive}, so it is guaranteed to complete before any message is
	 * delivered — even if no message is ever sent.
	 *
	 * @throws Exception
	 *             if start-up fails; the actor is then stopped immediately, without
	 *             consulting {@link #supervisorStrategy()} (a strategy could only
	 *             restart into the same failure)
	 */
	default void preStart(ActorContext context) throws Exception {
	}

	/**
	 * Called once, after this actor has stopped draining its mailbox and every one
	 * of its children has itself fully stopped, just before it is unregistered.
	 *
	 * <p>
	 * An exception thrown here is logged and never prevents unregistration or the
	 * completion of the actor's termination.
	 */
	default void postStop(ActorContext context) throws Exception {
	}

	/**
	 * Called on the failing instance when {@link #supervisorStrategy()} decides
	 * {@link Directive#RESTART}, just before it is discarded in favour of a fresh
	 * instance obtained from the {@link java.util.function.Supplier} passed at
	 * spawn time.
	 *
	 * <p>
	 * Defaults to {@link #postStop(ActorContext)}: with no overriding need to tell
	 * "stopping for good" and "stopping to restart" apart, the sensible default is
	 * to release whatever {@link #postStop} would release.
	 *
	 * @param cause
	 *            the throwable that triggered the restart
	 * @param message
	 *            the message being processed when it was thrown. Always present:
	 *            only a failure of {@link #onReceive} reaches supervision, and it
	 *            always has a message in hand
	 * @throws Exception
	 *             if thrown, the restart escalates to a {@link Directive#STOP} of
	 *             this actor, logged
	 */
	default void preRestart(Throwable cause, Optional<Message> message, ActorContext context) throws Exception {
		postStop(context);
	}

	/**
	 * Called on the fresh instance obtained after a {@link Directive#RESTART}, once
	 * it is in place but before it resumes draining the mailbox.
	 *
	 * <p>
	 * Defaults to {@link #preStart(ActorContext)}: a fresh instance has, by
	 * construction, never run it.
	 *
	 * @param cause
	 *            the throwable that triggered the restart
	 * @throws Exception
	 *             if thrown, the restart escalates to a {@link Directive#STOP} of
	 *             this actor, logged
	 */
	default void postRestart(Throwable cause, ActorContext context) throws Exception {
		preStart(context);
	}

	/**
	 * Handles a single message.
	 *
	 * <p>
	 * Blocking here <em>parks</em> this actor's loop virtual thread — the carrier
	 * is released and the machine keeps working — but it occupies that loop for the
	 * duration, and the loop is the only consumer of this actor's mailbox. So the
	 * actor answers nothing until the block returns; neighbours, having loops of
	 * their own, are unaffected. Block when there is nothing else this actor could
	 * usefully do meanwhile; offload through {@link ActorContext#vThreadFactory()}
	 * when it must stay responsive to its own next message.
	 *
	 * <p>
	 * A <em>pin</em> ({@code synchronized} around a blocking call, JNI) is the case
	 * that is genuinely costly: it cannot unmount the virtual thread at all, so it
	 * holds the carrier itself — one pool worker under the JDK builtin scheduler,
	 * but every actor sharing that home carrier under a carrier-affine one.
	 *
	 * @param message
	 *            the message to handle
	 * @param sender
	 *            the sender supplied to {@link ActorRef#tell(Message, ActorRef)};
	 *            for a message that arrived through {@link ActorRef#ask}, a
	 *            single-use reference standing for the asker, and replying to it
	 *            with {@code sender.tell(reply, context.self())} is what completes
	 *            the future {@code ask} returned — nothing else does. {@code null}
	 *            only for {@link ActorRef#tell(Message)}, which names no sender
	 * @param context
	 *            this actor's context; reference-identical to the one every other
	 *            hook receives
	 * @throws Exception
	 *             if handling fails; the failure is reported to
	 *             {@link #supervisorStrategy()}. The engine catches
	 *             {@link Throwable}, not just {@code RuntimeException}: a checked
	 *             exception or an {@link Error} goes through supervision exactly
	 *             like a runtime one, rather than silently killing the actor's loop
	 */
	void onReceive(Message message, ActorRef sender, ActorContext context) throws Exception;

	/**
	 * Returns the strategy used to decide what happens to this actor when
	 * {@link #onReceive} throws. Lifecycle hooks are not supervised — see
	 * {@link SupervisionStrategy} for what happens to each of them instead.
	 *
	 * <p>
	 * <b>Called afresh on every failure</b>, on whichever instance is current — so
	 * a strategy may read the instance's own fields, but an implementation that
	 * counts (a bounded number of restarts, say) must keep its counter in a field
	 * of the actor or of the strategy object, never in a strategy allocated by this
	 * method: a {@code return new MaxRestarts(3)} here is handed a fresh counter
	 * every time and its limit never trips. Pass such a strategy at spawn time
	 * instead, where it is held for the life of the actor.
	 *
	 * <p>
	 * Defaults to always resuming, i.e. logging the failure, discarding the message
	 * being processed, and continuing with the same instance and its existing
	 * state.
	 */
	default SupervisionStrategy supervisorStrategy() {
		return cause -> Directive.RESUME;
	}
}
