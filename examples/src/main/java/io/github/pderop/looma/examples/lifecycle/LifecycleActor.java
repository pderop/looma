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
package io.github.pderop.looma.examples.lifecycle;

import java.util.Optional;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Directive;
import io.github.pderop.looma.Message;
import io.github.pderop.looma.SupervisionStrategy;

/**
 * Demonstrates lifecycle hooks: preStart, postStop, preRestart and postRestart.
 * Sending a Crash message forces an exception and the actor's
 * supervisorStrategy restarts it; logs show the hooks being invoked.
 */
public class LifecycleActor implements Actor {

	private int counter = 0;

	@Override
	public void preStart(ActorContext context) throws Exception {
		System.out.println("[" + context.self().path() + "] preStart, counter=" + counter);
	}

	@Override
	public void postStop(ActorContext context) throws Exception {
		System.out.println("[" + context.self().path() + "] postStop");
	}

	@Override
	public void preRestart(Throwable cause, Optional<Message> message, ActorContext context) throws Exception {
		System.out.println("[" + context.self().path() + "] preRestart cause=" + cause + " message=" + message);
		Actor.super.preRestart(cause, message, context);
	}

	@Override
	public void postRestart(Throwable cause, ActorContext context) throws Exception {
		System.out.println("[" + context.self().path() + "] postRestart cause=" + cause);
		Actor.super.postRestart(cause, context);
	}

	@Override
	public void onReceive(Message message, ActorRef sender, ActorContext context) throws Exception {
		switch (message) {
			case Increment i -> {
				counter++;
				System.out.println("[" + context.self().path() + "] increment => " + counter);
			}

			case GetState gs -> {
				sender.tell(new State(counter), context.self());
			}

			case Crash c -> {
				System.out.println("[" + context.self().path() + "] crashing now (cause: " + c.reason() + ")");
				throw new RuntimeException("simulated crash: " + c.reason());
			}

			default -> throw new IllegalStateException("Unexpected message: " + message);
		}
	}

	@Override
	public SupervisionStrategy supervisorStrategy() {
		return cause -> Directive.RESTART;
	}
}
