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
 * The {@link java.util.ServiceLoader} contract through which an
 * {@link io.github.pderop.looma.ActorSystem} reaches a carrier-affine scheduler
 * without {@code core} ever depending on one at compile time, and the
 * mailbox-queue factory a caller can plug in on the builder.
 *
 * <p>
 * {@link io.github.pderop.looma.spi.ActorSchedulerProvider} is what
 * {@code ServiceLoader} discovers on the classpath;
 * {@link io.github.pderop.looma.spi.ActorScheduler} is the small placement view
 * of the scheduler it hands back. {@code core} declares both interfaces and
 * ships no implementation of either — the one implementation in this project
 * lives in the {@code netty-loom-scheduler} module, registered under
 * {@code META-INF/services/io.github.pderop.looma.spi.ActorSchedulerProvider}.
 * When none is found, or the JVM it is found on has not actually installed the
 * scheduler it wraps, {@code core}'s own JDK builtin scheduler is used instead.
 *
 * <p>
 * {@link io.github.pderop.looma.spi.MailboxQueue} /
 * {@link io.github.pderop.looma.spi.MailboxQueueFactory} are <em>not</em>
 * ServiceLoader-discovered: the default is always a
 * {@code ConcurrentLinkedQueue}, and a replacement is set with
 * {@code ActorSystem.Builder#mailboxQueueFactory}.
 */
package io.github.pderop.looma.spi;
