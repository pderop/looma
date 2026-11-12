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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Message;
import io.github.pderop.looma.spi.ActorScheduler;

/**
 * A scheduler whose factories differ only by identity must not get the local
 * timer path: only {@code mailboxThreadsStayOnCarrier()} turns it on.
 */
class MailboxThreadsStayOnCarrierTest {

	private record Ping() implements Message {
	}

	/** Returns a new factory on every call and keeps the default signal. */
	private static class FreshFactoryScheduler implements ActorScheduler {
		@Override
		public int carrierCount() {
			return 1;
		}

		@Override
		public ThreadFactory vThreadFactory(int index) {
			return Thread.ofVirtual().factory();
		}

		@Override
		public int currentCarrierId() {
			return -1;
		}

		@Override
		public OptionalInt pinnedCpu() {
			return OptionalInt.empty();
		}

		@Override
		public OptionalInt clusterOf(int index) {
			return OptionalInt.empty();
		}

		@Override
		public void close() {
		}
	}

	@Test
	void defaultSignalIsFalse() {
		assertEquals(false, new FreshFactoryScheduler().mailboxThreadsStayOnCarrier());
	}

	/**
	 * The same scheduler, but it says its mailbox threads stay on carrier 0: the
	 * control that shows the probe can see a binding.
	 */
	private static final class StayingScheduler extends FreshFactoryScheduler {
		@Override
		public boolean mailboxThreadsStayOnCarrier() {
			return true;
		}
	}

	@Test
	void mailboxThreadOfFreshFactorySchedulerHasNoCallerCarrier() throws Exception {
		assertNull(callerCarrierSeenByAMailboxThread(new FreshFactoryScheduler()));
	}

	@Test
	void mailboxThreadOfAStayingSchedulerBindsItsCarrier() throws Exception {
		assertNotNull(callerCarrierSeenByAMailboxThread(new StayingScheduler()));
	}

	/**
	 * Starts the wheel with a timer to a first actor, then asks a second actor,
	 * spawned after, what its mailbox thread sees. Each mailbox thread of that
	 * actor starts after the wheel, so it binds the wheel if the scheduler allows
	 * it.
	 */
	private static CarrierTimer callerCarrierSeenByAMailboxThread(ActorScheduler scheduler) throws Exception {
		ActorSystem system = ActorSystem.builder().scheduler(scheduler).build();
		CompletableFuture<CarrierTimer> seen = new CompletableFuture<>();
		try {
			ActorRef warm = system.spawn("warm", () -> (message, sender, context) -> {
			});
			warm.tell(new Ping(), 1, TimeUnit.HOURS);
			ActorRef probe = system.spawn("probe",
					() -> (message, sender, context) -> seen.complete(((ActorCell) context).runtime().boundTimer()));
			probe.tell(new Ping());
			return seen.get(5, TimeUnit.SECONDS);
		} finally {
			system.shutdown();
			assertTrue(system.awaitTermination(5, TimeUnit.SECONDS));
		}
	}
}
