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

import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;

/**
 * The messages between the container, its servlets and its fraud detector. The
 * client's reply address travels with the request, so the container keeps no
 * table of what is in flight. The client sends an {@link HttpRequest}, and gets
 * an {@link HttpResponse} back.
 */
final class Messages {

	private Messages() {
	}

	/** A request the container hands to a servlet. */
	record ServletRequest(HttpRequest request, ActorRef replyTo) implements Message {
	}

	/**
	 * The backend answered one call, sent by the call's virtual thread. The body is
	 * in the buffer of the call: {@code length} bytes, or -1 for a failed call.
	 */
	record BackendAnswered(BackendCalls.Call<?> call, int length) implements Message {
	}

	/**
	 * A call had no answer in time. The servlet arms it on itself when it calls the
	 * backend, and cancels it when the answer comes.
	 */
	record BackendTimeout(BackendCalls.Call<?> call) implements Message {
	}

	/** A servlet's answer, which the container forwards to the client. */
	record ServletResponse(HttpResponse response, ActorRef replyTo) implements Message {
	}

	/**
	 * A servlet asks its fraud detector about a request. {@code quantity} is 0 for
	 * a view. The request and its container travel with it, and come back in the
	 * verdict.
	 */
	record FraudCheck(ServletRequest request, ActorRef container, int product, int quantity) implements Message {
	}

	/** The detector's answer: the risk of the request, from 0 to 100. */
	record FraudVerdict(ServletRequest request, ActorRef container, int risk) implements Message {
	}

	/**
	 * Asks a container for the counts of its fraud detector. With {@code reset},
	 * the counts start again from zero.
	 */
	record FraudSnapshot(boolean reset) implements Message {
	}

	/**
	 * The counts of a fraud detector: its checks, the messages it sampled, and how
	 * many of those ran on another carrier than the message before.
	 */
	record FraudStats(long checks, long traced, long migrated) implements Message {

		FraudStats plus(FraudStats other) {
			return new FraudStats(checks + other.checks, traced + other.traced, migrated + other.migrated);
		}
	}
}
