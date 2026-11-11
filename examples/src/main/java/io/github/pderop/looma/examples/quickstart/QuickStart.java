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
package io.github.pderop.looma.examples.quickstart;

import java.util.concurrent.TimeUnit;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.Carriers;
import io.github.pderop.looma.Message;

/**
 * The quick start of the README: one actor, a {@code tell}, an {@code ask} and
 * a delayed {@code tell}. It runs on a stock JDK 25, on the JDK's scheduler.
 * With the argument {@code mpsc}, it runs on two MPSC carriers instead: this
 * needs the Loom JDK of the README.
 */
public class QuickStart {

	record Greet(String who) implements Message {
	}

	record Greeting(String text) implements Message {
	}

	static class Greeter implements Actor {

		private int greeted; // no lock: the actor runs one message at a time

		@Override
		public void onReceive(Message message, ActorRef sender, ActorContext context) {
			if (message instanceof Greet greet) {
				greeted++;
				System.out.println("greeting " + greet.who() + " (#" + greeted + ") on " + Thread.currentThread());
				if (sender != null) {
					sender.tell(new Greeting("hello " + greet.who()), context.self());
				}
			}
		}
	}

	public static void main(String[] args) throws Exception {
		ActorSystem.Builder builder = ActorSystem.builder();
		if (args.length > 0 && args[0].equals("mpsc")) {
			builder.carriers(Carriers.mpsc(2)); // carrier affinity: needs the Loom JDK
		}
		ActorSystem system = builder.build();
		ActorRef greeter = system.spawn("greeter", Greeter::new);

		greeter.tell(new Greet("Ann")); // fire and forget
		Greeting reply = greeter.ask(new Greet("Bob"), Greeting.class) // request and reply
				.orTimeout(1, TimeUnit.SECONDS)
				.join();
		System.out.println(reply.text());
		greeter.tell(new Greet("Cid"), 100, TimeUnit.MILLISECONDS); // in 100 ms

		Thread.sleep(300);
		system.shutdown();
		System.out.println("terminated: " + system.awaitTermination(5, TimeUnit.SECONDS));
	}
}
