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
 *
 */

package io.fluxzero.sdk.tracking.handling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.HasMetadata;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerInspector;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.sdk.common.HasMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.ChunkedDeserializingMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Parameter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PayloadParameterResolverTest {

    private final PayloadParameterResolver resolver = new PayloadParameterResolver();

    @Test
    void preparedResolverReusesPayloadFetchedDuringCompatibilityCheck() throws Exception {
        CountingMessage message = new CountingMessage("value", String.class);
        Parameter parameter = parameter("handleString");

        Function<HasMessage, Object> prepared = resolver.resolveIfPossible(parameter, null, message);

        assertEquals(1, message.payloadCalls);
        assertSame(message.payload, prepared.apply(message));
        assertEquals(1, message.payloadCalls);
    }

    @Test
    void preparedResolverKeepsSerializedPayloadLazyUntilInvocation() throws Exception {
        AtomicInteger payloadCalls = new AtomicInteger();
        DeserializingMessage message = new DeserializingMessage(serializedStringMessage(),
                                                                type -> {
                                                                    payloadCalls.incrementAndGet();
                                                                    return "value";
                                                                }, MessageType.EVENT, null, new JacksonSerializer());

        Function<HasMessage, Object> prepared = resolver.resolveIfPossible(parameter("handleString"), null, message);

        assertNotNull(prepared);
        assertEquals(0, payloadCalls.get());
        assertEquals("value", prepared.apply(message));
        assertEquals(1, payloadCalls.get());
    }

    @Test
    void preparedResolverRejectsNullPayloadForNonNullableParameter() throws Exception {
        CountingMessage message = new CountingMessage(null, String.class);

        Function<HasMessage, Object> prepared = resolver.resolveIfPossible(parameter("handleString"), null, message);

        assertNull(prepared);
        assertEquals(1, message.payloadCalls);
    }

    @Test
    void preparedResolverAllowsNullPayloadForNullableParameter() throws Exception {
        CountingMessage message = new CountingMessage(null, String.class);

        Function<HasMessage, Object> prepared = resolver.resolveIfPossible(parameter("handleNullableString"), null,
                                                                           message);

        assertEquals(1, message.payloadCalls);
        assertNull(prepared.apply(message));
        assertEquals(1, message.payloadCalls);
    }

    @Test
    void preparedResolverKeepsChunkedTypedPayloadsLazy() throws Exception {
        Function<HasMessage, Object> prepared = resolver.resolveIfPossible(
                parameter("handleString"), null, new PayloadFailingChunkedMessage());

        assertNotNull(prepared);
    }

    @Test
    void handlerSelectionKeepsSerializedPayloadLazyUntilInvocation() {
        AtomicInteger payloadCalls = new AtomicInteger();
        DeserializingMessage message = new DeserializingMessage(serializedStringMessage(),
                                                                type -> {
                                                                    payloadCalls.incrementAndGet();
                                                                    return "value";
                                                                }, MessageType.EVENT, null, new JacksonSerializer());
        RecordingPayloadHandler target = new RecordingPayloadHandler();
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                target, HandleEvent.class, java.util.List.of(new PayloadParameterResolver()));

        HandlerInvoker invoker = handler.getInvokerOrNull(message);

        assertNotNull(invoker);
        assertEquals(0, payloadCalls.get());
        invoker.invoke();
        assertEquals("value", target.payload);
        assertEquals(1, payloadCalls.get());
    }

    @Test
    void keyedHandlerSelectionAlwaysResolvesPayloadFromCurrentMessage() {
        ReturningPayloadHandler target = new ReturningPayloadHandler();
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                target, HandleEvent.class, java.util.List.of(new PayloadParameterResolver()));
        DeserializingMessage first = new DeserializingMessage(
                new Message("first"), MessageType.EVENT, new JacksonSerializer());
        DeserializingMessage second = new DeserializingMessage(
                new Message("second"), MessageType.EVENT, new JacksonSerializer());

        assertEquals("first", handler.getInvokerOrNull(first).invoke());
        assertEquals("second", handler.getInvokerOrNull(second).invoke());
    }

    @Test
    void cacheKeyChangesWhenLazyPayloadDeserializesToNull() {
        DeserializingMessage message = new DeserializingMessage(
                serializedStringMessage(), type -> null, MessageType.EVENT, null, new JacksonSerializer());
        Handler<DeserializingMessage> handler = HandlerInspector.createHandler(
                new RecordingPayloadHandler(), HandleEvent.class, java.util.List.of(new PayloadParameterResolver()));

        HandlerInvoker initialInvoker = handler.getInvokerOrNull(message);

        assertNotNull(initialInvoker);
        initialInvoker.invoke();
        assertNull(handler.getInvokerOrNull(message));
    }

    private static SerializedMessage serializedStringMessage() {
        return new SerializedMessage(
                new Data<>("\"value\"".getBytes(), String.class.getName(), 0, Data.JSON_FORMAT),
                Metadata.empty(),
                "message-id",
                0L);
    }

    private static Parameter parameter(String methodName) throws NoSuchMethodException {
        return HandlerFixture.class.getDeclaredMethod(methodName, String.class).getParameters()[0];
    }

    @SuppressWarnings("unused")
    private static class HandlerFixture {
        void handleString(String payload) {
        }

        void handleNullableString(@Nullable String payload) {
        }
    }

    private static class RecordingPayloadHandler {
        private String payload;

        @HandleEvent
        void handle(String payload) {
            this.payload = payload;
        }
    }

    private static class ReturningPayloadHandler {
        @HandleEvent
        String handle(String payload) {
            return payload;
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.PARAMETER, ElementType.TYPE_USE})
    private @interface Nullable {
    }

    private static class CountingMessage implements HasMessage {
        private final Object payload;
        private final Class<?> payloadClass;
        private int payloadCalls;

        private CountingMessage(Object payload, Class<?> payloadClass) {
            this.payload = payload;
            this.payloadClass = payloadClass;
        }

        @Override
        public Message toMessage() {
            return new Message(payload);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> R getPayload() {
            payloadCalls++;
            return (R) payload;
        }

        @Override
        public Class<?> getPayloadClass() {
            return payloadClass;
        }

        @Override
        public Metadata getMetadata() {
            return Metadata.empty();
        }
    }

    private static class PayloadFailingChunkedMessage extends ChunkedDeserializingMessage {
        private PayloadFailingChunkedMessage() {
            super(new SerializedMessage(
                    new Data<>("part".getBytes(), String.class.getName(), 0, Data.JSON_FORMAT),
                    Metadata.of(HasMetadata.FINAL_CHUNK, false, HasMetadata.CHUNK_INDEX, 0),
                    "chunked-message",
                    0L), MessageType.EVENT, null, new JacksonSerializer());
        }

        @Override
        public <V> V getPayload() {
            throw new AssertionError("Chunked typed payload should remain lazy during resolver preparation");
        }

        @Override
        public Class<?> getPayloadClass() {
            return String.class;
        }
    }
}
