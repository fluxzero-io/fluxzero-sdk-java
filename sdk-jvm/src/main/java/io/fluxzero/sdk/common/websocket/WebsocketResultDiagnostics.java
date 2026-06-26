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

/**
 * Controls how much diagnostic metadata websocket clients publish for {@link RequestResult} messages.
 * <p>
 * The mode is resolved once when a websocket client is created. {@link #DEFAULT} is intended for normal operation and
 * only derives metadata from timestamps already present in the result. {@link #HEAVY} additionally captures client-side
 * receive, decode, queue, and callback timestamps, so it should be enabled only while diagnosing result-delivery
 * latency.
 */
enum WebsocketResultDiagnostics {
    /**
     * Disables result diagnostics metadata entirely.
     */
    NONE {
        @Override
        Metadata metadata(RequestResult result, ResultTiming timing) {
            return Metadata.empty();
        }
    },
    /**
     * Publishes lightweight result metadata without capturing extra client-side timestamps.
     */
    DEFAULT {
        @Override
        Metadata metadata(RequestResult result, ResultTiming timing) {
            return baseMetadata(result);
        }
    },
    /**
     * Publishes lightweight result metadata plus detailed client-side receive and callback timing metadata.
     */
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

    /**
     * Resolves the diagnostics mode from properties.
     * <p>
     * {@link #MODE_PROPERTY} accepts {@code none}, {@code default}, or {@code heavy}, case-insensitively and with either
     * hyphens or underscores. If it is absent, the legacy timing toggle still maps to {@link #HEAVY}; otherwise the
     * mode defaults to {@link #DEFAULT}.
     */
    static WebsocketResultDiagnostics from(PropertySource propertySource) {
        String configuredMode = propertySource.get(MODE_PROPERTY);
        if (configuredMode != null && !configuredMode.isBlank()) {
            return valueOf(configuredMode.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        }
        return propertySource.getBoolean(LEGACY_TIMING_ENABLED_PROPERTY) ? HEAVY : DEFAULT;
    }

    /**
     * Returns whether the websocket endpoint should attach receive timing to incoming frames.
     */
    boolean captureReceiveTiming() {
        return false;
    }

    /**
     * Returns the current timestamp for diagnostics, or {@code 0} when the active mode does not capture timings.
     */
    long timestamp() {
        return 0L;
    }

    /**
     * Converts endpoint receive timing into frame timing metadata input.
     */
    FrameTiming frameTiming(WebsocketEndpoint.ReceiveTiming timing) {
        return FrameTiming.none();
    }

    /**
     * Combines frame, decode, queue, and callback timestamps into result timing metadata input.
     */
    ResultTiming resultTiming(FrameTiming frameTiming, long decodedTimestamp, long callbackQueuedTimestamp,
                              long callbackStartedTimestamp) {
        return ResultTiming.none();
    }

    /**
     * Creates the metadata to publish for a result in this diagnostics mode.
     */
    abstract Metadata metadata(RequestResult result, ResultTiming timing);

    /**
     * Creates lightweight metadata derived from server-side result timestamps.
     */
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

    /**
     * Creates client-side timing metadata for heavy diagnostics.
     */
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

    /**
     * Returns the timestamp as metadata value, or {@code null} when it was not captured.
     */
    static Long positiveTimestamp(long timestamp) {
        return timestamp > 0L ? timestamp : null;
    }

    /**
     * Returns the server-side result duration in milliseconds.
     */
    static Long serverMsDuration(RequestResult result) {
        long requestReceivedTimestamp = result.getRequestReceivedTimestamp();
        if (requestReceivedTimestamp <= 0) {
            return null;
        }
        long responseTimestamp = result.getTimestamp();
        // Idempotency can replay a cached result that was created before the retried request reached the server.
        return responseTimestamp >= requestReceivedTimestamp ? responseTimestamp - requestReceivedTimestamp : 0L;
    }

    /**
     * Returns whether the result was served from idempotency replay metadata.
     */
    static boolean isReplayedResponse(RequestResult result) {
        long requestReceivedTimestamp = result.getRequestReceivedTimestamp();
        return requestReceivedTimestamp > 0 && result.getTimestamp() < requestReceivedTimestamp;
    }

    /**
     * Client-side websocket frame receive and dispatch timestamps.
     */
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

    /**
     * Client-side result decode and callback timestamps, optionally including frame timing.
     */
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
