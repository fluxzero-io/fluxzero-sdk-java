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

/**
 * Marker interface for responses to {@link Request} objects (including commands and queries).
 * <p>
 * Implementations of this interface are returned by the Fluxzero Runtime and matched to
 * their corresponding {@link Request} using the {@code requestId}.
 * </p>
 *
 * <p>
 * A typical {@code RequestResult} contains minimal payload and metadata for monitoring,
 * and may override {@link #toMetric()} to avoid logging full results.
 * </p>
 *
 * @see Request
 * @see ErrorResult
 */
public interface RequestResult extends JsonType {

    /**
     * The requestId of the original {@link Request} this result corresponds to.
     */
    long getRequestId();

    /**
     * The timestamp (in epoch milliseconds) when this result was generated.
     */
    long getTimestamp();

    /**
     * The timestamp (in epoch milliseconds) when the Fluxzero Runtime received the original request.
     * <p>
     * A value of {@code 0} means the runtime did not provide this timestamp.
     */
    default long getRequestReceivedTimestamp() {
        return 0L;
    }

    /**
     * Updates the timestamp at which the Fluxzero Runtime received the original request.
     * <p>
     * Implementations that do not store this metadata may ignore the update.
     */
    default void setRequestReceivedTimestamp(long requestReceivedTimestamp) {
    }

    /**
     * The timestamp (in epoch milliseconds) when the Fluxzero Runtime queued this response for websocket delivery.
     * <p>
     * A value of {@code 0} means the runtime did not provide this timestamp.
     */
    default long getResponseQueuedTimestamp() {
        return 0L;
    }

    /**
     * Updates the timestamp at which the Fluxzero Runtime queued this response for websocket delivery.
     */
    default void setResponseQueuedTimestamp(long responseQueuedTimestamp) {
    }

    /**
     * The timestamp (in epoch milliseconds) just before the Fluxzero Runtime encoded and sent this response.
     * <p>
     * A value of {@code 0} means the runtime did not provide this timestamp.
     */
    default long getResponseSendStartTimestamp() {
        return 0L;
    }

    /**
     * Updates the timestamp just before the Fluxzero Runtime encoded and sent this response.
     */
    default void setResponseSendStartTimestamp(long responseSendStartTimestamp) {
    }
}
