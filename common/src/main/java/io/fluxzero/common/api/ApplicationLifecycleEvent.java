/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.common.api;

import lombok.Value;

/**
 * Metric event emitted when a Fluxzero application instance starts or begins shutting down.
 * <p>
 * A {@link Phase#STARTED} event is only published after the fully constructed application can store a message through
 * its metrics gateway. Its presence therefore confirms both application startup and connectivity to a Fluxzero
 * Runtime endpoint. A matching {@link Phase#STOPPING} event is attempted during controlled shutdown without delaying
 * shutdown indefinitely.
 */
@Value
public class ApplicationLifecycleEvent {

    /** Lifecycle phase represented by this event. */
    Phase phase;

    /** Unique identifier shared by the lifecycle events of one application instance. */
    String startupId;

    /** Stable application identifier, if configured. */
    String applicationId;

    /** Human-readable client or application name. */
    String clientName;

    /** Unique identifier of this client instance. */
    String clientId;

    /** Namespace used by this application, if configured. */
    String namespace;

    /** Version of the Fluxzero SDK, if available from build metadata. */
    String sdkVersion;

    /** Epoch milliseconds at which construction of this application instance completed. */
    long startedAt;

    /** Epoch milliseconds at which this lifecycle phase was initiated. */
    long timestamp;

    /** Application lifecycle phases published as metrics. */
    public enum Phase {
        /** The application was constructed and reached a Fluxzero Runtime metrics endpoint. */
        STARTED,

        /** The application has begun a controlled shutdown. */
        STOPPING
    }
}
