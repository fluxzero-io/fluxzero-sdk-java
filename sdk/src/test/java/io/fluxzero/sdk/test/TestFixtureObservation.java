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

package io.fluxzero.sdk.test;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.web.WebRequest;

import java.util.Objects;

/**
 * Machine-readable observation emitted by {@link TestFixture} when dev test impact indexing is enabled.
 *
 * @param testId               stable test selector, usually {@code fully.qualified.TestClass#method}
 * @param kind                 observation kind
 * @param handlerClass         handler class for handling observations
 * @param handlerMethod        handler method for handling observations
 * @param payloadClass         payload class handled or dispatched
 * @param messageType          Fluxzero message type
 * @param topic                message topic or document collection
 * @param namespace            Fluxzero namespace, if known
 * @param webMethod            HTTP/WebSocket method for web requests
 * @param webPath              HTTP/WebSocket path for web requests
 * @param documentCollection   document collection for document observations
 * @param schedulePayloadClass scheduled payload class for schedule observations
 * @param timestamp            observation timestamp in epoch milliseconds
 */
public record TestFixtureObservation(
        String testId,
        Kind kind,
        String handlerClass,
        String handlerMethod,
        String payloadClass,
        String messageType,
        String topic,
        String namespace,
        String webMethod,
        String webPath,
        String documentCollection,
        String schedulePayloadClass,
        long timestamp
) {

    public enum Kind {
        DISPATCH,
        HANDLING
    }

    static TestFixtureObservation dispatch(Message message, MessageType messageType, String topic, String namespace) {
        String payloadClass = className(message.getPayloadClass());
        return new TestFixtureObservation(
                null, Kind.DISPATCH, null, null, payloadClass, messageType.name(), topic, namespace,
                webMethod(messageType, message), webPath(messageType, message),
                messageType == MessageType.DOCUMENT ? topic : null,
                messageType == MessageType.SCHEDULE ? payloadClass : null,
                System.currentTimeMillis());
    }

    static TestFixtureObservation handling(DeserializingMessage message, HandlerInvoker invoker) {
        String payloadClass = className(message.getPayloadClass());
        return new TestFixtureObservation(
                null, Kind.HANDLING, invoker.getTargetClass().getName(), invoker.getMethod().getName(),
                payloadClass, message.getMessageType().name(), message.getTopic(), null,
                webMethod(message), webPath(message),
                message.getMessageType() == MessageType.DOCUMENT ? message.getTopic() : null,
                message.getMessageType() == MessageType.SCHEDULE ? payloadClass : null,
                System.currentTimeMillis());
    }

    TestFixtureObservation withTestId(String testId) {
        return Objects.equals(this.testId, testId) ? this : new TestFixtureObservation(
                testId, kind, handlerClass, handlerMethod, payloadClass, messageType, topic, namespace,
                webMethod, webPath, documentCollection, schedulePayloadClass, timestamp);
    }

    private static String className(Class<?> type) {
        return type == null || type == Void.class ? null : type.getName();
    }

    private static String webMethod(MessageType messageType, Message message) {
        return messageType == MessageType.WEBREQUEST && message instanceof WebRequest request
                ? request.getMethod() : null;
    }

    private static String webPath(MessageType messageType, Message message) {
        return messageType == MessageType.WEBREQUEST && message instanceof WebRequest request
                ? request.getPath() : null;
    }

    private static String webMethod(DeserializingMessage message) {
        return message.getMessageType() == MessageType.WEBREQUEST
                ? WebRequest.getMethod(message.getMetadata()) : null;
    }

    private static String webPath(DeserializingMessage message) {
        return message.getMessageType() == MessageType.WEBREQUEST
                ? WebRequest.getUrl(message.getMetadata()) : null;
    }
}
