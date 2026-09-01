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
package io.github.pderop.looma.examples.hello;

import io.github.pderop.looma.Message;

/**
 * A greeting request. It carries no destination: routing is entirely the job of
 * the {@code ActorRef} it is {@code tell}/{@code ask}ed through.
 */
public record Hello(String name) implements Message {
}
