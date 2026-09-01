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
package io.github.pderop.looma.examples.hello;

import io.github.pderop.looma.Actor;
import io.github.pderop.looma.ActorContext;
import io.github.pderop.looma.ActorRef;
import io.github.pderop.looma.Message;

/**
 * A child of {@link MyActor} that simply prints every request it is forwarded.
 *
 * <p>
 * Its only job in this example is to show the {@code carrier-N} suffix of the
 * thread it runs on: it is the same carrier as its parent's, because a child
 * spawned through {@link ActorContext#spawn} inherits its parent's home
 * carrier.
 */
public class AuditLogActor implements Actor {

	@Override
	public void onReceive(Message message, ActorRef sender, ActorContext context) {
		System.out.println("[" + context.self().path() + "] audited " + message + " from " + sender.path() + " on "
				+ Thread.currentThread());
	}
}
