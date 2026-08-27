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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fluxzero.common.api.AbstractRequestResult;
import lombok.Value;

/**
 * Result returned in response to a {@link Read} request for a batch of messages.
 * <p>
 * Contains the retrieved {@link MessageBatch} and metadata such as request ID and timestamp.
 */
@Value
public class ReadResult extends AbstractRequestResult {

    /**
     * The unique request ID corresponding to the {@link Read} request.
     */
    long requestId;

    /**
     * The batch of messages retrieved for the consumer/tracker.
     */
    MessageBatch messageBatch;

    /**
     * The time (epoch millis) when this result was created.
     */
    long timestamp;

    public ReadResult(long requestId, MessageBatch messageBatch) {
        this(requestId, messageBatch, System.currentTimeMillis());
    }

    @JsonCreator
    public ReadResult(@JsonProperty("requestId") long requestId,
                      @JsonProperty("messageBatch") MessageBatch messageBatch,
                      @JsonProperty("timestamp") long timestamp) {
        this.requestId = requestId;
        this.messageBatch = messageBatch;
        this.timestamp = timestamp;
    }

    /**
     * Produces a metric-friendly summary of the result for publishing to the Fluxzero metrics log.
     *
     * @return a {@link Metric} with batch metadata and timestamp
     */
    @Override
    public Metric toMetric() {
        return new Metric(messageBatch.toMetric(), timestamp);
    }

    /**
     * Compact representation of the {@link ReadResult} used for monitoring.
     */
    @Value
    public static class Metric {

        /**
         * Metrics extracted from the contained {@link MessageBatch}.
         */
        MessageBatch.Metric messageBatch;

        /**
         * The timestamp when the metric was created.
         */
        long timestamp;
    }
}
