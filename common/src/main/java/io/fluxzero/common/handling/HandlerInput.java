/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.common.handling;

/**
 * Provides the input for a prepared handler invocation.
 *
 * <p>The payload is always available directly. The enclosing message may be created only when a parameter resolver,
 * interceptor, or handler asks for it. This lets local handlers that only need their payload avoid message creation
 * altogether.</p>
 *
 * @param <M> the message type used by the handling pipeline
 */
public interface HandlerInput<M> {

    /**
     * Returns the payload that is being handled.
     *
     * @return the payload; never {@code null}
     */
    Object getPayload();

    /**
     * Returns the message that contains the payload, creating it first when necessary.
     *
     * @return the message being handled
     */
    M getMessage();

    /**
     * Returns the message if it is already available.
     *
     * <p>The default implementation calls {@link #getMessage()}. Implementations that create messages lazily should
     * override this method and return {@code null} while no message has been created.</p>
     *
     * @return the existing message, or {@code null} if a lazy implementation has not created it yet
     */
    default M getMessageIfAvailable() {
        return getMessage();
    }
}
