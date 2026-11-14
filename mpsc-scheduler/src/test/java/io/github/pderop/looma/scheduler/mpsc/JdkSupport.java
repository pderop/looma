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

/** What the JDK under test offers, for tests that need it. */
public final class JdkSupport {

	private JdkSupport() {
	}

	/**
	 * Whether this JDK has {@code Thread.Affinity}: the first JDK of pull request
	 * 227 has it, the later one does not. Without it, no pool with affinity or
	 * pinning installs.
	 */
	public static boolean hasThreadAffinity() {
		try {
			Class.forName("java.lang.Thread$Affinity");
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}
}
