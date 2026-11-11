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

import java.net.InetSocketAddress;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;
import io.github.pderop.looma.SpawnOptions;
import io.github.pderop.looma.examples.utils.ConnectionPool;

/**
 * One member of the container pool. It routes a request to the servlet child
 * for its path, and sends the servlet's answer back to the client.
 *
 * <p>
 * The two servlets and the fraud detector are children. With affinity, they
 * stay on this container's carrier. A session id always comes to the same
 * member, and so to the same servlets and detector.
 *
 * <p>
 * The container opens {@code connections} backend connections. Its servlets and
 * its detector share them.
 */
final class ServletContainerActor implements Actor {

	private final InetSocketAddress backend;

	private final int connections;

	private final FraudDetectorActor.Settings fraudSettings;

	private final Startup startup;

	private ActorRef profile;

	private ActorRef cart;

	private ActorRef fraud;

	private ConnectionPool<BackendClient> pool;

	ServletContainerActor(InetSocketAddress backend, int connections, FraudDetectorActor.Settings fraudSettings,
			Startup startup) {
		this.startup = startup;
		this.backend = backend;
		this.connections = connections;
		this.fraudSettings = fraudSettings;
	}

	/**
	 * Opens the connections, starts the servlets and tells {@link Startup}. A
	 * failure stops this actor, and the server does not start.
	 */
	@Override
	public void preStart(ActorContext context) throws Exception {
		// The open blocks this actor until the pool is ready, but the sockets are
		// touched only by virtual threads.
		try {
			pool = ConnectionPool.open(() -> BackendClient.connect(backend), connections, context.vThreadFactory());
			// No placement: with affinity, a child inherits this container's carrier.
			fraud = context.spawn("fraud", () -> new FraudDetectorActor(pool, fraudSettings), SpawnOptions.defaults());
			profile = context.spawn("profile", () -> new ServletActor(new ProfileServlet(), pool, fraud),
					SpawnOptions.defaults());
			cart = context.spawn("cart", () -> new ServletActor(new CartServlet(), pool, fraud),
					SpawnOptions.defaults());
		} catch (Exception e) {
			startup.failed(e);
			throw e;
		}
		startup.ready();
	}

	@Override
	public void postStop(ActorContext context) {
		if (pool != null) {
			pool.close();
		}
	}

	@Override
	public void onReceive(Message message, ActorRef sender, ActorContext context) {
		switch (message) {
			case HttpRequest request -> dispatch(request, sender, context);
			case Messages.ServletResponse response -> response.replyTo().tell(response.response(), context.self());
			// The detector answers the sender directly.
			case Messages.FraudSnapshot snapshot -> fraud.tell(snapshot, sender);
			default -> throw new IllegalStateException("servlet container received unexpected message: " + message);
		}
	}

	/**
	 * Sends the request to the servlet of its path. {@code client} gets the answer.
	 */
	private void dispatch(HttpRequest request, ActorRef client, ActorContext context) {
		ActorRef servlet;
		String method;
		if (request.hasRoute("/profile")) {
			servlet = profile;
			method = "GET";
		} else if (request.hasRoute("/cart")) {
			servlet = cart;
			method = "POST";
		} else {
			client.tell(HttpResponse.of(404, "not found"), context.self());
			return;
		}
		if (!method.equals(request.method())) {
			client.tell(HttpResponse.of(405, "method not allowed"), context.self());
			return;
		}
		servlet.tell(new Messages.ServletRequest(request, client), context.self());
	}
}
