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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Message;
import io.github.pderop.looma.spi.MailboxQueue;
import io.github.pderop.looma.spi.MailboxQueueFactory;

/**
 * {@link ActorSystem.Builder#mailboxQueueFactory} is the seam a caller uses to
 * plug a queue other than {@code ConcurrentLinkedQueue}: one factory per
 * system, one queue instance per actor.
 */
class MailboxQueueFactoryTest {

	private record Ping() implements Message {
	}

	private record Pong() implements Message {
	}

	private ActorSystem system;

	@AfterEach
	void shutdown() throws InterruptedException {
		if (system != null) {
			system.shutdown();
			system.awaitTermination(2, TimeUnit.SECONDS);
		}
	}

	@Test
	void pluggedFactoryCreatesOneQueuePerActorAndSeesOfferAndPoll() throws Exception {
		AtomicInteger created = new AtomicInteger();
		AtomicInteger offers = new AtomicInteger();
		AtomicInteger polls = new AtomicInteger();
		MailboxQueueFactory factory = new MailboxQueueFactory() {
			@Override
			public <E> MailboxQueue<E> create() {
				created.incrementAndGet();
				MailboxQueue<E> inner = ConcurrentLinkedMailboxQueue.factory().create();
				return new MailboxQueue<>() {
					@Override
					public boolean offer(E item) {
						offers.incrementAndGet();
						return inner.offer(item);
					}

					@Override
					public E poll() {
						polls.incrementAndGet();
						return inner.poll();
					}

					@Override
					public boolean isEmpty() {
						return inner.isEmpty();
					}
				};
			}
		};

		system = ActorSystem.builder().mailboxQueueFactory(factory).build();
		CompletableFuture<Message> done = new CompletableFuture<>();
		ActorRef ref = system.spawn("a", () -> (Actor) (message, sender, ctx) -> {
			if (message instanceof Ping) {
				sender.tell(new Pong(), ctx.self());
			}
		});
		ref.ask(new Ping()).thenAccept(done::complete);
		assertTrue(done.get(2, TimeUnit.SECONDS) instanceof Pong);
		assertTrue(created.get() >= 1, "factory must create a queue per spawned actor, was " + created.get());
		assertTrue(offers.get() >= 1, "tell/ask must offer into the plugged queue, was " + offers.get());
		assertTrue(polls.get() >= 1, "the loop must poll the plugged queue, was " + polls.get());
	}
}
