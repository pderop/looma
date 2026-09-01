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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.ActorSystem;

/**
 * Asks a single actor a few questions: they are handled one at a time, in
 * order, always on that actor's home carrier thread — as is its child, and as
 * are the virtual threads it offloads its blocking work to (see the
 * {@code carrier-N} suffix printed by {@link MyActor} and
 * {@link AuditLogActor}).
 *
 * <p>
 * Run with {@code scripts/run-hello.sh} (see it for the JVM args this needs —
 * {@code --enable-preview} and
 * {@code -Djdk.virtualThreadScheduler.implClass=io.netty.loom.scheduler.NettyScheduler}
 * at minimum). Without them the actor system still runs, on the JDK builtin
 * scheduler, and the {@code carrier-N} suffixes below simply stop varying.
 */
public class Main {

	public static void main(String[] args) throws InterruptedException {
		ActorSystem actorSystem = ActorSystem.builder().build();

		ActorRef hello = actorSystem.spawn("hello", MyActor::new);

		List<CompletableFuture<Greeting>> replies = List.of("Pierre", "John", "Bob").stream()
				.map(name -> hello.ask(new Hello(name), Greeting.class).orTimeout(5, TimeUnit.SECONDS)).toList();

		replies.forEach(reply -> reply.thenAccept(System.out::println));

		/*
		 * Wait for every reply before shutting down. Shutdown is a graceful, draining
		 * cascade -- anything already in a mailbox would still be delivered -- but an
		 * ask issued *after* it starts is rejected outright, and we want all three
		 * greetings printed before exit either way.
		 */
		CompletableFuture.allOf(replies.toArray(CompletableFuture[]::new)).join();

		actorSystem.shutdown();
		actorSystem.awaitTermination(5, TimeUnit.SECONDS);
	}
}
