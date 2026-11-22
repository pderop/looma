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
package io.github.pderop.looma.examples.utils;

import java.util.Locale;
import java.util.Random;

/**
 * How a request picks its key: a user or a session. Key {@code k} goes to actor
 * {@code k % actors}: a group, or a container. Actor {@code a} starts on
 * carrier {@code a % carriers}: one actor per carrier, or several.
 *
 * <p>
 * With {@code hotCarrier} 0, a request picks any key: each actor gets the same
 * share of the requests. Otherwise, carrier 0 gets {@code hotCarrier} % of the
 * requests, shared by its actors, and the other carriers share the rest. Inside
 * each part, the actors get the same share. Each key of an actor can be picked.
 *
 * <p>
 * With 20 actors on 10 carriers and {@code hotCarrier} 50: actors 0 and 10
 * start on carrier 0 and get 25% each; the 18 others 2.8% each.
 *
 * @param keys
 *            the keys to pick from: 0 to {@code keys - 1}
 * @param actors
 *            the actors the keys go to
 * @param carriers
 *            the carriers the actors run on; {@code actors} is a multiple of it
 * @param hotCarrier
 *            the share of carrier 0, in %; 0 for none, else more than its even
 *            share {@code 100 / carriers}
 */
public record SkewedLoad(int keys, int actors, int carriers, int hotCarrier) {

	public SkewedLoad {
		if (carriers < 1 || actors % carriers != 0) {
			throw new IllegalArgumentException(
					"the actors must be a multiple of the carriers: " + actors + " actors, " + carriers + " carriers");
		}
		if (hotCarrier < 0 || hotCarrier >= 100) {
			throw new IllegalArgumentException("the share of carrier 0 must be between 0 and 99, got " + hotCarrier);
		}
		if (hotCarrier > 0 && carriers < 2) {
			throw new IllegalArgumentException("a skewed load needs at least 2 carriers, got " + carriers);
		}
		if (hotCarrier > 0 && hotCarrier * carriers <= 100) {
			throw new IllegalArgumentException("the share of carrier 0 must be more than its even share, "
					+ 100 / carriers + "%, got " + hotCarrier);
		}
		if (hotCarrier > 0 && keys < actors) {
			throw new IllegalArgumentException("a skewed load needs at least one key per actor: " + keys + " keys, "
					+ actors + " actors");
		}
	}

	/** Reads {@code <prefix>.hotCarrier}, default 0. */
	public static SkewedLoad fromProperties(String prefix, int keys, int actors, int carriers) {
		return new SkewedLoad(keys, actors, carriers, Integer.getInteger(prefix + ".hotCarrier", 0));
	}

	/** The key of the next request. */
	public long next(Random random) {
		if (hotCarrier == 0) {
			return random.nextInt(keys);
		}
		int carrier = random.nextInt(100) < hotCarrier ? 0 : 1 + random.nextInt(carriers - 1);
		int actor = carrier + random.nextInt(actors / carriers) * carriers;
		// The keys of an actor: actor, actor + actors, ... below keys.
		int actorKeys = (keys - actor + actors - 1) / actors;
		return actor + (long) actors * random.nextInt(actorKeys);
	}

	/** One line for the console. {@code what} names the actors: "groups". */
	public String describe(String what) {
		int perCarrier = actors / carriers;
		String placement = perCarrier == 1 ? "one per carrier" : perCarrier + " per carrier";
		if (hotCarrier == 0) {
			return "none: each of the " + actors + " " + what + " (" + placement + ") gets the same share of the"
					+ " requests";
		}
		return String.format(Locale.ROOT, "carrier 0 gets %d%% of the requests: its %d of the %d %s (%s), %.1f%%"
				+ " each; the %d others %.1f%% each", hotCarrier, perCarrier, actors, what, placement,
				(double) hotCarrier / perCarrier, actors - perCarrier, (100.0 - hotCarrier) / (actors - perCarrier));
	}
}
