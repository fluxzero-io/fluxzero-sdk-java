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

package io.fluxzero.sdk.tracking.metrics;

import io.fluxzero.common.MessageType;
import lombok.Value;

/**
 * Metric event published when a tracked message matched a handler but was deliberately skipped before invocation.
 * <p>
 * This is distinct from a handler failure: no handler method was invoked and no handler result should be expected.
 */
@Value
public class IgnoreMessageEvent {
    /**
     * Reason emitted when an indexed request is skipped because its effective timeout expired before handler invocation.
     */
    public static final String EXPIRED_REQUEST = "expiredRequest";

    /**
     * Name of the consumer or local handler context that ignored the message.
     */
    String consumer;

    /**
     * Simple name of the handler class that would have handled the message.
     */
    String handler;

    /**
     * Index of the ignored message, when available.
     */
    Long messageIndex;

    /**
     * Type of message that was ignored.
     */
    MessageType messageType;

    /**
     * Topic of the ignored message, when available.
     */
    String topic;

    /**
     * Payload type or route descriptor of the ignored message.
     */
    String payloadType;

    /**
     * Machine-readable reason for ignoring the message.
     */
    String reason;
}
