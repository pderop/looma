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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One entry of the JSON body {@link MockUpstream} answers with.
 *
 * <p>
 * Same shape as the upstream Netty-VirtualThread-Scheduler benchmark-runner's
 * {@code Fruit}, so the handler in {@link ConnectionActor} does the same decode
 * and encode work per request as the {@code HandoffHttpServer} it mirrors.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record Fruit(String name, String color, double price) {
}
