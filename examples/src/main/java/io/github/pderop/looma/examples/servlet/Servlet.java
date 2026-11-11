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

/**
 * What a servlet does with its sessions. It knows nothing of actors, of the
 * backend or of the fraud check: {@link ServletActor} runs it, and is the only
 * one that touches it.
 *
 * <p>
 * For each request, the actor calls {@link #product}, {@link #quantity} and
 * {@link #update}. Then, once it has the risk and the backend body, it calls
 * {@link #page}.
 */
interface Servlet {

	/** The product of the request, for the fraud check. */
	int product(HttpRequest request);

	/** Its quantity, for the fraud check: 0 for a view. */
	int quantity(HttpRequest request);

	/**
	 * Updates the session of the request with them. Returns null, or why the
	 * request is wrong: then the client gets a 400, and nothing is called.
	 */
	String update(HttpRequest request, int product, int quantity);

	/**
	 * Writes the text of the answer in {@code out}, with the checksum of the
	 * backend body and the risk of the request. {@code out} is empty, and reused
	 * from one request to the next.
	 */
	void page(HttpRequest request, int checksum, int risk, StringBuilder out);
}
