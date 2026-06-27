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

package io.fluxzero.sdk.browser;

import io.fluxzero.common.MessageType;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Browser-safe message envelope used by the in-memory browser execution core.
 */
public final class BrowserMessage {
    private final MessageType messageType;
    private final String topic;
    private final Object payload;
    private final String payloadTypeName;
    private final Map<String, String> metadata;
    private final Instant timestamp;

    public BrowserMessage(MessageType messageType, String topic, Object payload, String payloadTypeName,
                          Map<String, String> metadata, Instant timestamp) {
        this.messageType = Objects.requireNonNull(messageType, "messageType");
        this.topic = topic == null ? "" : topic;
        this.payload = payload;
        this.payloadTypeName = payloadTypeName == null ? "" : payloadTypeName;
        this.metadata = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(metadata, "metadata")));
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }

    public static BrowserMessage of(MessageType messageType, String topic, Object payload,
                                    Map<String, String> metadata, Instant timestamp) {
        return new BrowserMessage(messageType, topic, payload, metadata.getOrDefault("payloadTypeName", ""),
                                  metadata, timestamp);
    }

    public static BrowserMessage of(MessageType messageType, String topic, Object payload, String payloadTypeName,
                                    Map<String, String> metadata, Instant timestamp) {
        return new BrowserMessage(messageType, topic, payload, payloadTypeName, metadata, timestamp);
    }

    public MessageType messageType() {
        return messageType;
    }

    public String topic() {
        return topic;
    }

    public Object payload() {
        return payload;
    }

    public String payloadTypeName() {
        return payloadTypeName;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public BrowserMessage withMetadata(String key, String value) {
        Map<String, String> updated = new LinkedHashMap<>(metadata);
        updated.put(key, value);
        return new BrowserMessage(messageType, topic, payload, payloadTypeName, updated, timestamp);
    }

    public Instant timestamp() {
        return timestamp;
    }
}
