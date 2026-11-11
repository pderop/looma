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

import java.util.Random;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;
import io.github.pderop.looma.examples.utils.CarrierTracker;
import io.github.pderop.looma.examples.utils.ConnectionPool;

/**
 * The fraud detector of a container. Its two servlets ask it about each
 * request, before they call the backend. It answers with a risk, from 0 to 100.
 *
 * <p>
 * A check goes like this:
 *
 * <pre>
 * 1. FraudCheck from a servlet
 *      score: reads and writes the records of the request's features
 *      calls the backend, for a flag (a small body)
 * 2. BackendAnswered
 *      learns: writes the weights of the same records
 *      FraudVerdict to the servlet
 * </pre>
 *
 * <p>
 * The state is one table of ints, made at start and never resized. A record is
 * four ints: a count, an amount, the check that touched it last, and a weight.
 * A feature is a mix of the session, the product, the quantity and the time,
 * hashed to one record. So a check touches records spread over the whole table,
 * and holds no reference to follow.
 *
 * <p>
 * A failed backend call answers the container at once, with a 502 or a 504. The
 * servlet then hears nothing more about the request.
 */
final class FraudDetectorActor implements Actor {

	/**
	 * The size of the table and the features of a check.
	 *
	 * @param tableKiB
	 *            the size of the table, in KiB: a power of two
	 * @param features
	 *            the records a check touches; 0 for none
	 */
	record Settings(int tableKiB, int features) {

		/** {@code servlet.fraudKiB} (1024) and {@code servlet.fraudFeatures} (256). */
		static Settings fromProperties() {
			return new Settings(Integer.getInteger("servlet.fraudKiB", 1024),
					Integer.getInteger("servlet.fraudFeatures", 256));
		}

		Settings {
			if (tableKiB < 1 || Integer.bitCount(tableKiB) != 1) {
				throw new IllegalArgumentException("servlet.fraudKiB must be a power of two, got " + tableKiB);
			}
			if (features < 0) {
				throw new IllegalArgumentException("servlet.fraudFeatures must not be negative, got " + features);
			}
		}
	}

	/** The body asked of the backend: from this many bytes... */
	static final int BODY_MIN = 256;

	/** ...to this many. */
	static final int BODY_MAX = 1024;

	/** The ints of one record. */
	private static final int RECORD = 4;

	private static final int COUNT = 0;

	private static final int AMOUNT = 1;

	private static final int LAST = 2;

	private static final int WEIGHT = 3;

	/**
	 * A count is halved when its record has not been touched for this many checks.
	 */
	private static final int STALE = 4096;

	/** The checks in one time window, for the features that hold the time. */
	private static final int WINDOW_SHIFT = 10;

	/** The risk of a check, in fixed point: this is 100. */
	private static final int ONE = 1 << 12;

	/** The most a weight can be, either way. */
	private static final int WEIGHT_MAX = 1 << 16;

	/** The records. Only this actor touches them. */
	private final int[] table;

	/** The number of records, minus one. */
	private final int mask;

	private final int features;

	private final CarrierTracker carriers = new CarrierTracker();

	private final BackendCalls<Check> calls;

	/**
	 * Seeded, so the sizes of a run are the same from one execution to the next.
	 */
	private final Random random = new Random(1);

	/** The checks so far. It also dates the records. */
	private int tick;

	/** The checks since the last reset of the counts. */
	private long checks;

	/** One check, from its first message to its verdict. */
	static final class Check {

		final Messages.FraudCheck request;

		final ActorRef servlet;

		final int tick;

		final int score;

		Check(Messages.FraudCheck request, ActorRef servlet, int tick, int score) {
			this.request = request;
			this.servlet = servlet;
			this.tick = tick;
			this.score = score;
		}
	}

	FraudDetectorActor(ConnectionPool<BackendClient> pool, Settings settings) {
		this.calls = new BackendCalls<>(pool, BODY_MAX);
		int records = settings.tableKiB() * 1024 / (RECORD * Integer.BYTES);
		this.table = new int[records * RECORD];
		this.mask = records - 1;
		this.features = settings.features();
	}

	@Override
	public void postStop(ActorContext context) {
		calls.cancelAll();
	}

	@Override
	public void onReceive(Message message, ActorRef sender, ActorContext context) {
		switch (message) {
			case Messages.FraudCheck request -> {
				carriers.resumed();
				check(request, sender, context);
				carriers.parking();
			}
			case Messages.BackendAnswered answered -> {
				carriers.resumed();
				Check check = calls.answered(answered);
				// Null: its timer came first, the container already has a 504.
				if (check != null) {
					int checksum = calls.checksum(answered);
					if (checksum < 0) {
						fail(check, HttpResponse.of(502, "backend failed"), context);
					} else {
						verdict(check, checksum, context);
					}
				}
				carriers.parking();
			}
			case Messages.BackendTimeout timeout -> {
				Check check = calls.timedOut(timeout);
				if (check != null) {
					fail(check, HttpResponse.of(504, "backend timeout"), context);
				}
			}
			case Messages.FraudSnapshot snapshot -> {
				sender.tell(new Messages.FraudStats(checks, carriers.traced(), carriers.migrated()), context.self());
				if (snapshot.reset()) {
					checks = 0;
					carriers.reset();
				}
			}
			default -> throw new IllegalStateException("fraud detector received unexpected message: " + message);
		}
	}

	/**
	 * Scores the request from the table, then asks the backend for a flag.
	 */
	private void check(Messages.FraudCheck request, ActorRef servlet, ActorContext context) {
		int now = ++tick;
		checks++;
		int score = score(request, now);
		int bytes = BODY_MIN + random.nextInt(BODY_MAX - BODY_MIN + 1);
		calls.call(new Check(request, servlet, now, score), bytes, 0, context);
	}

	/**
	 * The backend gave its answer, and so the flag: learns from it, and gives the
	 * risk to the servlet.
	 */
	private void verdict(Check check, int checksum, ActorContext context) {
		// The flag comes from the answer: a few answers in a hundred have it.
		boolean flagged = (checksum & 63) == 0;
		learn(check, flagged);
		int risk = flagged ? 100 : Math.clamp(check.score, 0, ONE) * 100 / ONE;
		Messages.FraudCheck request = check.request;
		check.servlet.tell(new Messages.FraudVerdict(request.request(), request.container(), risk), context.self());
	}

	/**
	 * The backend call failed: the container answers the client, the servlet hears
	 * nothing more.
	 */
	private void fail(Check check, HttpResponse error, ActorContext context) {
		Messages.FraudCheck request = check.request;
		request.container().tell(new Messages.ServletResponse(error, request.request().replyTo()), context.self());
	}

	/**
	 * Updates the records of the request's features, and adds up their weights.
	 */
	private int score(Messages.FraudCheck request, int now) {
		int score = 0;
		for (int i = 0; i < features; i++) {
			int at = record(request, now, i);
			int count = table[at + COUNT];
			if (now - table[at + LAST] > STALE) {
				count >>= 1;
			}
			count++;
			table[at + COUNT] = count;
			table[at + AMOUNT] += request.quantity() - (table[at + AMOUNT] >> 4);
			table[at + LAST] = now;
			score += (table[at + WEIGHT] * Math.min(count, 255)) >> 8;
		}
		return score;
	}

	/**
	 * Moves the weights of the same records toward the flag the backend gave.
	 */
	private void learn(Check check, boolean flagged) {
		int error = (flagged ? ONE : 0) - Math.clamp(check.score, 0, ONE);
		int step = error >> 6;
		for (int i = 0; i < features; i++) {
			int at = record(check.request, check.tick, i);
			table[at + WEIGHT] = Math.clamp((long) table[at + WEIGHT] + step, -WEIGHT_MAX, WEIGHT_MAX);
		}
	}

	/**
	 * The index of the record of feature {@code i}. There are eight kinds of
	 * feature; past eight, the same kinds come back with another hash.
	 */
	private int record(Messages.FraudCheck request, int now, int i) {
		long sid = request.request().request().sid();
		long product = request.product();
		long quantity = request.quantity();
		long window = now >>> WINDOW_SHIFT;
		long value = switch (i & 7) {
			case 0 -> sid;
			case 1 -> product;
			case 2 -> sid * 1_000_003 + product;
			case 3 -> sid * 8 + quantity;
			case 4 -> product * 8 + quantity;
			case 5 -> quantity;
			case 6 -> sid ^ (window << 40);
			default -> product ^ (window << 40);
		};
		long hash = (value + (i + 1) * 0x9E3779B97F4A7C15L) * 0xBF58476D1CE4E5B9L;
		hash ^= hash >>> 31;
		return (int) (hash & mask) * RECORD;
	}
}
