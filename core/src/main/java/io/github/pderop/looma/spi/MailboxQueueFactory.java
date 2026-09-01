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
package io.github.pderop.looma.spi;

/**
 * Creates one {@link MailboxQueue} per actor, at spawn.
 *
 * <p>
 * Not discovered through {@link java.util.ServiceLoader}: unlike
 * {@link ActorSchedulerProvider} there is always a working default
 * ({@code ConcurrentLinkedQueue}), and two factories on the classpath would be
 * ambiguous. Plug one in with
 * {@code ActorSystem.Builder#mailboxQueueFactory(MailboxQueueFactory)}. Each
 * {@code create()} must return a <em>fresh</em> queue — actors do not share
 * mailboxes.
 *
 * <p>
 * The returned queue must satisfy {@link MailboxQueue}'s MPSC, non-blocking
 * contract. A later JCTools or carrier-MPSC implementation is a different
 * factory, not a change to the engine.
 */
@FunctionalInterface
public interface MailboxQueueFactory {

	/**
	 * Returns a new empty queue for one actor.
	 */
	<E> MailboxQueue<E> create();
}
