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
 * The reply to a {@link Hello}. A reply is an ordinary {@link Message} sent
 * back to the sender, so an {@code ask} is typed the same way in both
 * directions.
 */
public record Greeting(String text) implements Message {
}
