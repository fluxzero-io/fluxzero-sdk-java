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

package io.fluxzero.common.api.tracking;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Request;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.experimental.NonFinal;

import java.beans.ConstructorProperties;

/**
 * Command to read a batch of messages from the Fluxzero Runtime for a given consumer and tracker.
 * <p>
 * This is a low-level API, typically only used internally in Fluxzero by client-side tracking mechanisms or in advanced
 * Fluxzero projects to support external consumers.
 */
@Value
@EqualsAndHashCode(callSuper = true)
@NonFinal
public class Read extends Request {

    /**
     * The type of messages to read (e.g. EVENT, COMMAND, QUERY, etc.).
     */
    MessageType messageType;

    /**
     * The logical name of the consumer performing the read.
     */
    String consumer;

    /**
     * Unique ID for the specific tracker instance.
     */
    String trackerId;

    /**
     * Maximum number of messages to return in a single batch.
     */
    int maxSize;

    /**
     * Maximum number of serialized payload bytes to return in a single batch. A value of {@code 0} disables this limit.
     */
    long maxBytes;

    /**
     * Maximum time to wait for new messages, in milliseconds. A value of {@code 0} returns immediately.
     */
    long maxTimeout;

    /**
     * Optional filter that limits messages by payload type (matches class name prefix).
     */
    String typeFilter;

    /**
     * If {@code true}, filters out messages not targeted to this client or tracker.
     */
    boolean filterMessageTarget;

    /**
     * If {@code true}, disables segment-based filtering, allowing access to all segments.
     */
    boolean ignoreSegment;

    /**
     * If {@code true}, assumes this is the only tracker for the consumer.
     */
    boolean singleTracker;

    /**
     * If {@code true}, indicates the client manages its own position/index.
     */
    boolean clientControlledIndex;

    /**
     * The last known index of the tracker, from which to start reading messages in case {@link #clientControlledIndex}
     * is {@code true} or when a new consumer is added. If {@code null}, the last known index will be queried from the
     * Fluxzero Runtime.
     */
    Long lastIndex;

    /**
     * Optional timeout (in milliseconds) after which the tracker is purged if inactive.
     */
    Long purgeTimeout;

    @ConstructorProperties({"messageType", "consumer", "trackerId", "maxSize", "maxBytes", "maxTimeout",
            "typeFilter", "filterMessageTarget", "ignoreSegment", "singleTracker", "clientControlledIndex",
            "lastIndex", "purgeTimeout"})
    public Read(MessageType messageType, String consumer, String trackerId, int maxSize, long maxBytes,
                long maxTimeout, String typeFilter, boolean filterMessageTarget, boolean ignoreSegment,
                boolean singleTracker, boolean clientControlledIndex, Long lastIndex, Long purgeTimeout) {
        this.messageType = messageType;
        this.consumer = consumer;
        this.trackerId = trackerId;
        this.maxSize = maxSize;
        this.maxBytes = maxBytes;
        this.maxTimeout = maxTimeout;
        this.typeFilter = typeFilter;
        this.filterMessageTarget = filterMessageTarget;
        this.ignoreSegment = ignoreSegment;
        this.singleTracker = singleTracker;
        this.clientControlledIndex = clientControlledIndex;
        this.lastIndex = lastIndex;
        this.purgeTimeout = purgeTimeout;
    }

    public Read(MessageType messageType, String consumer, String trackerId, int maxSize, long maxTimeout,
                String typeFilter, boolean filterMessageTarget, boolean ignoreSegment, boolean singleTracker,
                boolean clientControlledIndex, Long lastIndex, Long purgeTimeout) {
        this(messageType, consumer, trackerId, maxSize, 0L, maxTimeout, typeFilter, filterMessageTarget,
             ignoreSegment, singleTracker, clientControlledIndex, lastIndex, purgeTimeout);
    }

    /**
     * @return {@code true} if messages should not be filtered by target client/tracker ID. This is the inverse of
     * {@link #filterMessageTarget}.
     */
    @SuppressWarnings("unused")
    public boolean isIgnoreMessageTarget() {
        return !filterMessageTarget;
    }
}
