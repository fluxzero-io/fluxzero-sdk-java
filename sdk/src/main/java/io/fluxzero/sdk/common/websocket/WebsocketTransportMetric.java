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

package io.fluxzero.sdk.common.websocket;

import io.fluxzero.common.api.JsonType;

/**
 * Sparse operational metric emitted only for anomalous SDK websocket transport events.
 */
record WebsocketTransportMetric(
        Event event,
        String clientType,
        String runtimeVersion,
        int javaFeatureVersion,
        String workerMode,
        String completionWorkerMode,
        int retainedMessages,
        long retainedBytes,
        int inFlightMessages,
        long inFlightBytes,
        int activeMessages,
        long activeBytes,
        int admittedMessages,
        long admittedBytes,
        int pendingMessages,
        long pendingBytes,
        int maxConcurrency,
        int maxRetainedMessages,
        long maxRetainedBytes,
        long deferredFrameBytes,
        boolean ingressBackpressured,
        int completionWorkGroups,
        int pendingCompletionAdmissions,
        int activeResultCompletions,
        int pendingResultCompletions,
        int maxCompletionConcurrency,
        long stallCloseTimeoutMillis,
        long lastInboundAgeMillis
) implements JsonType {

    enum Event {
        PING_TIMEOUT,
        RUNTIME_INGRESS_BACKPRESSURED,
        RUNTIME_INGRESS_STALLED,
        RUNTIME_INGRESS_RECOVERED,
        RUNTIME_INGRESS_OVERFLOW,
        RUNTIME_EXECUTOR_REJECTED
    }
}
