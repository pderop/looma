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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;
import io.github.pderop.looma.SpawnOptions;

/**
 * The {@link ActorRef} handed out for a pool — a {@code spawn} whose
 * {@link SpawnOptions#poolSize()} was above {@code 1}: a router over the pool's
 * actors, spreading every {@code tell} and {@code ask} across them round-robin.
 *
 * <p>
 * <b>This reference owns no mailbox, no loop and no actor instance of its
 * own.</b> It is a plain fan-out over {@link ActorRefImpl}s, so a message pays
 * exactly one hop, the same as to a single actor: there is no router actor in
 * the middle to relay it, and therefore no extra queue and no extra virtual
 * thread. Every routee is an ordinary actor — registered at its own path,
 * visible in its parent's {@code children()}, supervised on its own, stopped by
 * the same cascade — and the only thing this class adds is which one a given
 * message goes to.
 *
 * <p>
 * Two consequences of owning nothing, both of them user-visible and both
 * documented on {@code ActorSystem.spawn}:
 * <ul>
 * <li>{@link #path()} names the pool, e.g. {@code "/db"} for routees at
 * {@code "/db-0"}…{@code "/db-3"}, but nothing is registered under it: a
 * {@code findActor} of that path finds nothing, since the live actors are the
 * routees;
 * <li>ordering is per-routee, never pool-wide. Two messages sent through this
 * reference land in two different mailboxes and are processed concurrently —
 * which is the entire point of a pool, and the one guarantee a caller gives up
 * by using one.
 * </ul>
 */
final class PoolRef implements ActorRef {

	private final String name;

	private final String path;

	/**
	 * The pool's routees, in spawn order and never modified afterwards: a routee
	 * that stops is <em>not</em> removed from this array — see {@link #next()} for
	 * what happens to a message that would have gone to it.
	 */
	private final ActorRefImpl[] routees;

	/**
	 * The round-robin cursor, private to this pool: one pool's traffic never
	 * advances another's. Read with {@link Math#floorMod}, so the eventual
	 * {@code int} overflow keeps distributing evenly instead of wrapping into a
	 * negative index.
	 */
	private final AtomicInteger cursor = new AtomicInteger();

	/**
	 * @param name
	 *            the pool's simple name, as passed to {@code spawn} — the routees
	 *            carry {@code name-0}…{@code name-(n-1)}, this carries {@code name}
	 * @param path
	 *            the pool's path, composed exactly as a single actor of that name
	 *            would have been; no actor is registered under it
	 * @param cells
	 *            the pool's cells, at least two (a pool of one is not wrapped at
	 *            all — {@code spawn} returns the cell's own reference)
	 */
	PoolRef(String name, String path, List<ActorCell> cells) {
		this.name = name;
		this.path = path;
		this.routees = cells.stream().map(ActorCell::selfRef).toArray(ActorRefImpl[]::new);
	}

	/**
	 * Returns this pool's cells, in spawn order — how {@link ActorCell#cellsOf}
	 * turns a {@code stop} of this reference into a stop of every routee.
	 */
	List<ActorCell> cells() {
		return Arrays.stream(routees).map(ActorRefImpl::cell).toList();
	}

	/**
	 * Returns the routee this message goes to: the next one in round-robin order,
	 * skipping any that is already terminating or stopped.
	 *
	 * <p>
	 * The skip is what keeps a stopped routee from silently blackholing its
	 * {@code 1/n} share of the pool's traffic — {@code tell} to a stopped actor is
	 * dropped, per this project's dead-letter semantics, and a load balancer that
	 * kept feeding a dead routee would turn that into a steady leak. It is a
	 * bounded scan of at most {@code n} volatile reads, not a liveness protocol: a
	 * routee that stops between this check and the {@code tell} is the ordinary
	 * race, and its message is dropped exactly as it would be for a single actor.
	 * When every routee is gone the first pick is returned anyway, so the caller
	 * gets the usual behaviour of sending to a stopped actor — a silent drop from
	 * {@code tell}, a synchronously failed future from {@code ask} — rather than a
	 * different exception from here.
	 */
	private ActorRefImpl next() {
		int start = Math.floorMod(cursor.getAndIncrement(), routees.length);
		for (int probe = 0; probe < routees.length; probe++) {
			ActorRefImpl candidate = routees[(start + probe) % routees.length];
			if (!candidate.cell().isTerminatingOrStopped()) {
				return candidate;
			}
		}
		return routees[start];
	}

	/**
	 * Returns the pool's simple name, e.g. {@code "db"} for routees named
	 * {@code "db-0"}…{@code "db-3"}.
	 */
	@Override
	public String name() {
		return name;
	}

	/**
	 * Returns the pool's path, e.g. {@code "/db"} — composed exactly as a single
	 * actor of that name would have been, but resolving to nothing: see this
	 * class's javadoc.
	 */
	@Override
	public String path() {
		return path;
	}

	/**
	 * Delivers {@code message} to one routee, chosen by {@link #next()}. Beyond the
	 * choice, this is the ordinary single-actor {@code tell} — same one hop, same
	 * attribution of {@code sender}, same silent drop if that routee has stopped.
	 */
	@Override
	public void tell(Message message, ActorRef sender) {
		next().tell(message, sender);
	}

	/**
	 * Asks one routee, chosen by {@link #next()}. The reply comes from that routee
	 * alone — a pool answers an {@code ask} once, never {@code poolSize} times —
	 * and every guarantee of {@link ActorRefImpl#ask} applies unchanged, including
	 * the checked {@code replyType} and the synchronous failure when the chosen
	 * routee is already known to be stopped.
	 */
	@Override
	public <R extends Message> CompletableFuture<R> ask(Message message, Class<R> replyType) {
		return next().ask(message, replyType);
	}

	@Override
	public String toString() {
		return path + "[" + routees.length + "]";
	}
}
