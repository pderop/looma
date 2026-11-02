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
 * A queue with many producers and one consumer. The engine makes one for each
 * actor mailbox: {@code tell} from any thread offers, and that actor's mailbox
 * thread polls, one at a time. It also makes the queues of the timers, where
 * any thread offers and one timer thread polls.
 *
 * <p>
 * Implementations must be at least MPSC, unbounded, and must never block in
 * {@link #offer} or {@link #poll}. On an empty queue the mailbox thread ends,
 * and the next {@code tell} starts a new one; a blocking {@code take()} here
 * would hold a virtual thread that should have ended. Size, iterators and
 * draining helpers are deliberately absent — the engine does not need them.
 *
 * <p>
 * {@link #offer} must publish as a volatile write does, and {@link #isEmpty}
 * must read as a volatile read does. A producer offers, then reads a volatile
 * flag; the consumer sets that flag, then calls {@code isEmpty}: one of the two
 * must see the other, or the timer thread could sleep with a timer waiting. An
 * offer with only a lazy (release) store is not enough. {@code
 * ConcurrentLinkedQueue} is enough.
 *
 * @param <E>
 *            the element type; the engine uses this for its internal envelope
 *            type, which is not part of this interface
 */
public interface MpscQueue<E> {

	/**
	 * Enqueues {@code item}. Always accepts it: the queue has no limit. Never
	 * blocks. Must be safe for concurrent callers.
	 */
	void offer(E item);

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
