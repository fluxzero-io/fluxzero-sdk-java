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

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.MethodInvocationValidator;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.publishing.RequestHandler;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.IndexUtils;
import io.fluxzero.sdk.tracking.metrics.HandleMessageEvent;
import io.fluxzero.sdk.tracking.metrics.IgnoreMessageEvent;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.HandleSocketMessage;
import io.fluxzero.sdk.web.HttpRequestMethod;
import io.fluxzero.sdk.web.WebRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpiredRequestDecoratorTest {

    @Test
    void commandHandlersDoNotSkipExpiredRequestsByDefault() {
        Handler<DeserializingMessage> handler = handler(MessageType.COMMAND, new CommandHandler());

        assertTrue(handler.getInvoker(expiredMessage(MessageType.COMMAND, true)).isPresent());
    }

    @Test
    void commandHandlersCanOptInToSkippingExpiredRequests() {
        Handler<DeserializingMessage> handler = handler(MessageType.COMMAND, new SkippingCommandHandler());

        assertFalse(handler.getInvoker(expiredMessage(MessageType.COMMAND, true)).isPresent());
    }

    @Test
    void queryHandlersSkipExpiredRequestsByDefault() {
        Handler<DeserializingMessage> handler = handler(MessageType.QUERY, new QueryHandler());

        assertFalse(handler.getInvoker(expiredMessage(MessageType.QUERY, true)).isPresent());
    }

    @Test
    void queryHandlersCanOptOutOfSkippingExpiredRequests() {
        Handler<DeserializingMessage> handler = handler(MessageType.QUERY, new NonSkippingQueryHandler());

        assertTrue(handler.getInvoker(expiredMessage(MessageType.QUERY, true)).isPresent());
    }

    @Test
    void localRequestsWithoutIndexAreNotSkipped() {
        Handler<DeserializingMessage> handler = handler(MessageType.QUERY, new QueryHandler());

        assertTrue(handler.getInvoker(expiredMessage(MessageType.QUERY, false)).isPresent());
    }

    @Test
    void webShortcutHandlersCanOptOutOfSkippingExpiredRequests() {
        Handler<DeserializingMessage> handler = handler(MessageType.WEBREQUEST, new NonSkippingWebHandler());

        assertTrue(handler.getInvoker(expiredWebRequest(HttpRequestMethod.GET, "/test")).isPresent());
    }

    @Test
    void webShortcutHandlersSkipExpiredRequestsByDefault() {
        Handler<DeserializingMessage> handler = handler(MessageType.WEBREQUEST, new WebHandler());

        assertFalse(handler.getInvoker(expiredWebRequest(HttpRequestMethod.GET, "/test")).isPresent());
    }

    @Test
    void websocketHandlersDoNotSkipExpiredRequestsByDefault() {
        Handler<DeserializingMessage> handler = handler(MessageType.WEBREQUEST, new SocketHandler());

        assertTrue(handler.getInvoker(expiredWebRequest(HttpRequestMethod.WS_MESSAGE, "/socket")).isPresent());
    }

    @Test
    void skippedRequestsPublishIgnoreMessageMetricsInsteadOfHandleMessageMetrics() {
        Handler<DeserializingMessage> handler = handler(MessageType.QUERY, new QueryHandler());

        TestFixture.create().whenApplying(fc -> {
            assertFalse(handler.getInvoker(expiredMessage(MessageType.QUERY, true)).isPresent());
            return null;
        }).<IgnoreMessageEvent>expectMetric(e -> e.getReason().equals(IgnoreMessageEvent.EXPIRED_REQUEST)
                                             && e.getMessageType() == MessageType.QUERY
                                             && e.getHandler().equals(QueryHandler.class.getSimpleName()))
                .expectNoMetricsLike(HandleMessageEvent.class);
    }

    @Test
    void handlerFactoryDoesNotPublishIgnoreMessageMetricsWhenTrackingMetricsAreDisabled() {
        Handler<DeserializingMessage> handler = handler(MessageType.QUERY, new QueryHandler(), false);

        TestFixture.create().whenApplying(fc -> {
            assertFalse(handler.getInvoker(expiredMessage(MessageType.QUERY, true)).isPresent());
            return null;
        }).expectNoMetricsLike(IgnoreMessageEvent.class);
    }

    @Test
    void nonMatchingHandlerMethodsDoNotPublishIgnoreMessageMetrics() {
        Handler<DeserializingMessage> handler = handler(MessageType.QUERY, new TypedQueryHandler());

        TestFixture.create().whenApplying(fc -> {
            assertFalse(handler.getInvoker(expiredMessage(MessageType.QUERY, true, BarQuery.class)).isPresent());
            return null;
        }).expectNoMetricsLike(IgnoreMessageEvent.class);
    }

    @Test
    void decoratorDoesNotExposeReusableHandlerMethodThatCouldBypassExpiryCheck() {
        Handler<DeserializingMessage> handler = handler(MessageType.QUERY, new QueryHandler());

        assertNull(handler.getHandlerMethodOrNull(expiredMessage(MessageType.QUERY, true)));
    }

    private static Handler<DeserializingMessage> handler(MessageType messageType, Object target) {
        return handler(messageType, target, true);
    }

    private static Handler<DeserializingMessage> handler(MessageType messageType, Object target,
                                                         boolean trackingMetricsEnabled) {
        return new DefaultHandlerFactory(messageType, HandlerDecorator.noOp, List.of(),
                                         MethodInvocationValidator.noOp(), c -> null, null,
                                         trackingMetricsEnabled, null)
                .createHandler(target, (c, e) -> true, List.of()).orElseThrow();
    }

    private static DeserializingMessage expiredMessage(MessageType messageType, boolean indexed) {
        return expiredMessage(messageType, indexed, String.class, Metadata.empty());
    }

    private static DeserializingMessage expiredMessage(MessageType messageType, boolean indexed, Class<?> payloadType) {
        return expiredMessage(messageType, indexed, payloadType, Metadata.empty());
    }

    private static DeserializingMessage expiredWebRequest(String method, String path) {
        return expiredMessage(MessageType.WEBREQUEST, true, String.class,
                              Metadata.of(WebRequest.methodKey, method, WebRequest.urlKey, path));
    }

    private static DeserializingMessage expiredMessage(MessageType messageType, boolean indexed, Class<?> payloadType,
                                                      Metadata metadata) {
        Instant timestamp = Instant.now().minusSeconds(40);
        SerializedMessage message = new SerializedMessage(
                new Data<>(new byte[0], payloadType.getName(), 0),
                metadata.with(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY, 1_000L),
                "message-id", timestamp.toEpochMilli());
        if (indexed) {
            message.setIndex(IndexUtils.indexFromTimestamp(timestamp));
        }
        return new DeserializingMessage(message, ignored -> null, messageType, null, null);
    }

    static class CommandHandler {
        @HandleCommand
        void handle() {
        }
    }

    static class SkippingCommandHandler {
        @HandleCommand(skipExpiredRequests = true)
        void handle() {
        }
    }

    static class QueryHandler {
        @HandleQuery
        void handle() {
        }
    }

    static class TypedQueryHandler {
        @HandleQuery
        void handle(FooQuery query) {
        }
    }

    static class FooQuery {
    }

    static class BarQuery {
    }

    static class NonSkippingQueryHandler {
        @HandleQuery(skipExpiredRequests = false)
        void handle() {
        }
    }

    static class NonSkippingWebHandler {
        @HandleGet(value = "/test", skipExpiredRequests = false)
        void handle() {
        }
    }

    static class WebHandler {
        @HandleGet("/test")
        void handle() {
        }
    }

    static class SocketHandler {
        @HandleSocketMessage("/socket")
        void handle() {
        }
    }
}
