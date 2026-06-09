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

package io.fluxzero.sdk.common.websocket;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.application.PropertySource;

import java.util.Locale;

enum WebsocketResultDiagnostics {
    NONE {
        @Override
        Metadata metadata(RequestResult result, ResultTiming timing) {
            return Metadata.empty();
        }
    },
    DEFAULT {
        @Override
        Metadata metadata(RequestResult result, ResultTiming timing) {
            return baseMetadata(result);
        }
    },
    HEAVY {
        @Override
        boolean captureReceiveTiming() {
            return true;
        }

        @Override
        long timestamp() {
            return System.currentTimeMillis();
        }

        @Override
        FrameTiming frameTiming(WebsocketEndpoint.ReceiveTiming timing) {
            if (timing == null) {
                return FrameTiming.none();
            }
            return new FrameTiming(timing.frameReceivedTimestamp(), timing.frameDispatchQueuedTimestamp(),
                                   timing.frameDispatchStartedTimestamp());
        }

        @Override
        ResultTiming resultTiming(FrameTiming frameTiming, long decodedTimestamp, long callbackQueuedTimestamp,
                                  long callbackStartedTimestamp) {
            FrameTiming timing = frameTiming == null ? FrameTiming.none() : frameTiming;
            return new ResultTiming(timing.frameReceivedTimestamp(), timing.frameDispatchQueuedTimestamp(),
                                    timing.frameDispatchStartedTimestamp(), decodedTimestamp, callbackQueuedTimestamp,
                                    callbackStartedTimestamp);
        }

        @Override
        Metadata metadata(RequestResult result, ResultTiming timing) {
            return baseMetadata(result).with(clientTimingMetadata(timing));
        }
    };

    static final String MODE_PROPERTY = "FLUXZERO_WEBSOCKET_RESULT_DIAGNOSTICS";
    static final String LEGACY_TIMING_ENABLED_PROPERTY = "FLUXZERO_WEBSOCKET_RESULT_TIMING_METRICS_ENABLED";
    static final String REPLAYED_RESPONSE_METADATA_KEY = "replayedResponse";

    static WebsocketResultDiagnostics from(PropertySource propertySource) {
        String configuredMode = propertySource.get(MODE_PROPERTY);
        if (configuredMode != null && !configuredMode.isBlank()) {
            return valueOf(configuredMode.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        }
        return propertySource.getBoolean(LEGACY_TIMING_ENABLED_PROPERTY) ? HEAVY : DEFAULT;
    }

    boolean captureReceiveTiming() {
        return false;
    }

    long timestamp() {
        return 0L;
    }

    FrameTiming frameTiming(WebsocketEndpoint.ReceiveTiming timing) {
        return FrameTiming.none();
    }

    ResultTiming resultTiming(FrameTiming frameTiming, long decodedTimestamp, long callbackQueuedTimestamp,
                              long callbackStartedTimestamp) {
        return ResultTiming.none();
    }

    abstract Metadata metadata(RequestResult result, ResultTiming timing);

    static Metadata baseMetadata(RequestResult result) {
        long requestReceivedTimestamp = result.getRequestReceivedTimestamp();
        return Metadata.of(
                "requestReceivedTimestamp", requestReceivedTimestamp > 0 ? requestReceivedTimestamp : null,
                "responseTimestamp", result.getTimestamp(),
                "responseQueuedTimestamp", positiveTimestamp(result.getResponseQueuedTimestamp()),
                "responseSendStartTimestamp", positiveTimestamp(result.getResponseSendStartTimestamp()),
                "serverMsDuration", serverMsDuration(result),
                REPLAYED_RESPONSE_METADATA_KEY, isReplayedResponse(result) ? true : null);
    }

    static Metadata clientTimingMetadata(ResultTiming timing) {
        if (timing == null || timing == ResultTiming.none()) {
            return Metadata.empty();
        }
        return Metadata.of(
                "clientFrameReceivedTimestamp", timing.frameReceivedTimestamp(),
                "clientFrameDispatchQueuedTimestamp", timing.frameDispatchQueuedTimestamp(),
                "clientFrameDispatchStartedTimestamp", timing.frameDispatchStartedTimestamp(),
                "clientDecodedTimestamp", timing.decodedTimestamp(),
                "clientCallbackQueuedTimestamp", timing.callbackQueuedTimestamp(),
                "clientCallbackStartedTimestamp", timing.callbackStartedTimestamp());
    }

    static Long positiveTimestamp(long timestamp) {
        return timestamp > 0L ? timestamp : null;
    }

    static Long serverMsDuration(RequestResult result) {
        long requestReceivedTimestamp = result.getRequestReceivedTimestamp();
        if (requestReceivedTimestamp <= 0) {
            return null;
        }
        long responseTimestamp = result.getTimestamp();
        // Idempotency can replay a cached result that was created before the retried request reached the server.
        return responseTimestamp >= requestReceivedTimestamp ? responseTimestamp - requestReceivedTimestamp : 0L;
    }

    static boolean isReplayedResponse(RequestResult result) {
        long requestReceivedTimestamp = result.getRequestReceivedTimestamp();
        return requestReceivedTimestamp > 0 && result.getTimestamp() < requestReceivedTimestamp;
    }

    record FrameTiming(
            long frameReceivedTimestamp,
            long frameDispatchQueuedTimestamp,
            long frameDispatchStartedTimestamp
    ) {
        private static final FrameTiming NONE = new FrameTiming(0L, 0L, 0L);

        static FrameTiming none() {
            return NONE;
        }
    }

    record ResultTiming(
            long frameReceivedTimestamp,
            long frameDispatchQueuedTimestamp,
            long frameDispatchStartedTimestamp,
            long decodedTimestamp,
            long callbackQueuedTimestamp,
            long callbackStartedTimestamp
    ) {
        private static final ResultTiming NONE = new ResultTiming(0L, 0L, 0L, 0L, 0L, 0L);

        static ResultTiming none() {
            return NONE;
        }
    }
}
