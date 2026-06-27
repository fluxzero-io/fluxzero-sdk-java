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

import java.util.Objects;

/**
 * Generated handler registration metadata for browser execution.
 */
public final class BrowserHandlerRegistration {
    private final String feature;
    private final MessageType messageType;
    private final String topic;
    private final String payloadTypeName;
    private final boolean passive;
    private final BrowserHandler handler;

    public BrowserHandlerRegistration(String feature, MessageType messageType, String topic, String payloadTypeName,
                                      boolean passive, BrowserHandler handler) {
        this.feature = Objects.requireNonNull(feature, "feature");
        this.messageType = Objects.requireNonNull(messageType, "messageType");
        this.topic = topic == null ? "" : topic;
        this.payloadTypeName = payloadTypeName == null ? "" : payloadTypeName;
        this.passive = passive;
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public String feature() {
        return feature;
    }

    public MessageType messageType() {
        return messageType;
    }

    public String topic() {
        return topic;
    }

    public String payloadTypeName() {
        return payloadTypeName;
    }

    public boolean passive() {
        return passive;
    }

    public BrowserHandler handler() {
        return handler;
    }

    boolean matches(BrowserMessage message) {
        if (messageType != message.messageType()) {
            return false;
        }
        if (!topic.isBlank() && !topic.equals(message.topic())) {
            return false;
        }
        return payloadTypeName.isBlank()
               || payloadTypeName.equals(message.payloadTypeName())
               || payloadTypeName.equals(simpleName(message.payloadTypeName()))
               || Object.class.getName().equals(payloadTypeName)
               || Object.class.getSimpleName().equals(payloadTypeName);
    }

    private static String simpleName(String typeName) {
        int dot = typeName.lastIndexOf('.');
        return dot < 0 ? typeName : typeName.substring(dot + 1);
    }
}
