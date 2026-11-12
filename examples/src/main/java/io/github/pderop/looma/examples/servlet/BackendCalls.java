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
package io.github.pderop.looma.examples.servlet;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.examples.utils.FairPool;
import io.github.pderop.looma.examples.utils.ConnectionPool;

/**
 * The backend calls of one actor. Each call runs on a virtual thread from
 * {@link ActorContext#vThreadFactory()}, with a connection of the container's
 * {@link ConnectionPool}. The actor owns this object: only the actor touches
 * it.
 *
 * <p>
 * Each call borrows a buffer of this object for the body. The thread of the
 * call reads the body into it, and the actor parses it there with
 * {@link #checksum}, which takes the buffer back. A request so allocates no
 * buffer for its body.
 *
 * <p>
 * Each call arms a timer on the actor. When the actor gets the answer, it gives
 * it to {@link #answered}, which cancels the timer. If the timer comes first,
 * the actor gives it to {@link #timedOut}, which interrupts the thread. A call
 * still waiting for a connection ends there. A call reading the backend loses
 * its connection, and the pool opens a new one. Both give back the job of the
 * call, or null if the other one came first.
 *
 * @param <J>
 *            what the actor keeps for a call, and gets back when it ends
 */
final class BackendCalls<J> {

	/** How long a call may wait for the backend. */
	static final long TIMEOUT_MILLIS = Long.getLong("servlet.backendTimeoutMillis", 3000);

	/** The padding of a backend request: 0 to this many bytes. */
	static final int PAD_MAX = 2048;

	private final ConnectionPool<BackendClient> pool;

	/** The size of a body buffer: the biggest body a call may ask for. */
	private final int maxBody;

	/**
	 * The body buffers no call holds. A buffer comes back once the actor has parsed
	 * its body, or with a late answer: its thread no longer writes in it.
	 */
	private final ArrayDeque<byte[]> spareBodies = new ArrayDeque<>();

	/**
	 * The calls with no answer yet, as a list through their own fields: adding and
	 * removing one allocates nothing.
	 */
	private Call<J> first;

	/**
	 * One call in flight, and the timer that guards it. It is also the waiter of
	 * the pool and the body of its virtual thread, so a call allocates nothing
	 * more. The messages of the call carry it back to the actor.
	 */
	static final class Call<J> extends FairPool.Waiter<BackendClient> implements Runnable {

		private final J job;

		private final int bytes;

		private final int pad;

		private final ConnectionPool<BackendClient> pool;

		private final ActorRef self;

		/** The buffer the body is read into. The actor gets it back after. */
		private final byte[] body;

		private ScheduledFuture<?> timer;

		private Thread thread;

		/**
		 * Set by the actor when the answer or the timer came: the other one is late.
		 */
		private boolean done;

		private Call<J> previous;

		private Call<J> next;

		private Call(J job, int bytes, int pad, ConnectionPool<BackendClient> pool, ActorRef self, byte[] body) {
			this.job = job;
			this.bytes = bytes;
			this.pad = pad;
			this.pool = pool;
			this.self = self;
			this.body = body;
		}

		/**
		 * The body of the virtual thread: borrows a connection, reads the backend into
		 * the body buffer, and sends the length to the actor. A length of -1 means the
		 * call failed.
		 */
		@Override
		public void run() {
			BackendClient client;
			try {
				client = pool.take(this);
			} catch (InterruptedException e) {
				return; // its timer came first, or the server stops
			}
			int length;
			try {
				length = client.get(bytes, pad, body);
			} catch (IOException | RuntimeException e) {
				length = -1;
				// An interrupt left set would fail the new connection too.
				Thread.interrupted();
				client = pool.replace(client);
			} finally {
				pool.give(client);
			}
			self.tell(new Messages.BackendAnswered(this, length), self);
		}
	}

	/** {@code maxBody}: the biggest body a call of this actor asks for. */
	BackendCalls(ConnectionPool<BackendClient> pool, int maxBody) {
		this.pool = pool;
		this.maxBody = maxBody;
	}

	/**
	 * Starts a call on a virtual thread: it asks the backend for {@code bytes} of
	 * body, with {@code pad} bytes of padding. The actor then gets a
	 * {@link Messages.BackendAnswered} or a {@link Messages.BackendTimeout}.
	 */
	void call(J job, int bytes, int pad, ActorContext context) {
		if (bytes > maxBody) {
			throw new IllegalArgumentException("a body of " + bytes + " bytes, the buffers hold " + maxBody);
		}
		byte[] body = spareBodies.isEmpty() ? new byte[maxBody] : spareBodies.pop();
		Call<J> call = new Call<>(job, bytes, pad, pool, context.self(), body);
		call.timer = context.self().tell(new Messages.BackendTimeout(call), TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
		call.next = first;
		if (first != null) {
			first.previous = call;
		}
		first = call;
		call.thread = context.vThreadFactory().newThread(call);
		call.thread.start();
	}

	/**
	 * The backend answered: cancels the timer, and gives back the job of the call.
	 * Null if the timer came first: the call already failed, and its buffer comes
	 * back now. Else the actor then parses the body with {@link #checksum}.
	 */
	J answered(Messages.BackendAnswered answered) {
		Call<J> call = mine(answered.call());
		if (call.done) {
			spareBodies.push(call.body);
			return null;
		}
		end(call);
		call.timer.cancel(false);
		return call.job;
	}

	/**
	 * The timer of a call came first: interrupts its thread, and gives back the job
	 * of the call. Null if the answer came first: its cancel was too late. The
	 * thread may still write in the buffer: it comes back with the late answer, or
	 * never.
	 */
	J timedOut(Messages.BackendTimeout timeout) {
		Call<J> call = mine(timeout.call());
		if (call.done) {
			return null;
		}
		end(call);
		call.thread.interrupt();
		return call.job;
	}

	/** Cancels the timers of the calls in flight. For the actor's postStop. */
	void cancelAll() {
		while (first != null) {
			first.timer.cancel(false);
			end(first);
		}
	}

	/** A call of this object: the messages carry the calls it started. */
	@SuppressWarnings("unchecked")
	private Call<J> mine(Call<?> call) {
		return (Call<J>) call;
	}

	/** Marks the call as done, and takes it out of the list. */
	private void end(Call<J> call) {
		call.done = true;
		if (call.previous != null) {
			call.previous.next = call.next;
		} else {
			first = call.next;
		}
		if (call.next != null) {
			call.next.previous = call.previous;
		}
		call.previous = null;
		call.next = null;
	}

	/**
	 * Parses the body of an answer that {@link #answered} accepted, and takes its
	 * buffer back. Returns the checksum of the body, or -1 if the call failed.
	 */
	int checksum(Messages.BackendAnswered answered) {
		Call<J> call = mine(answered.call());
		int checksum = answered.length() < 0 ? -1 : checksum(call.body, answered.length());
		spareBodies.push(call.body);
		return checksum;
	}

	/**
	 * The sum of the integer values in the first {@code length} bytes of a
	 * {@code key=value} body. The answers carry it, so the JIT cannot remove the
	 * parse.
	 */
	static int checksum(byte[] body, int length) {
		int sum = 0;
		int value = -1;
		for (int i = 0; i < length; i++) {
			byte b = body[i];
			if (b >= '0' && b <= '9') {
				value = (value < 0 ? 0 : value * 10) + (b - '0');
			} else if (b == '\n') {
				if (value > 0) {
					sum += value;
				}
				value = -1;
			} else if (b != '=') {
				value = -1;
			}
		}
		return sum;
	}
}
