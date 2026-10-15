/*
 * Copyright (c) Fluxzero IP or its affiliates. All Rights Reserved.
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

import io.fluxzero.common.api.tracking.ConsumerFilter;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static io.fluxzero.common.MessageType.EVENT;
import static org.junit.jupiter.api.Assertions.*;

class HandlerFilterPropagationTest {
    private final ConsumerFilter filter = ConsumerFilter.select(null, 0L, ConsumerFilter.VERSION, null,
            List.of(new ConsumerFilter.PayloadType(String.class.getName(), null)), null, List.of(), false);
    private final HandlerInvoker invoker = HandlerInvoker.noOp();
    private final Handler<DeserializingMessage> delegate = new Handler<>() {
        @Override public Class<?> getTargetClass() { return getClass(); }
        @Override public ConsumerFilter getConsumerFilter() { return filter; }
        @Override public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
            return message.getPayloadClass() == String.class ? Optional.of(invoker) : Optional.empty();
        }
    };

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void standardInterceptorsPreserveTheirDelegateSelection(boolean prepared) {
        HandlerInterceptor interceptor = (function, handler) -> function;
        var handler = prepared ? new HandlerInterceptor.PreparedInterceptedHandler(delegate, interceptor)
                : new HandlerInterceptor.InterceptedHandler(delegate, interceptor);
        assertSame(filter, handler.getConsumerFilter());
        assertTrue(handler.getInvoker(message("value")).isPresent());
        assertTrue(handler.getInvoker(message(123)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void customInterceptorWrappersMayAcceptAdditionalPayloads(boolean prepared) {
        HandlerInterceptor interceptor = (function, handler) -> function;
        var handler = prepared ? new HandlerInterceptor.PreparedInterceptedHandler(delegate, interceptor) {
            @Override public HandlerInvoker getInvokerOrNull(DeserializingMessage message) { return invoker; }
        } : new HandlerInterceptor.InterceptedHandler(delegate, interceptor) {
            @Override public HandlerInvoker getInvokerOrNull(DeserializingMessage message) { return invoker; }
        };
        assertTrue(handler.getInvoker(message(123)).isPresent());
        assertNull(handler.getConsumerFilter());
    }

    @Test
    void ordinaryDecoratorPreservesSelectionAndInvocation() {
        var handler = new DocumentHandlerDecorator(() -> null).wrap(delegate);
        assertSame(filter, handler.getConsumerFilter());
        assertSame(invoker, handler.getInvoker(message("value")).orElseThrow());
        assertTrue(handler.getInvoker(message(123)).isEmpty());
    }

    @Test
    void customWrapperCanWidenSelectionWithoutInheritingANarrowFilter() {
        var decorator = new DocumentHandlerDecorator(() -> null) {
            @Override
            public Handler<DeserializingMessage> wrap(Handler<DeserializingMessage> handler) {
                return new DocumentHandler(handler) {
                    @Override
                    public Optional<HandlerInvoker> getInvoker(DeserializingMessage message) {
                        return Optional.of(invoker);
                    }
                };
            }
        };
        var handler = decorator.wrap(delegate);
        assertNull(handler.getConsumerFilter());
        assertTrue(handler.getInvoker(message(123)).isPresent());
    }

    private static DeserializingMessage message(Object payload) {
        return new DeserializingMessage(new Message(payload), EVENT, new JacksonSerializer());
    }
}
