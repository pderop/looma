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
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Message;
import io.github.pderop.looma.Placement;
import io.github.pderop.looma.SpawnOptions;
import io.github.pderop.looma.TimerTest;
import io.github.pderop.looma.impl.TimerProbe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Runs the shared {@link TimerTest} suite against the MPSC scheduler, chosen by
 * the flags of the {@code default-test} execution (see
 * {@code mpsc-scheduler/pom.xml}).
 */
class MpscTimerTest extends TimerTest {

	@Override
	protected ActorSystem.Builder newBuilder() {
		return ActorSystem.builder();
	}

	@Override
	protected int pendingTimers() {
		return TimerProbe.pendingTimers(system());
	}

	/**
	 * The wheel of the target's carrier sends the message, and the actor handles it
	 * on that same home carrier.
	 */
	@Test
	void aDelayedMessageIsProcessedOnTheTargetsHomeCarrier() throws Exception {
		int carriers = system().carrierCount();
		List<CompletableFuture<int[]>> results = new ArrayList<>();
		for (int c = 0; c < carriers; c++) {
			CompletableFuture<int[]> result = new CompletableFuture<>();
			results.add(result);
			ActorRef ref = system().spawn("home-" + c,
					() -> (message, sender, context) -> result
							.complete(new int[]{MpscPool.currentCarrier(), system().homeCarrierIdOf(context.self())}),
					SpawnOptions.placedOn(Placement.carrier(c)));
			ref.tell(new Ping(), 20, TimeUnit.MILLISECONDS);
		}

		for (int c = 0; c < carriers; c++) {
			int[] ran = results.get(c).get(10, TimeUnit.SECONDS);
			assertEquals(c, ran[1]);
			assertEquals(ran[1], ran[0], "handled away from the home carrier");
		}
	}

	/**
	 * Once the wheel of its carrier runs, an actor that arms a timer on itself and
	 * cancels it sees the wheel hold it, then empty right away: the timer never
	 * went through a queue.
	 */
	@Test
	void armAndCancelOnSelfTouchTheWheelDirectly() throws Exception {
		startWheelOfCarrierZero();
		CompletableFuture<int[]> counts = new CompletableFuture<>();
		ActorRef ref = system().spawn("direct", () -> (message, sender, context) -> {
			var future = context.self().tell(new Ping(), 30, TimeUnit.SECONDS);
			int armed = TimerProbe.pendingTimers(system());
			future.cancel(false);
			counts.complete(new int[]{armed, TimerProbe.pendingTimers(system())});
		}, SpawnOptions.placedOn(Placement.carrier(0)));
		ref.tell(new Ping());

		assertArrayEquals(new int[]{1, 0}, counts.get(10, TimeUnit.SECONDS));
	}

	/**
	 * Two systems in one JVM. An actor of the first one, whose wheel runs, arms a
	 * timer on a pool of the second one: the timer goes to a wheel of the second
	 * system, never into the wheel the actor has marked.
	 */
	@Test
	void aPoolTimerOfAnotherSystemStaysInThatSystem() throws Exception {
		startWheelOfCarrierZero();
		ActorSystem other = ActorSystem.builder().build();
		try {
			ActorRef pool = other.spawn("pool", () -> (message, sender, context) -> {
			}, SpawnOptions.pooled(2));
			CompletableFuture<Integer> ownAfterArm = new CompletableFuture<>();
			ActorRef caller = system().spawn("caller", () -> (message, sender, context) -> {
				pool.tell(new Ping(), 30, TimeUnit.SECONDS);
				ownAfterArm.complete(TimerProbe.pendingTimers(system()));
			}, SpawnOptions.placedOn(Placement.carrier(0)));
			caller.tell(new Ping());

			assertEquals(0, ownAfterArm.get(10, TimeUnit.SECONDS));
			waitUntil(() -> TimerProbe.pendingTimers(other) == 1);
		} finally {
			other.shutdown();
			other.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

	/** A mailbox thread on a carrier whose wheel never started binds nothing. */
	@Test
	void aCarrierWithoutTimersBindsNothing() throws Exception {
		CompletableFuture<Boolean> bound = new CompletableFuture<>();
		ActorRef ref = system().spawn("plain",
				() -> (message, sender, context) -> bound.complete(TimerProbe.mailboxThreadBindsItsCarrier()),
				SpawnOptions.placedOn(Placement.carrier(0)));
		ref.tell(new Ping());

		assertFalse(bound.get(10, TimeUnit.SECONDS));
	}

	/**
	 * Starts the wheel of carrier 0 with one timer, and waits until it is sent: an
	 * actor spawned there afterwards binds that wheel in each mailbox thread.
	 */
	private void startWheelOfCarrierZero() throws Exception {
		CompletableFuture<Void> fired = new CompletableFuture<>();
		ActorRef warm = system().spawn("warm", () -> (message, sender, context) -> fired.complete(null),
				SpawnOptions.placedOn(Placement.carrier(0)));
		warm.tell(new Ping(), 1, TimeUnit.MILLISECONDS);
		fired.get(10, TimeUnit.SECONDS);
	}

	private record Ping() implements Message {
	}
}
