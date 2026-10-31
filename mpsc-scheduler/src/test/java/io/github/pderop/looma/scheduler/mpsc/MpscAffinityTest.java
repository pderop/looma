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
package io.github.pderop.looma.scheduler.mpsc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the MPSC scheduler promises an actor, with stealing on: an actor runs
 * only on its home carrier, while the threads it offloads can be stolen.
 *
 * <p>
 * The carrier is the one the thread runs on, read from its name. Under stealing
 * it is not always the home carrier: that is what is tested.
 */
class MpscAffinityTest {

	private record Ping() implements Message {
	}

	/** Offloads a thread, then keeps the carrier busy until the thread ran. */
	private record OffloadAndSpin(CompletableFuture<Integer> ranOn) implements Message {
	}

	private ActorSystem system;

	@BeforeEach
	void setUp() {
		system = ActorSystem.builder().build();
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		system.shutdown();
		assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
	}

	@Test
	void anActorRunsOnlyOnItsHomeCarrier() throws Exception {
		int actors = system.carrierCount() * 4;
		int messages = 500;
		AtomicInteger away = new AtomicInteger();
		CountDownLatch done = new CountDownLatch(actors);
		List<ActorRef> refs = new ArrayList<>();
		for (int a = 0; a < actors; a++) {
			int[] home = {-2};
			refs.add(system.spawn("a" + a, () -> new Actor() {

				private int seen;

				@Override
				public void onReceive(Message message, ActorRef sender, ActorContext context) {
					if (home[0] == -2) {
						home[0] = system.homeCarrierIdOf(context.self());
					}
					if (MpscPool.currentCarrier() != home[0]) {
						away.incrementAndGet();
					}
					// Some work, so that carriers fall idle and steal.
					long end = System.nanoTime() + 20_000;
					while (System.nanoTime() < end) {
						Thread.onSpinWait();
					}
					if (++seen == messages) {
						done.countDown();
					}
				}
			}));
		}
		for (int m = 0; m < messages; m++) {
			for (ActorRef ref : refs) {
				ref.tell(new Ping());
			}
		}

		assertTrue(done.await(30, TimeUnit.SECONDS));
		assertEquals(0, away.get(), "an actor ran on another carrier than its home");
	}

	@Test
	void anOffloadedThreadCanBeStolen() throws Exception {
		ActorRef ref = system.spawn("busy", () -> (message, sender, context) -> {
			if (message instanceof OffloadAndSpin(CompletableFuture<Integer> ranOn)) {
				context.vThreadFactory().newThread(() -> ranOn.complete(MpscPool.currentCarrier())).start();
				// The actor never parks: only another carrier can run the thread.
				long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				while (!ranOn.isDone() && System.nanoTime() < end) {
					Thread.onSpinWait();
				}
			}
		});
		CompletableFuture<Integer> ranOn = new CompletableFuture<>();

		ref.tell(new OffloadAndSpin(ranOn));

		int carrier = ranOn.get(10, TimeUnit.SECONDS);
		assertTrue(carrier >= 0, "the offloaded thread did not run on an MPSC carrier");
		assertNotEquals(system.homeCarrierIdOf(ref), carrier, "the offloaded thread was not stolen");
	}
}
