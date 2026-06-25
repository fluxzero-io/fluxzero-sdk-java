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

package io.fluxzero.sdk.tracking;

/**
 * Controls whether handlers for a consumer are invoked in the tracker thread or offloaded to a worker thread.
 */
public enum ConsumerHandlingMode {
    /**
     * Inherit the handling mode from the message-type default consumer, app-wide default, or SDK defaults version.
     */
    DEFAULT,

    /**
     * Invoke handlers in the tracker thread.
     */
    SYNC,

    /**
     * Invoke handlers on a worker thread while preserving the Fluxzero tracking context.
     */
    ASYNC;

    /**
     * Parses a user-facing handling mode value.
     *
     * @param value configured value
     * @return parsed handling mode
     */
    public static ConsumerHandlingMode parse(String value) {
        for (ConsumerHandlingMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "Consumer handling mode must be `default`, `sync`, or `async`, but found `%s`.".formatted(value));
    }
}
