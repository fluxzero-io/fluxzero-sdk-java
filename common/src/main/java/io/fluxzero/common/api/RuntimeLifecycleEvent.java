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

package io.fluxzero.common.api;

import lombok.Value;

/**
 * Metric event emitted when a Fluxzero Runtime changes lifecycle phase.
 */
@Value
public class RuntimeLifecycleEvent implements JsonType {

    /**
     * The lifecycle phase that was reached by the runtime.
     */
    Phase phase;

    /**
     * Human-readable runtime name, such as {@code FluxzeroTestServer}.
     */
    String runtime;

    /**
     * Runtime version if available from build metadata.
     */
    String runtimeVersion;

    /**
     * Port the runtime listens on.
     */
    int port;

    /**
     * Epoch milliseconds when the lifecycle phase occurred.
     */
    long timestamp;

    /**
     * Runtime lifecycle phases that are published as metrics.
     */
    public enum Phase {
        /**
         * The runtime has started and is ready to accept traffic.
         */
        STARTED,

        /**
         * The runtime is about to stop accepting traffic and shut down.
         */
        STOPPING
    }
}
