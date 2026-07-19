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
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.publishing.ResultGateway;
import io.fluxzero.sdk.tracking.handling.MessageParameterResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.fluxzero.common.Guarantee.NONE;
import static io.fluxzero.sdk.common.ClientUtils.setConsumerNamespace;
import static io.fluxzero.sdk.web.HttpRequestMethod.WS_OPEN;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsocketHandlerDecoratorTest {

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
}
