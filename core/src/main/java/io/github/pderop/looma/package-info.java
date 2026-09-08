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

/**
 * Looma's actor API: a small actor runtime for Java virtual threads, built so
 * that an actor and everything it does can stay on one carrier thread — and so
 * on one CPU core, with its state hot in that core's private caches.
 *
 * <h2>The types</h2>
 *
 * <ul>
 * <li>{@link io.github.pderop.looma.ActorSystem} — the root of a hierarchy:
 * spawns root actors, resolves paths, shuts everything down. Reached only
 * through {@link io.github.pderop.looma.ActorSystem#builder()}.
 * <li>{@link io.github.pderop.looma.Actor} — the POJO you write: lifecycle
 * hooks plus {@code onReceive}, never called concurrently for the same
 * instance, so its fields need no synchronization.
 * <li>{@link io.github.pderop.looma.ActorRef} — the opaque, thread-safe handle
 * to an actor: {@code tell} (fire and forget) and {@code ask} (a future of the
 * reply, typed by the reply class the caller passes). A pool spawn hands back
 * one of these standing for several actors, load-balanced round-robin.
 * <li>{@link io.github.pderop.looma.ActorContext} — an actor's own view of
 * itself, its children, and the system; also where blocking work is offloaded,
 * through {@link io.github.pderop.looma.ActorContext#vThreadFactory()}.
 * <li>{@link io.github.pderop.looma.Message} — the marker interface every
 * payload implements.
 * <li>{@link io.github.pderop.looma.SupervisionStrategy} and
 * {@link io.github.pderop.looma.Directive} — what happens to an actor whose
 * {@code onReceive} threw: resume, restart, or stop.
 * <li>{@link io.github.pderop.looma.Placement} and
 * {@link io.github.pderop.looma.SpawnOptions} — the optional home-carrier
 * placement, supervision strategy, and pool size a {@code spawn} can carry.
 * </ul>
 *
 * <h2>A complete program</h2>
 *
 * <pre>{@code
 * record Greet(String who) implements Message {
 * }
 * record Greeting(String text) implements Message {
 * }
 *
 * class Greeter implements Actor {
 * 	public void onReceive(Message message, ActorRef sender, ActorContext context) {
 * 		if (message instanceof Greet greet && sender != null) {
 * 			sender.tell(new Greeting("hello " + greet.who()), context.self());
 * 		}
 * 	}
 * }
 *
 * ActorSystem system = ActorSystem.builder().build();
 * ActorRef greeter = system.spawn("greeter", Greeter::new);
 *
 * Greeting reply = greeter.ask(new Greet("Pierre"), Greeting.class).orTimeout(1, TimeUnit.SECONDS).join();
 *
 * system.shutdown();
 * system.awaitTermination(5, TimeUnit.SECONDS);
 * }</pre>
 *
 * <h2>Where the carrier affinity comes from</h2>
 *
 * Application code never names a scheduler:
 * {@code ActorSystem.builder().build()} takes the
 * {@link io.github.pderop.looma.spi.ActorScheduler} that {@code ServiceLoader}
 * discovers, and otherwise the JDK builtin one. Which of the two a process gets
 * is a deployment decision — JVM flags and classpath — not a code one, which is
 * exactly what makes the identical workload runnable and comparable under both.
 *
 * @see io.github.pderop.looma.spi
 */
package io.github.pderop.looma;
