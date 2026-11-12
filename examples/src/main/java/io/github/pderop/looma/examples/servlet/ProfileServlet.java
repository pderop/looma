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

import java.util.HashMap;
import java.util.Map;

/**
 * The {@code /profile} servlet. It keeps the profile of each session: its
 * visits, and the products it viewed last. {@code GET /profile?viewed=12} adds
 * product 12 to them. The answer lists them, with their prices. A session stays
 * in this object, which only its {@link ServletActor} touches: the container
 * that owns the session is the one {@code forKey} picks for the session id.
 */
final class ProfileServlet implements Servlet {

	/** The most viewed products a profile keeps. */
	static final int MAX_VIEWED = Integer.getInteger("servlet.recentMax", 20);

	/** The profile of one session. */
	private static final class Profile {

		private int visits;

		/**
		 * The products viewed, the last one first, each once: the first {@code size}
		 * slots. Allocated with the profile: a view writes ints, and allocates nothing.
		 */
		private final int[] viewed = new int[MAX_VIEWED];

		private int size;

		/** Puts {@code product} first. It moves up if already there. */
		void view(int product) {
			int at = 0;
			while (at < size && viewed[at] != product) {
				at++;
			}
			if (at == size && size < viewed.length) {
				size++;
			}
			// Shifts the ones before it down by one. A full list drops its last one.
			System.arraycopy(viewed, 0, viewed, 1, Math.min(at, viewed.length - 1));
			viewed[0] = product;
		}
	}

	private final Map<Long, Profile> profiles = new HashMap<>();

	ProfileServlet() {
		if (MAX_VIEWED < 1) {
			throw new IllegalArgumentException("servlet.recentMax must be above 0, got " + MAX_VIEWED);
		}
	}

	/** The product viewed, or {@link HttpRequest#ABSENT} for a visit with none. */
	@Override
	public int product(HttpRequest request) {
		return request.queryParam("viewed");
	}

	@Override
	public int quantity(HttpRequest request) {
		return 0;
	}

	@Override
	public String update(HttpRequest request, int product, int quantity) {
		boolean hasViewed = product != HttpRequest.ABSENT;
		if (hasViewed && Products.get(product) == null) {
			return "unknown product";
		}
		Profile profile = profiles.computeIfAbsent(request.sid(), id -> new Profile());
		profile.visits++;
		if (hasViewed) {
			profile.view(product);
		}
		return null;
	}

	@Override
	public void page(HttpRequest request, int checksum, int risk, StringBuilder text) {
		Profile profile = profiles.get(request.sid());
		int visits = profile == null ? 0 : profile.visits;
		text.append("visits=").append(visits).append('\n');
		if (profile != null) {
			for (int i = 0; i < profile.size; i++) {
				Products.Product product = Products.get(profile.viewed[i]);
				text.append(product.name()).append(' ').append(product.price()).append('\n');
			}
		}
		text.append("checksum=").append(checksum).append("\nrisk=").append(risk).append('\n');
	}
}
