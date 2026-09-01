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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ActorRef#ask(Message, Class)}: the request/reply half of the API,
 * built on a single-use transient reply channel rather than a registered actor.
 *
 * <p>
 * Two rejection outcomes are pinned separately here, because they are genuinely
 * different and only one of them is synchronous: an {@code ask} <b>rejected at
 * call time</b> (target already stopped, or the system shutting down) fails
 * with {@link IllegalStateException}, while an {@code ask} that was accepted
 * and whose target then never replies leaves the future open — the caller
 * bounds it with {@link CompletableFuture#orTimeout}.
 */
public abstract class AskTest {

	private record Echo(String text) implements Message {
	}

	private record Silence() implements Message {
	}

	/**
	 * Asks the actor to reply twice to the same request — the second reply must be
	 * dropped by the already-settled promise.
	 */
	private record EchoTwice(String text) implements Message {
	}

	/**
	 * Asks the actor to wait for a latch the test releases, then reply — used to
	 * make a reply arrive strictly after the caller timed the future out.
	 */
	private record ReplyWhenReleased(String text) implements Message {
	}

	private record Reply(String text) implements Message {
	}

	/**
	 * Asks the actor to reply with a {@link Wrong} instead of a {@link Reply} — the
	 * declared reply type of the {@code ask} must then be what fails the future.
	 */
	private record EchoWrongType(String text) implements Message {
	}

	private record Wrong() implements Message {
	}

	/**
	 * The two shapes a {@link Lookup} can answer with, under one sealed supertype:
	 * the idiomatic way to keep a multi-shaped reply typed at the {@code ask} site.
	 */
	private sealed interface LookupResult extends Message permits Found, NotFound {
	}

	private record Found(String value) implements LookupResult {
	}

	private record NotFound() implements LookupResult {
	}

	private record Lookup(boolean hit) implements Message {
	}

	protected abstract ActorSystem newActorSystem();

	private ActorSystem system;

	@BeforeEach
	void setUp() {
		system = newActorSystem();
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		system.shutdown();
		assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
	}

	@Test
	void aReplyResolvesTheFuture() throws Exception {
		ActorRef echo = system.spawn("echo", EchoActor::new);

		Message reply = echo.ask(new Echo("hi")).get(10, TimeUnit.SECONDS);

		assertEquals(new Reply("hi"), reply);
	}

	@Test
	void anUnansweredAskStaysOpenUntilTheCallerTimesItOut() {
		ActorRef echo = system.spawn("echo", EchoActor::new);

		ExecutionException e = assertThrows(ExecutionException.class,
				() -> echo.ask(new Silence()).orTimeout(100, TimeUnit.MILLISECONDS).get());

		assertInstanceOf(TimeoutException.class, e.getCause());
	}

	/**
	 * A second reply to the same request — and, in the case below, a reply that
	 * arrives after the caller already timed the future out — is dropped silently.
	 * The future completes exactly once, whichever of the two got there first.
	 */
	@Test
	void aSecondReplyIsDropped() throws Exception {
		ActorRef echo = system.spawn("echo", EchoActor::new);

		Message reply = echo.ask(new EchoTwice("hi")).get(10, TimeUnit.SECONDS);

		assertEquals(new Reply("hi"), reply);
	}

	@Test
	void aReplyArrivingAfterTheTimeoutIsHarmlesslyDropped() throws Exception {
		CountDownLatch release = new CountDownLatch(1);

		ActorRef echo = system.spawn("echo", () -> new EchoActor(release));

		CompletableFuture<Message> future = echo.ask(new ReplyWhenReleased("late")).orTimeout(100,
				TimeUnit.MILLISECONDS);

		ExecutionException e = assertThrows(ExecutionException.class, future::get);
		assertInstanceOf(TimeoutException.class, e.getCause());

		// Only now does the actor reply -- into a promise that is already settled.
		release.countDown();

		/*
		 * Nothing observable should happen. What this really guards is that the drop
		 * neither throws on the actor's own thread nor re-completes the future; a
		 * subsequent ask still working proves the actor survived delivering it.
		 */
		assertEquals(new Reply("hi"), echo.ask(new Echo("hi")).get(10, TimeUnit.SECONDS));
		assertTrue(future.isCompletedExceptionally());
	}

	/**
	 * Asking an actor that has already <em>fully</em> terminated is rejected
	 * synchronously. Note the deliberate wait for its termination first: asking an
	 * actor that is merely stopping concurrently would be racy by construction —
	 * the message can still reach a mailbox that will never deliver a reply, and
	 * the future would stay open unless the caller times it out.
	 */
	@Test
	void askingAFullyStoppedActorFailsImmediately() throws Exception {
		ActorRef echo = system.spawn("echo", EchoActor::new);

		system.stop(echo);
		waitUntil(() -> system.findActor("/echo").isEmpty());

		ExecutionException e = assertThrows(ExecutionException.class, () -> echo.ask(new Echo("hi")).get());

		assertInstanceOf(IllegalStateException.class, e.getCause());
	}

	// ================================================================
	// The declared reply type
	// ================================================================

	/**
	 * The whole point of the two-argument form: the future is typed, so the caller
	 * neither casts nor pattern-matches to get at the reply.
	 */
	@Test
	void aDeclaredReplyTypeIsHandedBackWithoutACast() throws Exception {
		ActorRef echo = system.spawn("echo", EchoActor::new);

		Reply reply = echo.ask(new Echo("hi"), Reply.class).get(10, TimeUnit.SECONDS);

		assertEquals("hi", reply.text());
	}

	/**
	 * A reply of the wrong type fails the future <em>where it happened</em>, rather
	 * than being handed to the caller to blow up later. It also settles the promise
	 * — see {@link #aReplyAfterAWrongTypedOneIsDropped()} — and the exception names
	 * both types, which is what makes it actionable.
	 */
	@Test
	void aReplyOfTheWrongTypeFailsTheFuture() {
		ActorRef echo = system.spawn("echo", EchoActor::new);

		ExecutionException e = assertThrows(ExecutionException.class,
				() -> echo.ask(new EchoWrongType("hi"), Reply.class).get());

		ClassCastException cause = assertInstanceOf(ClassCastException.class, e.getCause());
		assertTrue(cause.getMessage().contains(Reply.class.getName()), cause.getMessage());
		assertTrue(cause.getMessage().contains(Wrong.class.getName()), cause.getMessage());
	}

	/**
	 * A wrong-typed reply is a settlement, not a skipped message: the correct reply
	 * the actor sends straight afterwards must not quietly overwrite it. Otherwise
	 * an actor whose first reply is a bug would look healthy whenever it managed to
	 * send a second one.
	 */
	@Test
	void aReplyAfterAWrongTypedOneIsDropped() {
		ActorRef echo = system.spawn("echo", EchoActor::new);

		CompletableFuture<Reply> future = echo.ask(new EchoWrongType("hi"), Reply.class);

		ExecutionException e = assertThrows(ExecutionException.class, future::get);
		assertInstanceOf(ClassCastException.class, e.getCause());
	}

	/**
	 * A reply with several shapes stays typed at the ask site through their common
	 * sealed supertype — no cast, and {@code switch} still exhaustive.
	 */
	@Test
	void aSealedSupertypeAcceptsEveryShapeOfReply() throws Exception {
		ActorRef echo = system.spawn("echo", EchoActor::new);

		LookupResult hit = echo.ask(new Lookup(true), LookupResult.class).get(10, TimeUnit.SECONDS);
		LookupResult miss = echo.ask(new Lookup(false), LookupResult.class).get(10, TimeUnit.SECONDS);

		assertEquals(new Found("value"), hit);
		assertEquals(new NotFound(), miss);
	}

	/**
	 * The one-argument form is exactly the two-argument one at
	 * {@code Message.class}, so it accepts any reply — including one no declared
	 * type would have allowed.
	 */
	@Test
	void theOneArgFormAcceptsAnyReply() throws Exception {
		ActorRef echo = system.spawn("echo", EchoActor::new);

		Message reply = echo.ask(new EchoWrongType("hi")).get(10, TimeUnit.SECONDS);

		assertEquals(new Wrong(), reply);
	}

	@Test
	void aNullReplyTypeIsRejected() {
		ActorRef echo = system.spawn("echo", EchoActor::new);

		assertThrows(NullPointerException.class, () -> echo.ask(new Echo("hi"), null));
	}

	private void waitUntil(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.getAsBoolean()) {
			assertTrue(System.nanoTime() < deadline, "the condition never became true");
			Thread.sleep(1);
		}
	}

	// ================================================================
	// Actors
	// ================================================================

	private static final class EchoActor implements Actor {

		private final CountDownLatch release;

		EchoActor() {
			this(null);
		}

		EchoActor(CountDownLatch release) {
			this.release = release;
		}

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) throws Exception {
			switch (message) {
				case Echo echo -> sender.tell(new Reply(echo.text()), context.self());
				case EchoTwice echo -> {
					sender.tell(new Reply(echo.text()), context.self());
					sender.tell(new Reply(echo.text() + "-again"), context.self());
				}
				case ReplyWhenReleased echo -> {
					/*
					 * Blocking the actor's own loop is exactly what an actor should not do -- but
					 * it is the point here: the reply must land strictly after the caller's {@code
					 * orTimeout} has already fired.
					 */
					release.await(10, TimeUnit.SECONDS);
					sender.tell(new Reply(echo.text()), context.self());
				}
				case EchoWrongType ignored -> {
					sender.tell(new Wrong(), context.self());
					/*
					 * A correct reply immediately after the wrong one: the promise is already
					 * settled exceptionally, so this must be dropped rather than repair it.
					 */
					sender.tell(new Reply("hi"), context.self());
				}
				case Lookup lookup -> sender.tell(lookup.hit() ? new Found("value") : new NotFound(), context.self());
				case Silence ignored -> {
					// Deliberately no reply: the future stays open until the caller times it out.
				}
				default -> throw new IllegalStateException("unexpected message: " + message);
			}
		}
	}
}
