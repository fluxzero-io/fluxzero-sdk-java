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
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerConfiguration;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.tracking.handling.MessageParameterResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    private static class SocketEndpoint {
        @HandleSocketMessage("/socket")
        void onMessage() {
        }
    }
}
