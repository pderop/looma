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
package io.github.pderop.looma.scheduler.mpsc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.pderop.looma.ActorSystem;
import io.github.pderop.looma.spi.ActorScheduler;

/**
 * The scheduler follows the JDK: with {@code Thread.Affinity}, actors keep
 * their carrier; without it, their mailbox threads are kept alive instead.
 */
class MpscJdkTest {

	@Test
	void theLookupFindsWhatTheJdkHas() {
		assertEquals(JdkSupport.hasThreadAffinity(), JdkAffinity.SUPPORTED);
	}

	@Test
	void affinityOrKeepAliveFollowsTheJdk() throws InterruptedException {
		ActorSystem system = ActorSystem.builder().build();
		try {
			ActorScheduler scheduler = system.scheduler();
			assertEquals(JdkAffinity.SUPPORTED, scheduler.mailboxThreadsStayOnCarrier());
			assertEquals(!JdkAffinity.SUPPORTED, scheduler.keepMailboxThreadsAlive());
		} finally {
			system.shutdown();
			assertTrue(system.awaitTermination(10, TimeUnit.SECONDS));
		}
	}
}
