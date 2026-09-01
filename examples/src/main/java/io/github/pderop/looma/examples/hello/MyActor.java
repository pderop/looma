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
package io.github.pderop.looma.examples.hello;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;

/**
 * An actor answering {@link Hello} messages with a {@link Greeting}.
 *
 * <p>
 * Demonstrates the distinctive thing this project exists to show: a
 * <b>blocking</b> call inside {@link #onReceive} moved off the actor's own gate
 * onto a virtual thread from {@link ActorContext#vThreadFactory()}. On this
 * carrier-affine implementation that thread is pinned to the actor's home
 * carrier (see {@code CarrierAffinityTest}), so blocking there parks it without
 * stalling any other actor's dispatch, and replying from it pays no
 * cross-carrier hop.
 *
 * <p>
 * It also spawns one child, in {@link #preStart}, to show the second half of
 * the affinity rule: a child spawned from a context inherits its parent's home
 * carrier, so the {@code tell} below stays on that same carrier too.
 */
public class MyActor implements Actor {

	private ActorRef auditLog;

	@Override
	public void preStart(ActorContext context) {
		/*
		 * The child lands on this actor's own home carrier -- context.spawn never
		 * consults the system's round-robin cursor, only ActorSystem.spawn does.
		 */
		auditLog = context.spawn("audit", AuditLogActor::new);
	}

	@Override
	public void onReceive(Message message, ActorRef sender, ActorContext context) {
		switch (message) {
			case Hello hello -> {
				System.out.println(
						"[" + context.self().path() + "] handling '" + hello.name() + "' on " + Thread.currentThread());

				auditLog.tell(hello, context.self());

				/*
				 * Capture what the offloaded thread needs *before* spawning it: `sender` is a
				 * parameter of this call only, and `self` must not be read from the context
				 * concurrently with the next message being dispatched.
				 */
				ActorRef self = context.self();

				context.vThreadFactory().newThread(() -> {
					System.out.println("[" + self.path() + "] blocking (simulated I/O) for '" + hello.name() + "' on "
							+ Thread.currentThread());

					try {
						/*
						 * Simulates a blocking call (a JDBC query, a blocking HTTP client, ...). It
						 * parks this virtual thread; the carrier keeps running other actors' work in
						 * the meantime, and this actor's own gate was released the moment onReceive
						 * returned.
						 */
						Thread.sleep(50);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}

					System.out.println(
							"[" + self.path() + "] greeting '" + hello.name() + "' on " + Thread.currentThread());

					sender.tell(new Greeting("Greeting " + hello.name()), self);
				}).start();
			}

			default -> throw new IllegalStateException("Hello actor received unexpected event: " + message);
		}
	}
}
