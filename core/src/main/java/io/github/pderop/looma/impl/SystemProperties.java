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

/**
 * Sets the JDK's scheduler properties on behalf of
 * {@code ActorSystem.Builder.carriers(...)}.
 *
 * <p>
 * These properties are read once per JVM, so a value that differs from one
 * already set is not an update but a second configuration, and only one of the
 * two can be in force: the JDK, or a {@code -D} on the command line, or an
 * earlier {@code ActorSystem}, got there first. It is refused, naming both.
 */
final class SystemProperties {

	private SystemProperties() {
	}

	/**
	 * Sets {@code key} to {@code value}, or leaves it alone if it already holds
	 * {@code value}.
	 *
	 * @throws IllegalStateException
	 *             if {@code key} already holds a different value
	 */
	static void setOnce(String key, String value) {
		String current = System.getProperty(key);
		if (current == null) {
			System.setProperty(key, value);
		} else if (!current.equals(value)) {
			throw new IllegalStateException("-D" + key + "=" + current + " is already set, and this JVM reads it once:"
					+ " cannot also set it to " + value);
		}
	}
}
