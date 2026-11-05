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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * An opaque, thread-safe handle to an actor, obtained from
 * {@link ActorSystem#spawn}, {@link ActorContext#spawn}, or one of the
 * {@code findActor} lookups.
 *
 * <p>
 * An {@code ActorRef} carries no state of its own beyond routing information:
 * it may be freely shared across threads, stored in a message, or held past the
 * lifetime of the actor it points to. {@code tell}ing (or {@code ask}ing) a
 * reference whose actor has stopped, or was never known, is silently dropped
 * (respectively fails the returned future) rather than throwing — this
 * project's dead-letter semantics.
 *
 * <p>
 * <b>A reference usually stands for one actor, but not always.</b> A
 * {@code spawn} whose {@link SpawnOptions#poolSize()} is above {@code 1} hands
 * back one reference standing for that many actors, and spreads every
 * {@code tell} and {@code ask} over them round-robin; every method below then
 * means "one of them", chosen per call. Such a reference is a router rather
 * than an actor: {@link #path()} reports the pool's path but no actor is
 * registered there ({@code findActor} finds the pool's actors, at
 * {@code "/db-0"} and so on), {@code stop} on it stops all of them, and
 * {@code homeCarrierIdOf} rejects it. The per-actor ordering an actor
 * guarantees is <em>not</em> a pool-wide ordering: see
 * {@link ActorSystem#spawn(String, java.util.function.Supplier, SpawnOptions)}.
 * To send all the messages of one key to the same actor of a pool, use
 * {@link #forKey(Object)}.
 *
 * <p>
 * Identity is plain reference identity: {@code ActorRef} does not override
 * {@link Object#equals} or {@link Object#hashCode}. Two lookups of the same
 * live actor return the same instance, so reference identity is sufficient in
 * practice; nothing about the interface promises value semantics.
 */
public interface ActorRef {

	/**
	 * Returns the simple name this actor was spawned under, e.g. {@code "worker"}
	 * for an actor whose {@link #path()} is {@code "/db/worker"}.
	 */
	String name();

	/**
	 * Returns the actor that serves {@code key}.
	 *
	 * <p>
	 * For a pool, it is the member chosen by the pool's selector (see
	 * {@link SpawnOptions#withSelector(java.util.function.ToIntFunction)}): the
	 * same key gives the same member for the whole life of the pool. The member is
	 * an ordinary actor: {@code homeCarrierIdOf} works on it, and its timers go to
	 * its own carrier. A stopped member is still returned, and a {@code tell} to it
	 * is dropped, as for any stopped actor. So the same key never moves to another
	 * member.
	 *
	 * <p>
	 * For a single actor, it is this reference.
	 *
	 * <p>
	 * Without a selector, the key's {@code hashCode()} picks the member, after its
	 * bits are mixed so that simple hash codes spread over every member. So two
	 * equal keys must have the same {@code hashCode()}: a {@code String}, an
	 * {@code Integer}, a {@code Long}, a {@code UUID} or a record does; a class
	 * that does not override {@code hashCode()} does not.
	 *
	 * <pre>{@code
	 * ActorRef sessions = system.spawn("sessions", Session::new, SpawnOptions.pooled(8));
	 * ActorRef session = sessions.forKey(sessionId);
	 * session.tell(new Update(...));
	 * session.tell(new Checkout(...)); // the same member as the Update
	 * }</pre>
	 *
	 * @param key
	 *            the key, for example a session id
	 * @return the actor that serves this key
	 * @throws NullPointerException
	 *             if {@code key} is {@code null}
	 * @throws RuntimeException
	 *             whatever the pool's selector throws
	 */
	default ActorRef forKey(Object key) {
		Objects.requireNonNull(key, "key");
		return this;
	}

	/**
	 * Returns the full, slash-separated path of this actor, e.g.
	 * {@code "/db/worker"} for a child named {@code "worker"} spawned under a root
	 * actor named {@code "db"}.
	 *
	 * <p>
	 * A path is what {@link ActorSystem#findActor(String)} resolves, and what
	 * appears in log and error messages. {@link #name()} stays the simple name.
	 */
	String path();

	/**
	 * Sends {@code message} to this actor asynchronously, attributing it to
	 * {@code sender} so the receiving actor can reply with
	 * {@code sender.tell(reply, self)}.
	 *
	 * @param message
	 *            the message to send
	 * @param sender
	 *            the actor to attribute the message to, visible to the receiver as
	 *            {@code onReceive}'s {@code sender} parameter; {@code null} if
	 *            there is no actor to reply to (e.g. sent from a plain thread)
	 * @throws NullPointerException
	 *             if {@code message} is {@code null}
	 */
	void tell(Message message, ActorRef sender);

	/**
	 * Equivalent to {@code tell(message, null)}.
	 *
	 * <p>
	 * For external senders — a {@code main} method, a test thread — that have no
	 * {@code ActorRef} of their own to be replied to.
	 */
	default void tell(Message message) {
		tell(message, null);
	}

	/**
	 * Sends {@code message} to this actor and returns a future completed with its
	 * reply, typed as {@code replyType}.
	 *
	 * <p>
	 * Internally, {@code ask} creates a single-use, unregistered reply channel and
	 * passes it as the message's {@code sender}; the receiving actor replies with
	 * {@code sender.tell(reply, self)} exactly as it would to any other sender.
	 *
	 * <pre>{@code
	 * record GetBalance() implements Message {
	 * }
	 * record Balance(long cents) implements Message {
	 * }
	 *
	 * CompletableFuture<Balance> balance = account.ask(new GetBalance(), Balance.class);
	 * }</pre>
	 *
	 * <p>
	 * <b>{@code replyType} is checked, not merely declared.</b> An actor that
	 * replies with something else fails this future with a
	 * {@link ClassCastException} naming both types, at the moment the wrong reply
	 * arrives — rather than handing the caller a value that blows up somewhere else
	 * later. When a reply legitimately has several shapes, pass their common
	 * supertype (a {@code sealed interface} is the natural one) and {@code switch}
	 * over the result.
	 *
	 * <p>
	 * The returned future completes <b>at most once</b>. A second reply is silently
	 * dropped. There is no timeout on this call: an actor that never replies leaves
	 * the future open. Bound it with
	 * {@link CompletableFuture#orTimeout(long, java.util.concurrent.TimeUnit)
	 * orTimeout} (or {@code get} with a timeout) if you need one.
	 *
	 * <p>
	 * <b>The reply completes the future on the mailbox thread of the actor that
	 * replies</b>. A stage chained with {@code thenApply} or {@code thenAccept}
	 * runs there too, and the replying actor handles nothing else meanwhile. Chain
	 * slow work with {@code thenApplyAsync} or {@code thenAcceptAsync} instead.
	 *
	 * @param <R>
	 *            the reply type, inferred from {@code replyType} — so the caller
	 *            never casts
	 * @param message
	 *            the message to send
	 * @param replyType
	 *            the type the reply is required to have; {@code Message.class}
	 *            accepts any reply
	 * @return a future completed with the reply; failed with
	 *         {@link ClassCastException} if one arrives but is not a
	 *         {@code replyType}; or failed synchronously with
	 *         {@link IllegalStateException} if this actor is already known to be
	 *         stopped or the system is shutting down (a reply that only fails to
	 *         arrive because the target stops <em>after</em> accepting the request
	 *         leaves the future open — bound it with {@code orTimeout})
	 * @throws NullPointerException
	 *             if {@code message} or {@code replyType} is {@code null}
	 */
	<R extends Message> CompletableFuture<R> ask(Message message, Class<R> replyType);

	/**
	 * Equivalent to {@link #ask(Message, Class) ask(message, Message.class)}:
	 * accepts a reply of any type, leaving the caller to {@code switch} over it.
	 *
	 * <p>
	 * Prefer the two-argument form whenever the reply has one expected type, or a
	 * common supertype: it is the one that both spares the caller a cast and
	 * catches a wrong reply where it happens.
	 */
	default CompletableFuture<Message> ask(Message message) {
		return ask(message, Message.class);
	}

	/**
	 * Equivalent to {@code tell(message, null, delay, unit)}.
	 */
	default ScheduledFuture<?> tell(Message message, long delay, TimeUnit unit) {
		return tell(message, null, delay, unit);
	}

	/**
	 * Sends {@code message} to this actor once, after {@code delay}.
	 *
	 * <p>
	 * The precision is one tick of the timer wheel, set with
	 * {@code ActorSystem.Builder#timerTick}. The message never goes early. It goes
	 * about one tick late when the carrier is free, later when it is busy. A delay
	 * of zero or less sends at the next tick.
	 *
	 * <p>
	 * The returned future is done from the start of the send, and {@code get}
	 * returns once the send has returned. {@code cancel} stops the send and returns
	 * {@code false} if the message has already gone, or if the timer was already
	 * cancelled. When it returns {@code true}, nothing is sent. If the actor has
	 * stopped when the delay ends, the message is dropped, as for any {@code tell}.
	 *
	 * <p>
	 * After {@code shutdown} the message is not sent and the future comes back
	 * cancelled. A timer pending at {@code shutdown} is cancelled soon after, by
	 * the timer thread; {@code awaitTermination} does not wait for it.
	 *
	 * @param message
	 *            the message to send
	 * @param sender
	 *            the actor to attribute the message to; {@code null} if there is no
	 *            actor to reply to
	 * @param delay
	 *            how long to wait before sending
	 * @param unit
	 *            the unit of {@code delay}
	 * @return a future to cancel the send
	 * @throws NullPointerException
	 *             if {@code message} or {@code unit} is {@code null}
	 * @throws UnsupportedOperationException
	 *             if this reference does not support timers
	 */
	default ScheduledFuture<?> tell(Message message, ActorRef sender, long delay, TimeUnit unit) {
		throw new UnsupportedOperationException("timers are not supported by " + getClass().getName());
	}

	/**
	 * Sends {@code message} to this actor first after {@code initialDelay}, then
	 * once every {@code period}.
	 *
	 * <p>
	 * The precision is one tick of the timer wheel. A message never goes early.
	 * Each deadline is one period after the previous deadline. When the sends fall
	 * behind, after a pause or with a period shorter than the tick, the missed ones
	 * go one per tick, so the sends never go more than once per tick. The sends
	 * stop when the future is cancelled, once the actor is stopping, or at
	 * {@code shutdown}. A pool stops them once all its actors are stopping. The
	 * future is never done otherwise, so {@code get} blocks until the cancel. A
	 * cancel that races a send may return {@code true} while that one message still
	 * goes.
	 *
	 * @param message
	 *            the message to send
	 * @param sender
	 *            the actor to attribute the message to; {@code null} if there is no
	 *            actor to reply to
	 * @param initialDelay
	 *            how long to wait before the first send
	 * @param period
	 *            the time between two sends
	 * @param unit
	 *            the unit of both times
	 * @return a future to cancel the sends
	 * @throws NullPointerException
	 *             if {@code message} or {@code unit} is {@code null}
	 * @throws IllegalArgumentException
	 *             if {@code period} is zero or negative
	 * @throws UnsupportedOperationException
	 *             if this reference does not support timers
	 */
	default ScheduledFuture<?> tellAtFixedRate(Message message, ActorRef sender, long initialDelay, long period,
			TimeUnit unit) {
		throw new UnsupportedOperationException("timers are not supported by " + getClass().getName());
	}
}
