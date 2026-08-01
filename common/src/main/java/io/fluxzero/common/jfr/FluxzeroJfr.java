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

package io.fluxzero.common.jfr;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Shared low-overhead JFR events for following one Fluxzero message pipeline across SDK and Runtime components.
 *
 * <p>Batch events are emitted only while their JFR event type is enabled. Request-stage events use a stable one in
 * 4,096 sample based on the request identifier, so the same request remains selected at every participating stage.
 */
public final class FluxzeroJfr {

    private static final long TRACE_SAMPLE_MASK = 4_096L - 1L;
    private static final int MAX_TRACE_CORRELATIONS = 16_384;
    private static final EventType BATCH_TYPE = EventType.getEventType(Batch.class);
    private static final EventType REQUEST_STAGE_TYPE = EventType.getEventType(RequestStage.class);

    private FluxzeroJfr() {
    }

    /** Returns whether batch diagnostics are enabled in the active recording. */
    public static boolean batchEnabled() {
        return BATCH_TYPE.isEnabled();
    }

    /** Returns whether sampled request-stage diagnostics are enabled in the active recording. */
    public static boolean requestStageEnabled() {
        return REQUEST_STAGE_TYPE.isEnabled();
    }

    /** Returns whether the supplied identifier belongs to the deterministic request-trace sample. */
    public static boolean requestTraceSampled(long requestId) {
        return (requestId & TRACE_SAMPLE_MASK) == 0L;
    }

    /**
     * Remembers a sampled request across an asynchronous or protocol boundary that preserves only a string key.
     *
     * <p>The bounded table is populated only while request-stage recording is active. SDK and Runtime JVMs each
     * populate their own table from information already available locally, so this does not add correlation data to
     * the wire format.</p>
     */
    public static void registerTraceCorrelation(String correlationKey, long requestId) {
        if (correlationKey == null || !requestStageEnabled() || !requestTraceSampled(requestId)) {
            return;
        }
        TraceCorrelations.register(correlationKey, requestId);
    }

    /** Resolves a sampled request previously registered through {@link #registerTraceCorrelation(String, long)}. */
    public static Long resolveTraceCorrelation(String correlationKey) {
        if (correlationKey == null || !requestStageEnabled()) {
            return null;
        }
        return TraceCorrelations.resolve(correlationKey);
    }

    /**
     * Starts one batch event, returning {@code null} without allocating an event when batch diagnostics are disabled.
     */
    public static Batch startBatch(String component, String operation, String messageType,
                                   int itemCount, long bytes, long queueDepth, long queueBytes) {
        if (!batchEnabled()) {
            return null;
        }
        Batch event = new Batch();
        event.component = component;
        event.operation = operation;
        event.messageType = messageType;
        event.itemCount = itemCount;
        event.bytes = bytes;
        event.queueDepth = queueDepth;
        event.queueBytes = queueBytes;
        event.begin();
        return event;
    }

    /** Completes and conditionally commits a batch event. */
    public static void finish(Batch event, Throwable failure) {
        if (event == null) {
            return;
        }
        event.failed = failure != null;
        event.failureType = failure == null ? null : failure.getClass().getName();
        event.end();
        if (event.shouldCommit()) {
            event.commit();
        }
    }

    /** Emits one deterministically sampled request-stage event. */
    public static void requestStage(long requestId, String component, String stage, int batchSize, long messageIndex) {
        if (!requestTraceSampled(requestId) || !requestStageEnabled()) {
            return;
        }
        RequestStage event = new RequestStage();
        event.requestId = requestId;
        event.component = component;
        event.stage = stage;
        event.batchSize = batchSize;
        event.messageIndex = messageIndex;
        event.commit();
    }

    /** One completed batch operation in the SDK or Runtime pipeline. */
    @Name("io.fluxzero.Batch")
    @Label("Fluxzero batch")
    @Category({"Fluxzero", "Pipeline"})
    @StackTrace(false)
    public static final class Batch extends Event {
        @Label("Component")
        public String component;
        @Label("Operation")
        public String operation;
        @Label("Message type")
        public String messageType;
        @Label("Items")
        public int itemCount;
        @Label("Output items")
        public int outputItemCount;
        @Label("Bytes")
        @DataAmount
        public long bytes;
        @Label("Queue depth")
        public long queueDepth;
        @Label("Queued bytes")
        @DataAmount
        public long queueBytes;
        @Label("Active workers")
        public int activeWorkers;
        @Label("Sub-batches")
        public int subBatchCount;
        @Label("JDBC round trips")
        public int jdbcRoundTrips;
        @Label("Queue wait")
        @Timespan(Timespan.NANOSECONDS)
        public long queueWaitNanos;
        @Label("Preparation")
        @Timespan(Timespan.NANOSECONDS)
        public long preparationNanos;
        @Label("Storage")
        @Timespan(Timespan.NANOSECONDS)
        public long storageNanos;
        @Label("Publication")
        @Timespan(Timespan.NANOSECONDS)
        public long publicationNanos;
        @Label("Callback")
        @Timespan(Timespan.NANOSECONDS)
        public long callbackNanos;
        @Label("Failed")
        public boolean failed;
        @Label("Failure type")
        public String failureType;
    }

    /** One stage reached by a deterministically sampled request. */
    @Name("io.fluxzero.RequestStage")
    @Label("Fluxzero request stage")
    @Category({"Fluxzero", "Pipeline"})
    @StackTrace(false)
    public static final class RequestStage extends Event {
        @Label("Request id")
        public long requestId;
        @Label("Component")
        public String component;
        @Label("Stage")
        public String stage;
        @Label("Batch size")
        public int batchSize;
        @Label("Message index")
        public long messageIndex;
    }

    private static final class TraceCorrelations {
        private static final Map<String, Long> values = new ConcurrentHashMap<>();
        private static final ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();

        private static void register(String key, long requestId) {
            if (values.put(key, requestId) == null) {
                order.add(key);
            }
            while (values.size() > MAX_TRACE_CORRELATIONS) {
                String oldest = order.poll();
                if (oldest == null) {
                    break;
                }
                values.remove(oldest);
            }
        }

        private static Long resolve(String key) {
            return values.get(key);
        }
    }
}
