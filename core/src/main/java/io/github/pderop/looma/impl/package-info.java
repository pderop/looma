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
 * The one {@link io.github.pderop.looma.ActorSystem} implementation and the
 * actor engine it drives: the mailbox, one virtual thread per actor, the
 * hierarchy, supervision, {@code ask}, and the graceful cascading stop — all
 * implemented exactly once, and parameterised only by the small
 * {@code ActorDispatcher} that {@code DefaultActorSystem} wraps an
 * {@link io.github.pderop.looma.spi.ActorScheduler} into.
 *
 * <p>
 * {@code DefaultActorSystem} contributes placement and nothing else: which
 * carrier a root actor lands on by default (round-robin), and that a child
 * inherits its parent's by default too — either overridable per spawn through
 * {@link io.github.pderop.looma.SpawnOptions#placedOn(io.github.pderop.looma.Placement)}.
 * {@code ActorRuntime} owns the state one system shares across every actor it
 * hosts (the path registry, the in-flight work counter, the shutdown signal,
 * the mailbox-queue factory); {@code ActorCell} is one actor's own state and
 * implements {@code ActorContext} directly; {@code ActorRefImpl} is the
 * {@code ActorRef} handed out for a live cell; {@code Envelope} is one mailbox
 * entry, either a message or a lifecycle step; {@code ActorSchedulers} is the
 * {@code ServiceLoader} lookup that decides which scheduler a process gets.
 *
 * <p>
 * <b>One class here is {@code public}, and only just.</b> Everything else is
 * package-private, which is what makes "the engine is not API" a compiler
 * constraint rather than a naming convention — this project has no
 * {@code module-info.java}, so a type that had to be reachable from a sibling
 * package would have to be {@code public} to the world.
 * {@code DefaultActorSystem} itself is public solely so that
 * {@link io.github.pderop.looma.ActorSystem#builder()}, one package up, can
 * reach its factory; callers go through that contract and never name it.
 *
 * <p>
 * One implementation of {@link io.github.pderop.looma.spi.ActorScheduler} sits
 * here, package-private and reached only from {@code DefaultActorSystem}'s own
 * {@code Builder} when discovery finds nothing: {@code JdkActorScheduler},
 * whose factories create plain virtual threads on the JDK's default scheduler.
 * The carrier-affine implementation lives in {@code netty-loom-scheduler} and
 * is the only one reached through {@code ServiceLoader}, never by name.
 */
package io.github.pderop.looma.impl;
