/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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
 *
 */

package io.fluxzero.sdk.publishing;

/**
 * Browser-safe gateway for command messages.
 * <p>
 * The shared API intentionally exposes only synchronous command dispatch. JVM runtimes can offer richer asynchronous
 * operations by implementing {@code AsyncCommandGateway}.
 */
public interface CommandGateway {

    /**
     * Sends a command without waiting for a result.
     *
     * @param command the command payload
     */
    void sendAndForget(Object command);

    /**
     * Sends a command and waits for the result.
     *
     * @param command the command payload
     * @param <R> expected result type
     * @return command result
     */
    <R> R sendAndWait(Object command);
}
