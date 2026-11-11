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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** The parse of {@link HttpRequest}: the route, the query and the form. */
class HttpRequestTest {

	private static HttpRequest get(String path) {
		return new HttpRequest("GET", path, 1, null);
	}

	private static HttpRequest post(String form) {
		return new HttpRequest("POST", "/cart", 1, form.getBytes(StandardCharsets.US_ASCII));
	}

	@Test
	void theRouteIsThePathWithoutItsQuery() {
		assertTrue(get("/profile").hasRoute("/profile"));
		assertTrue(get("/profile?viewed=3").hasRoute("/profile"));
		assertFalse(get("/profiles").hasRoute("/profile"));
		assertFalse(get("/pro").hasRoute("/profile"));
		assertFalse(get("/cart?x=1").hasRoute("/profile"));
	}

	@Test
	void aQueryParamIsANumberAbsentOrMinusOne() {
		assertEquals(12, get("/profile?viewed=12").queryParam("viewed"));
		assertEquals(7, get("/profile?a=1&viewed=7&b=2").queryParam("viewed"));
		assertEquals(HttpRequest.ABSENT, get("/profile").queryParam("viewed"));
		assertEquals(HttpRequest.ABSENT, get("/profile?viewedx=1").queryParam("viewed"));
		assertEquals(HttpRequest.ABSENT, get("/profile?view=1").queryParam("viewed"));
		assertEquals(-1, get("/profile?viewed=").queryParam("viewed"));
		assertEquals(-1, get("/profile?viewed=abc").queryParam("viewed"));
		assertEquals(-1, get("/profile?viewed=-5").queryParam("viewed"));
		assertEquals(-1, get("/profile?viewed=99999999999").queryParam("viewed"));
	}

	@Test
	void aFormParamIsANumberAbsentOrMinusOne() {
		assertEquals(12, post("product=12&qty=2").formParam("product"));
		assertEquals(2, post("product=12&qty=2").formParam("qty"));
		assertEquals(HttpRequest.ABSENT, post("product=12").formParam("qty"));
		assertEquals(HttpRequest.ABSENT, post("").formParam("qty"));
		assertEquals(HttpRequest.ABSENT, post("qtyx=3").formParam("qty"));
		assertEquals(-1, post("qty=").formParam("qty"));
		assertEquals(-1, post("qty=x").formParam("qty"));
		assertEquals(-1, post("qty=-1").formParam("qty"));
	}
}
