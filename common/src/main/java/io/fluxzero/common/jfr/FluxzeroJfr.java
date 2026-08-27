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

/**
 * Shared low-overhead JFR events for Fluxzero batch operations across SDK and Runtime components.
 */
public final class FluxzeroJfr {

    private static final EventType BATCH_TYPE = EventType.getEventType(Batch.class);

    private FluxzeroJfr() {
    }

    /** Returns whether batch diagnostics are enabled in the active recording. */
    public static boolean batchEnabled() {
        return BATCH_TYPE.isEnabled();
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

}
