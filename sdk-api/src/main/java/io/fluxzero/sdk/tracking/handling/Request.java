/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.tracking.handling;

/**
 * Marker interface for request messages (e.g., commands or queries) that expect a response of a specific type.
 * <p>
 * Implementing this interface allows the Fluxzero client to infer the return type of a request, enabling:
 * <ol>
 *     <li>Type-safe request invocations (e.g. {@code Fluxzero.queryAndWait(Request<T>)} returns {@code T})</li>
 *     <li>Compile-time verification that handler methods return a value compatible with the expected response</li>
 * </ol>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @Value
 * public class GetUser implements Request<UserProfile> {
 *     String userId;
 *
 *     @HandleQuery
 *     UserProfile handle(GetUser query) {
 *         return Fluxzero.search(UserProfile.class).match(query.getUserId()).fetchFirstOrNull();
 *     }
 * }
 * }</pre>
 *
 * <p>
 * The generic response type is intentionally a compile-time contract in the browser-safe API. JVM runtimes may resolve
 * it reflectively when they need runtime type information.
 *
 * @param <R> the expected response type of the request
 */
@SuppressWarnings("unused")
public interface Request<R> {
}
