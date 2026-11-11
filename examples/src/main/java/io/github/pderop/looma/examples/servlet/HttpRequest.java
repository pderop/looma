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

import io.github.pderop.looma.Message;

/**
 * One request. The load generator sends it to the container of its session.
 * {@code sid} is the session id: it picks the container. {@code path} may end
 * with a query, as in {@code /profile?viewed=12}.
 */
public record HttpRequest(String method, String path, long sid, byte[] body) implements Message {

	/** The value of a parameter that is not there. */
	public static final int ABSENT = -2;

	public HttpRequest {
		body = body == null ? new byte[0] : body;
	}

	/**
	 * True if the path, without its query, is {@code route}. It reads the path
	 * where it is: no copy.
	 */
	public boolean hasRoute(String route) {
		int end = path.indexOf('?');
		return (end < 0 ? path.length() : end) == route.length() && path.startsWith(route);
	}

	/**
	 * The value of {@code name} in the query, {@link #ABSENT}, or -1 if it is not a
	 * number or is negative.
	 */
	public int queryParam(String name) {
		int at = path.indexOf('?');
		if (at < 0) {
			return ABSENT;
		}
		int from = at + 1;
		while (from < path.length()) {
			int end = path.indexOf('&', from);
			if (end < 0) {
				end = path.length();
			}
			int eq = from + name.length();
			if (eq < end && path.charAt(eq) == '=' && path.startsWith(name, from)) {
				int value = 0;
				for (int i = eq + 1; i < end; i++) {
					value = digit(value, path.charAt(i));
				}
				return eq + 1 < end ? value : -1;
			}
			from = end + 1;
		}
		return ABSENT;
	}

	/**
	 * The value of {@code name} in the body, a form such as
	 * {@code product=12&qty=2}, {@link #ABSENT}, or -1 if it is not a number or is
	 * negative. It reads the body where it is: no copy.
	 */
	public int formParam(String name) {
		int from = 0;
		while (from < body.length) {
			int end = from;
			while (end < body.length && body[end] != '&') {
				end++;
			}
			int eq = from + name.length();
			if (eq < end && body[eq] == '=' && isAt(body, from, name)) {
				int value = 0;
				for (int i = eq + 1; i < end; i++) {
					value = digit(value, (char) body[i]);
				}
				return eq + 1 < end ? value : -1;
			}
			from = end + 1;
		}
		return ABSENT;
	}

	/** True if {@code name} is in {@code bytes} at {@code from}. */
	private static boolean isAt(byte[] bytes, int from, String name) {
		for (int i = 0; i < name.length(); i++) {
			if (bytes[from + i] != name.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * {@code value} with the digit {@code c} added after it. -1 stays -1, and a
	 * character that is not a digit, or a number too big, gives -1.
	 */
	private static int digit(int value, char c) {
		if (value < 0 || c < '0' || c > '9') {
			return -1;
		}
		long next = value * 10L + (c - '0');
		return next > Integer.MAX_VALUE ? -1 : (int) next;
	}
}
