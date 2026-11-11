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
 * The {@code /cart} servlet. Each POST is a form, {@code product=12&qty=2}: it
 * adds that quantity of the product to the session's cart. The answer lists the
 * cart, with the price of each line and the total. The carts stay in this
 * object, which only its {@link ServletActor} touches.
 */
final class CartServlet implements Servlet {

	/** The most lines a cart keeps. A new line beyond that drops the oldest. */
	static final int MAX_LINES = Integer.getInteger("servlet.cartMax", 20);

	/**
	 * The cart of one session: line {@code i} is {@code quantities[i]} of
	 * {@code products[i]}, the oldest line first. Allocated with the cart: an add
	 * writes ints, and allocates nothing.
	 */
	private static final class Cart {

		private final int[] products = new int[MAX_LINES];

		private final int[] quantities = new int[MAX_LINES];

		private int lines;

		/** Adds {@code quantity} of {@code product}, on its line if it has one. */
		void add(int product, int quantity) {
			for (int i = 0; i < lines; i++) {
				if (products[i] == product) {
					quantities[i] += quantity;
					return;
				}
			}
			if (lines == MAX_LINES) {
				// Full: the oldest line goes.
				System.arraycopy(products, 1, products, 0, lines - 1);
				System.arraycopy(quantities, 1, quantities, 0, lines - 1);
				lines--;
			}
			products[lines] = product;
			quantities[lines] = quantity;
			lines++;
		}
	}

	/** The carts, by session. */
	private final Map<Long, Cart> carts = new HashMap<>();

	CartServlet() {
		if (MAX_LINES < 1) {
			throw new IllegalArgumentException("servlet.cartMax must be above 0, got " + MAX_LINES);
		}
	}

	@Override
	public int product(HttpRequest request) {
		return request.formParam("product");
	}

	@Override
	public int quantity(HttpRequest request) {
		return request.formParam("qty");
	}

	@Override
	public String update(HttpRequest request, int product, int quantity) {
		if (Products.get(product) == null || quantity < 1) {
			return "want product=<id>&qty=<n>";
		}
		carts.computeIfAbsent(request.sid(), id -> new Cart()).add(product, quantity);
		return null;
	}

	@Override
	public void page(HttpRequest request, int checksum, int risk, StringBuilder text) {
		Cart cart = carts.get(request.sid());
		int lines = cart == null ? 0 : cart.lines;
		long total = 0;
		for (int i = 0; i < lines; i++) {
			Products.Product product = Products.get(cart.products[i]);
			long price = (long) product.price() * cart.quantities[i];
			total += price;
			text.append(product.name()).append(" x").append(cart.quantities[i]).append(' ').append(price).append('\n');
		}
		text.append("items=")
				.append(lines)
				.append("\ntotal=")
				.append(total)
				.append("\nchecksum=")
				.append(checksum)
				.append("\nrisk=")
				.append(risk)
				.append('\n');
	}
}
