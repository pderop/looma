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
import io.github.pderop.looma.Carriers;

/**
 * Asks a single actor a few questions: they are handled one at a time, in
 * order, always on that actor's home carrier thread — as is its child, and as
 * are the virtual threads it offloads its blocking work to (see the
 * {@code carrier-N} suffix printed by {@link MyActor} and
 * {@link AuditLogActor}).
 *
 * <p>
 * Asks for two carriers through {@link ActorSystem.Builder#carriers}, so the
 * only JVM flag it needs is {@code --enable-preview} — run it with
 * {@code scripts/run-hello.sh}. Without the flag, or on a stock JDK, there is
 * no carrier-affine scheduler to install and {@code build()} says so.
 */
public class Main {

	public static void main(String[] args) throws InterruptedException {
		ActorSystem actorSystem = ActorSystem.builder().carriers(Carriers.affine(2)).build();

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
