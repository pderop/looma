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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;

/** The future side of a {@link Timer}. */
class TimerFutureTest {

	private static final ActorRef TARGET = new ActorRef() {
		@Override
		public String name() {
			return "target";
		}

		@Override
		public String path() {
			return "/target";
		}

		@Override
		public void tell(Message message, ActorRef sender) {
		}

		@Override
		public <R extends Message> CompletableFuture<R> ask(Message message, Class<R> replyType) {
			throw new UnsupportedOperationException();
		}
	};
	private static final Message MESSAGE = new Message() {
	};

	private static Timer scheduled(long periodNanos) {
		Timer timer = new Timer(TARGET, MESSAGE, null, System.nanoTime() + 1_000_000_000L, periodNanos);
		assertTrue(timer.markScheduled());
		return timer;
	}

	@Test
	void cancelWithoutGetDoesNotNeedAnException() {
		for (int i = 0; i < 1000; i++) {
			Timer timer = scheduled(0);
			assertTrue(timer.cancel(false));
			assertTrue(timer.isCancelled());
			assertTrue(timer.isDone());
		}
	}

	@Test
	void getAfterCancelThrowsAFreshExceptionEachTime() {
		Timer timer = scheduled(0);
		timer.cancel(false);
		CancellationException first = assertThrows(CancellationException.class, timer::get);
		CancellationException second = assertThrows(CancellationException.class, () -> timer.get(1, TimeUnit.SECONDS));
		assertNotSame(first, second);
	}

	@Test
	void getBlocksUntilTheSendThenReturns() throws Exception {
		Timer timer = scheduled(0);
		CompletableFuture<Void> waiting = CompletableFuture.supplyAsync(() -> {
			try {
				return timer.get();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		assertThrows(TimeoutException.class, () -> waiting.get(200, TimeUnit.MILLISECONDS));
		assertTrue(timer.markSent());
		assertTrue(timer.isDone());
		assertFalse(timer.cancel(false));
		assertThrows(TimeoutException.class, () -> timer.get(100, TimeUnit.MILLISECONDS));
		timer.completeSent();
		assertNull(waiting.get(5, TimeUnit.SECONDS));
		assertTrue(timer.isDone());
		assertNull(timer.get());
	}

	@Test
	void getOnInterruptedThreadThrowsWhileNotDone() {
		Timer timer = scheduled(0);
		Thread.currentThread().interrupt();
		try {
			assertThrows(InterruptedException.class, timer::get);
		} finally {
			Thread.interrupted();
		}
		Thread.currentThread().interrupt();
		try {
			assertThrows(InterruptedException.class, () -> timer.get(5, TimeUnit.SECONDS));
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void getOnInterruptedThreadReturnsOnceDone() throws Exception {
		Timer sent = scheduled(0);
		assertTrue(sent.markSent());
		sent.completeSent();
		Timer cancelled = scheduled(0);
		cancelled.cancel(false);
		Thread.currentThread().interrupt();
		try {
			assertNull(sent.get());
			assertNull(sent.get(1, TimeUnit.SECONDS));
			assertThrows(CancellationException.class, cancelled::get);
			assertThrows(CancellationException.class, () -> cancelled.get(1, TimeUnit.SECONDS));
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void manyGettersRaceTheEnd() throws Exception {
		for (int round = 0; round < 200; round++) {
			Timer timer = scheduled(0);
			boolean send = round % 2 == 0;
			int getters = 8;
			java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
			java.util.List<CompletableFuture<Object>> results = new java.util.ArrayList<>();
			for (int i = 0; i < getters; i++) {
				results.add(CompletableFuture.supplyAsync(() -> {
					try {
						go.await();
						return timer.get(10, TimeUnit.SECONDS);
					} catch (Exception e) {
						return e;
					}
				}));
			}
			go.countDown();
			if (send) {
				assertTrue(timer.markSent());
				timer.completeSent();
			} else {
				assertTrue(timer.cancel(false));
			}
			for (CompletableFuture<Object> result : results) {
				Object value = result.get(5, TimeUnit.SECONDS);
				if (send) {
					assertNull(value);
				} else {
					assertTrue(value instanceof CancellationException, String.valueOf(value));
				}
			}
		}
	}

	@Test
	void getBlocksUntilCancelThenThrows() throws Exception {
		Timer timer = scheduled(0);
		CompletableFuture<Object> waiting = CompletableFuture.supplyAsync(() -> {
			try {
				return timer.get();
			} catch (Exception e) {
				return e;
			}
		});
		assertThrows(TimeoutException.class, () -> waiting.get(200, TimeUnit.MILLISECONDS));
		timer.cancel(false);
		assertTrue(waiting.get(5, TimeUnit.SECONDS) instanceof CancellationException);
	}

	@Test
	void getWithTimeoutTimesOutOnAPeriodicTimer() {
		Timer timer = scheduled(1_000_000L);
		assertThrows(TimeoutException.class, () -> timer.get(50, TimeUnit.MILLISECONDS));
		assertFalse(timer.isDone());
	}

	@Test
	void cancelAfterSendReturnsFalseAndSecondCancelReturnsTrue() {
		Timer sent = scheduled(0);
		assertTrue(sent.markSent());
		sent.completeSent();
		assertFalse(sent.cancel(false));
		assertFalse(sent.isCancelled());
		Timer cancelled = scheduled(0);
		assertTrue(cancelled.cancel(false));
		assertTrue(cancelled.cancel(false));
		assertFalse(cancelled.markSent());
	}
}
