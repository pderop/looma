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
package io.github.pderop.looma.examples.http;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The whole JSON body {@link MockUpstream} answers with: the object graph a
 * round decodes the upstream response into, and re-encodes its own response
 * from.
 *
 * <p>
 * The list is what makes the work non-trivial — ten {@link Fruit} objects
 * allocated on the way in and walked again on the way out — and it is
 * per-request garbage by design: a handler that decoded into a cached immutable
 * value would collapse into a memcpy and stop measuring the CPU side of a round
 * altogether.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record FruitsResponse(List<Fruit> fruits) {
}
