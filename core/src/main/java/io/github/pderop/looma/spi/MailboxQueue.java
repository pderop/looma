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
 * One actor's envelope queue: many producers ({@code tell} from any thread),
 * one consumer (that actor's virtual-thread loop).
 *
 * <p>
 * Implementations must be at least MPSC and must never block in {@link #offer}
 * or {@link #poll}. The actor loop parks itself on an empty queue via
 * {@code LockSupport}; a blocking {@code take()} here would pin a carrier or
 * duplicate that park. Size, iterators and draining helpers are deliberately
 * absent — the engine does not need them.
 *
 * @param <E>
 *            the element type; the engine uses this for its internal envelope
 *            type, which is not part of this interface
 */
public interface MailboxQueue<E> {

	/**
	 * Enqueues {@code item}. Never blocks. Must be safe for concurrent callers.
	 *
	 * @return {@code true} if {@code item} was accepted; {@code false} only if this
	 *         implementation is bounded and full — the engine treats that like any
	 *         other dropped tell
	 */
	boolean offer(E item);

	/**
	 * Removes and returns the head, or {@code null} if empty. Never blocks.
	 */
	E poll();

	/**
	 * Returns whether there is currently nothing to {@link #poll}. Used by the stop
	 * cascade to decide the mailbox has drained; may race a concurrent
	 * {@link #offer}, exactly as {@code ConcurrentLinkedQueue#isEmpty} does.
	 */
	boolean isEmpty();
}
