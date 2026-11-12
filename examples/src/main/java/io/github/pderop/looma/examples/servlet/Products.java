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
import java.util.Random;

/**
 * The products of the shop, {@code servlet.products} of them. Built once, read
 * by every servlet, never changed.
 */
final class Products {

	static final int COUNT = Integer.getInteger("servlet.products", 10_000);

	/** One product. The price is in cents. */
	record Product(int id, String name, int price) {
	}

	private static final Map<Integer, Product> ALL = build();

	private Products() {
	}

	/** The product {@code id}, or null if there is none. */
	static Product get(int id) {
		return ALL.get(id);
	}

	private static Map<Integer, Product> build() {
		if (COUNT < 1) {
			throw new IllegalArgumentException("servlet.products must be above 0, got " + COUNT);
		}
		Random random = new Random(1);
		Map<Integer, Product> all = new HashMap<>();
		for (int id = 0; id < COUNT; id++) {
			all.put(id, new Product(id, "product-" + id, 100 + random.nextInt(9_900)));
		}
		return Map.copyOf(all);
	}
}
