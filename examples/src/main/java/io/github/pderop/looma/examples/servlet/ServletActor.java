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

import java.util.Arrays;
import java.util.Random;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;
import io.github.pderop.looma.examples.utils.ConnectionPool;

/**
 * The actor of a servlet: {@link CartServlet} or {@link ProfileServlet}. A
 * request goes like this:
 *
 * <pre>
 * 1. ServletRequest from the container
 *      the servlet updates the session (or the client gets a 400)
 *      FraudCheck to the fraud detector of the container
 * 2. FraudVerdict from the detector
 *      calls the backend
 * 3. BackendAnswered (or BackendTimeout)
 *      the servlet writes the page
 *      ServletResponse to the container
 * </pre>
 *
 * <p>
 * A failed fraud check never comes back here: the detector answers the
 * container itself. A failed backend call answers a 502, a late one a 504.
 *
 * <p>
 * The sizes are drawn from a generator seeded with 1, so a run repeats.
 */
final class ServletActor implements Actor {

	/** The body asked of the backend: from this many bytes... */
	static final int BACKEND_MIN = 256;

	/** ...to this many. */
	static final int BACKEND_MAX = 16 * 1024;

	/** The page written for the client: from this many bytes... */
	private static final int CLIENT_MIN = 512;

	/** ...to this many. */
	private static final int CLIENT_MAX = 32 * 1024;

	private final Servlet servlet;

	private final ActorRef fraud;

	private final BackendCalls<Messages.FraudVerdict> calls;

	/**
	 * Seeded, so the sizes of a run are the same from one execution to the next.
	 */
	private final Random random = new Random(1);

	/** The text of a page, written by the servlet. Reused by every request. */
	private final StringBuilder text = new StringBuilder(1024);

	/**
	 * The output buffer: each page is written in it, as a server writes a page in
	 * its buffer before the socket. Reused by every request.
	 */
	private final byte[] out = new byte[CLIENT_MAX];

	ServletActor(Servlet servlet, ConnectionPool<BackendClient> pool, ActorRef fraud) {
		this.servlet = servlet;
		this.fraud = fraud;
		this.calls = new BackendCalls<>(pool, BACKEND_MAX);
	}

	@Override
	public void postStop(ActorContext context) {
		calls.cancelAll();
	}

	@Override
	public void onReceive(Message message, ActorRef sender, ActorContext context) {
		switch (message) {
			case Messages.ServletRequest request -> request(request, sender, context);
			case Messages.FraudVerdict verdict -> {
				int bytes = BACKEND_MIN + random.nextInt(BACKEND_MAX - BACKEND_MIN + 1);
				int pad = random.nextInt(BackendCalls.PAD_MAX + 1);
				calls.call(verdict, bytes, pad, context);
			}
			case Messages.BackendAnswered answered -> {
				Messages.FraudVerdict verdict = calls.answered(answered);
				if (verdict == null) {
					return; // its timer came first: the client already has a 504
				}
				int checksum = calls.checksum(answered);
				HttpResponse response = checksum < 0
						? HttpResponse.of(502, "backend failed")
						: page(verdict, checksum);
				reply(verdict, response, context);
			}
			case Messages.BackendTimeout timeout -> {
				Messages.FraudVerdict verdict = calls.timedOut(timeout);
				if (verdict != null) {
					reply(verdict, HttpResponse.of(504, "backend timeout"), context);
				}
			}
			default -> throw new IllegalStateException("servlet received unexpected message: " + message);
		}
	}

	/**
	 * The servlet updates the session, then the fraud detector is asked for a risk.
	 * A wrong request answers 400 at once.
	 */
	private void request(Messages.ServletRequest request, ActorRef container, ActorContext context) {
		HttpRequest http = request.request();
		int product = servlet.product(http);
		int quantity = servlet.quantity(http);
		String wrong = servlet.update(http, product, quantity);
		if (wrong != null) {
			container.tell(new Messages.ServletResponse(HttpResponse.of(400, wrong), request.replyTo()),
					context.self());
			return;
		}
		fraud.tell(new Messages.FraudCheck(request, container, product, quantity), context.self());
	}

	/**
	 * The 200 response. The servlet writes its text, and the page, padded to a size
	 * drawn for it, goes into the output buffer, as a server writes it before its
	 * socket. The padding stays there. The client gets a copy of the text: one
	 * allocation per response.
	 */
	private HttpResponse page(Messages.FraudVerdict verdict, int checksum) {
		text.setLength(0);
		servlet.page(verdict.request().request(), checksum, verdict.risk(), text);
		int size = CLIENT_MIN + random.nextInt(CLIENT_MAX - CLIENT_MIN + 1);
		int length = Math.min(text.length(), out.length);
		for (int i = 0; i < length; i++) {
			out[i] = (byte) text.charAt(i);
		}
		Arrays.fill(out, length, Math.max(length, size), (byte) 'x');
		return new HttpResponse(200, Arrays.copyOf(out, length));
	}

	/** Gives the answer to the container, which sends it to the client. */
	private void reply(Messages.FraudVerdict verdict, HttpResponse response, ActorContext context) {
		verdict.container().tell(new Messages.ServletResponse(response, verdict.request().replyTo()), context.self());
	}
}
