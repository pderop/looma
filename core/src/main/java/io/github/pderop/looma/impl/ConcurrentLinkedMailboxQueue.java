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
package io.github.pderop.looma.impl;

import java.util.concurrent.ConcurrentLinkedQueue;

import io.github.pderop.looma.spi.MailboxQueue;
import io.github.pderop.looma.spi.MailboxQueueFactory;

/**
 * The default {@link MailboxQueue}: a {@link ConcurrentLinkedQueue}. Unbounded,
 * lock-free, MPMC (stronger than the MPSC the engine needs). One instance per
 * actor, created by {@link #factory()}.
 */
final class ConcurrentLinkedMailboxQueue<E> implements MailboxQueue<E> {

	private final ConcurrentLinkedQueue<E> queue = new ConcurrentLinkedQueue<>();

	@Override
	public boolean offer(E item) {
		return queue.offer(item);
	}

	@Override
	public E poll() {
		return queue.poll();
	}

	@Override
	public boolean isEmpty() {
		return queue.isEmpty();
	}

	/**
	 * The factory {@code ActorSystem.Builder} uses when the caller does not plug
	 * one in.
	 */
	static MailboxQueueFactory factory() {
		return ConcurrentLinkedMailboxQueue::new;
	}
}
