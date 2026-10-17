/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.web;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.TaskScheduler;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerConfiguration;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.handling.MethodInvocationValidator;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.publishing.ResultGateway;
import io.fluxzero.sdk.tracking.handling.DefaultHandlerFactory;
import io.fluxzero.sdk.tracking.handling.MessageParameterResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.fluxzero.common.Guarantee.NONE;
import static io.fluxzero.sdk.common.ClientUtils.setConsumerNamespace;
import static io.fluxzero.sdk.web.HttpRequestMethod.GET;
import static io.fluxzero.sdk.web.HttpRequestMethod.OPTIONS;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_HANDSHAKE;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_OPEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsocketHandlerDecoratorTest {

    @Test
    void automaticHandshakeFilterPreservesRawPathsAndOriginIndependentMatching() {
        var serializer = new JacksonSerializer();
        var handler = handler(new ParameterEndpoint(), new WebsocketHandlerDecorator(null, serializer, null));
        var filter = handler.getConsumerFilter();
        assertNotNull(filter);
        assertTrue(filter.routeRestricted());
        for (String url : List.of("/socket/a%2Fb", "/socket/a+b", "https://example.com/socket/a%252Fb",
                                   "https://[::1]:8443/socket/%E2%82%AC", "/elsewhere")) {
            var request = WebRequest.builder().method(WS_HANDSHAKE).url(url).build().serialize(serializer);
            boolean handled = handler.getInvoker(serializer.deserializeMessage(request, MessageType.WEBREQUEST))
                    .isPresent();
            assertEquals(!url.equals("/elsewhere"), handled);
            assertEquals(handled, filter.compile().test(request), url);
        }
        var legacy = WebRequest.builder().method(WS_HANDSHAKE).url("/elsewhere").build().serialize(serializer);
        assertFalse(filter.compile().test(legacy));
        legacy.setRoute(null);
        assertTrue(filter.compile().test(legacy), "legacy requests must remain eligible without a route");
        for (String method : List.of(GET, OPTIONS)) {
            var request = WebRequest.builder().method(method).url("/socket/a").build().serialize(serializer);
            assertTrue(handler.getInvoker(serializer.deserializeMessage(request, MessageType.WEBREQUEST)).isEmpty());
            assertFalse(filter.compile().test(request), "socket routes do not create automatic HTTP OPTIONS");
        }
    }

    @Test
    void customSocketDecoratorRetainsUnknownSelection() {
        var handler = handler(new ParameterEndpoint(), new WebsocketHandlerDecorator(null, null, null) {});
        assertNull(handler.getConsumerFilter());
    }

    @Test
    void consumerProjectionRetainsHandshakeWhenAnotherFactoryAlreadyRegisteredItsPath() {
        var serializer = new JacksonSerializer();
        var decorator = new WebsocketHandlerDecorator(null, serializer, null);
        var local = handler(new ParameterEndpoint(), decorator);
        var tracked = handler(new ParameterEndpoint(), decorator);
        var request = WebRequest.builder().method(WS_HANDSHAKE).url("/socket/a").build().serialize(serializer);
        assertTrue(local.getInvoker(serializer.deserializeMessage(request, MessageType.WEBREQUEST)).isPresent());
        assertTrue(tracked.getConsumerFilter().routeRestricted());
        assertTrue(tracked.getConsumerFilter().compile().test(request),
                   "the tracking projection must admit a handshake owned by the local handler factory");
    }

    private static Handler<DeserializingMessage> handler(Object target, WebsocketHandlerDecorator decorator) {
        return new DefaultHandlerFactory(MessageType.WEBREQUEST, decorator, List.of(new MessageParameterResolver()),
                MethodInvocationValidator.noOp(), ignored -> null, null, false, new JacksonSerializer())
                .createHandler(target, HandlerFilter.ALWAYS_HANDLE, List.of()).orElseThrow();
    }

    @Test
    void lifecycleDecoratorsDoNotExposeReusableHandlerMethodThatCouldBypassSocketState() {
        DeserializingMessage message = new DeserializingMessage(new Message("payload"), MessageType.WEBREQUEST, null);
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new SocketEndpoint(), List.of(new MessageParameterResolver()),
                HandlerConfiguration.<DeserializingMessage>builder().methodAnnotation(HandleWeb.class).build());

        assertNotNull(handler.getHandlerMethodOrNull(message));

        Handler<DeserializingMessage> wrapped =
                new WebsocketHandlerDecorator(null, null, null).lifecycleDecorator().wrap(handler);

        assertNull(wrapped.getHandlerMethodOrNull(message));
    }

    @Test
    void socketSessionResponsesUseRequestNamespace() {
        ResultGateway defaultGateway = mock(ResultGateway.class);
        ResultGateway tenantGateway = mock(ResultGateway.class);
        ResultGateway otherGateway = mock(ResultGateway.class);
        when(defaultGateway.forNamespace("tenant")).thenReturn(tenantGateway);
        when(defaultGateway.forNamespace("other")).thenReturn(otherGateway);
        WebsocketHandlerDecorator decorator = new WebsocketHandlerDecorator(
                defaultGateway, new JacksonSerializer(), mock(TaskScheduler.class));

        DefaultSocketSession tenantSession = decorator.getOrCreateSocketSession(request("tenant"));
        DefaultSocketSession otherSession = decorator.getOrCreateSocketSession(request("other"));
        tenantSession.sendMessage("hello", NONE);
        otherSession.sendMessage("hello", NONE);

        assertNotSame(tenantSession, otherSession);
        verify(tenantGateway).respond(eq("hello"), any(Metadata.class), any(), any(), eq(NONE));
        verify(otherGateway).respond(eq("hello"), any(Metadata.class), any(), any(), eq(NONE));
    }

    private static DeserializingMessage request(String namespace) {
        WebRequest request = WebRequest.builder().method(WS_OPEN).url("/socket")
                .metadata(Metadata.of("sessionId", "shared-session")).build();
        return setConsumerNamespace(new DeserializingMessage(
                request, MessageType.WEBREQUEST, new JacksonSerializer()), namespace);
    }

    private static class SocketEndpoint {
        @HandleSocketMessage("/socket")
        void onMessage() {
        }
    }

    private static class ParameterEndpoint {
        @HandleSocketMessage("/socket/{id}")
        void onMessage() {}
    }
}
