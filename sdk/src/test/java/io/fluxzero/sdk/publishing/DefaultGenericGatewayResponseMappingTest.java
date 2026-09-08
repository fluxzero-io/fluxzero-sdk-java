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

package io.fluxzero.sdk.publishing;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.ObjectUtils;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.SerializedObject;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.tracking.handling.HandlerRegistry;
import io.fluxzero.sdk.tracking.handling.ResponseMapper;
import io.fluxzero.sdk.web.WebResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultGenericGatewayResponseMappingTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulResponseRetainsMetadataNullPayloadAndCompletionContext(boolean completed)
            throws InterruptedException {
        for (MessageType type : List.of(MessageType.COMMAND, MessageType.WEBREQUEST)) {
            for (Object payload : new Object[]{"result", null}) {
                SerializedMessage response = response();
                if (type == MessageType.WEBREQUEST) {
                    response.setMetadata(response.getMetadata().with(WebResponse.statusKey, 201)
                            .with(WebResponse.headersKey, Map.of("X-Response", List.of("value"))));
                }
                CompletableFuture<SerializedMessage> raw = raw(response, completed);
                ThreadLocal<String> context = new ThreadLocal<>();
                AtomicInteger calls = new AtomicInteger();
                AtomicReference<Thread> mappingThread = new AtomicReference<>();
                DefaultGenericGateway gateway = gateway(type, raw, response, () -> {
                    assertEquals(completed ? "registration" : "completion", context.get());
                    mappingThread.set(Thread.currentThread());
                    calls.incrementAndGet();
                    return payload;
                });
                try {
                    context.set("registration");
                    CompletableFuture<Message> result = gateway.sendForMessage(new Message("request"));
                    assertEquals(completed, result.isDone());
                    Thread completionThread = Thread.ofPlatform().unstarted(() -> {
                        context.set("completion");
                        try {
                            raw.complete(response);
                        } finally {
                            context.remove();
                        }
                    });
                    completionThread.start();
                    completionThread.join();
                    Message message = result.join();
                    assertEquals(payload, message.getPayload());
                    assertEquals(response.getMetadata(), message.getMetadata());
                    assertEquals(type == MessageType.WEBREQUEST, message instanceof WebResponse);
                    if (message instanceof WebResponse webResponse) {
                        assertEquals(201, webResponse.getStatus());
                        assertEquals(List.of("value"), webResponse.getHeaders().get("X-Response"));
                    }
                    assertSame(completed ? Thread.currentThread() : completionThread, mappingThread.get());
                    assertEquals(1, calls.get());
                } finally {
                    context.remove();
                    gateway.close();
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void returnedFailuresRetainTheExistingRelayContract(boolean completed) {
        RuntimeException secondary = new IllegalStateException("toString failure");
        List<Throwable> failures = List.of(
                new IllegalArgumentException("ordinary"), new Exception("checked"), new Throwable("base"),
                new CompletionException(new Exception("wrapped")), new CompletionException((Throwable) null),
                new CancellationException("cancelled"), new AssertionError("error"),
                new RuntimeException() {
                    @Override
                    public String toString() {
                        throw secondary;
                    }
                }, new CancellationException() {
                    @Override
                    public String toString() {
                        throw secondary;
                    }
                });
        for (Throwable failure : failures) {
            SerializedMessage response = response();
            CompletableFuture<SerializedMessage> raw = raw(response, completed);
            DefaultGenericGateway gateway = gateway(MessageType.COMMAND, raw, response, () -> failure);
            try {
                CompletableFuture<Message> actual = assertDoesNotThrow(
                        () -> gateway.sendForMessage(new Message("request")));
                raw.complete(response);
                // Keep the old expression as the oracle for JDK-dependent Throwable wrapping.
                CompletableFuture<Message> expected = CompletableFuture.completedFuture(response)
                        .thenCompose(ignored -> CompletableFuture.failedFuture(failure));
                assertEquivalentFailure(expected, actual);
                if (failure instanceof CompletionException) {
                    assertSame(failure, assertThrows(CompletionException.class, actual::join));
                }
            } finally {
                gateway.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void thrownSerializerFailuresRemainExceptionalFutures(boolean completed) {
        for (Throwable failure : List.of(new IllegalStateException("serializer"),
                new Exception("checked serializer"), new AssertionError("serializer error"))) {
            SerializedMessage response = response();
            CompletableFuture<SerializedMessage> raw = raw(response, completed);
            DefaultGenericGateway gateway = gateway(MessageType.COMMAND, raw, response,
                    () -> ObjectUtils.forceThrow(failure));
            try {
                CompletableFuture<Message> actual = assertDoesNotThrow(
                        () -> gateway.sendForMessage(new Message("request")));
                raw.complete(response);
                CompletableFuture<Message> expected = CompletableFuture.completedFuture(response)
                        .thenCompose(ignored -> {
                            try {
                                ObjectUtils.forceThrow(failure);
                                throw new AssertionError("unreachable");
                            } catch (Exception caught) {
                                return CompletableFuture.failedFuture(caught);
                            }
                        });
                assertEquivalentFailure(expected, actual);
            } finally {
                gateway.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void upstreamFailureSkipsDeserializationAndDownstreamCancellationDoesNotPropagate(boolean cancelled) {
        SerializedMessage response = response();
        CompletableFuture<SerializedMessage> raw = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        DefaultGenericGateway gateway = gateway(MessageType.COMMAND, raw, response, calls::incrementAndGet);
        try {
            if (cancelled) {
                raw.cancel(false);
            } else {
                raw.completeExceptionally(new IllegalStateException("upstream"));
            }
            CompletableFuture<Message> failed = gateway.sendForMessage(new Message("request"));
            assertTrue(failed.isCompletedExceptionally());
            assertFalse(failed.isCancelled());
            assertEquals(0, calls.get());
        } finally {
            gateway.close();
        }
        raw = new CompletableFuture<>();
        gateway = gateway(MessageType.COMMAND, raw, response, calls::incrementAndGet);
        try {
            CompletableFuture<Message> result = gateway.sendForMessage(new Message("request"));
            assertTrue(result.cancel(false));
            assertFalse(raw.isDone());
            raw.complete(response);
            assertTrue(result.isCancelled());
            assertEquals(1, calls.get());
        } finally {
            gateway.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bulkResponsesKeepIndependentSuccessAndFailure(boolean completed) {
        SerializedMessage response = response();
        CompletableFuture<SerializedMessage> raw = raw(response, completed);
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("one response");
        DefaultGenericGateway gateway = gateway(MessageType.COMMAND, raw, response,
                () -> calls.getAndIncrement() == 0 ? failure : "ok");
        try {
            List<CompletableFuture<Message>> results = gateway.sendForMessages(IntStream.range(0, 256)
                    .mapToObj(i -> new Message("request-" + i)).toArray(Message[]::new));
            assertEquals(256, results.size());
            raw.complete(response);
            assertEquals(1, results.stream().filter(CompletableFuture::isCompletedExceptionally).count());
            results.stream().filter(f -> !f.isCompletedExceptionally())
                    .forEach(f -> assertEquals("ok", f.join().getPayload()));
            assertEquals(256, calls.get());
        } finally {
            gateway.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void responseStagesPreserveTheRequestHandlersFutureSubtype(boolean completed) {
        SerializedMessage response = response();
        ResponseFuture<SerializedMessage> raw = new ResponseFuture<>();
        if (completed) {
            raw.complete(response);
        }
        DefaultGenericGateway gateway = gateway(MessageType.COMMAND, raw, response, () -> "ok");
        try {
            CompletableFuture<Message> result = gateway.sendForMessage(new Message("request"));
            assertInstanceOf(ResponseFuture.class, result);
            assertEquals(completed, result.isDone());
            raw.complete(response);
            assertEquals("ok", result.join().getPayload());
        } finally {
            gateway.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyCaughtSerializerExceptionsAreLogged(boolean completed) {
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultGenericGateway.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int scenario = 0; scenario < 4; scenario++) {
                appender.list.clear();
                SerializedMessage response = response();
                boolean invalidStatus = scenario == 3;
                if (invalidStatus) {
                    response.setMetadata(response.getMetadata().with(WebResponse.statusKey, "invalid"));
                }
                Supplier<?> mapping = switch (scenario) {
                    case 0 -> () -> new IllegalArgumentException("returned");
                    case 1 -> () -> ObjectUtils.forceThrow(new IllegalArgumentException("thrown"));
                    case 2 -> () -> ObjectUtils.forceThrow(new AssertionError("thrown"));
                    default -> () -> "ok";
                };
                CompletableFuture<SerializedMessage> raw = raw(response, completed);
                DefaultGenericGateway gateway = gateway(
                        invalidStatus ? MessageType.WEBREQUEST : MessageType.COMMAND, raw, response, mapping);
                try {
                    CompletableFuture<Message> result = assertDoesNotThrow(
                            () -> gateway.sendForMessage(new Message("request")));
                    raw.complete(response);
                    assertTrue(result.isDone());
                    Throwable cause = assertThrows(CompletionException.class, result::join).getCause();
                    if (invalidStatus) {
                        assertInstanceOf(NumberFormatException.class, cause);
                    }
                    assertEquals(scenario == 1 ? 1 : 0, appender.list.stream().filter(
                            e -> e.getMessage().startsWith("Failed to deserialize result")).count());
                } finally {
                    gateway.close();
                }
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static class ResponseFuture<T> extends CompletableFuture<T> {
        @Override
        public <U> CompletableFuture<U> newIncompleteFuture() {
            return new ResponseFuture<>();
        }
    }

    private static void assertEquivalentFailure(CompletableFuture<?> expected, CompletableFuture<?> actual) {
        assertTrue(actual.isDone());
        assertTrue(actual.isCompletedExceptionally());
        assertEquals(expected.isCancelled(), actual.isCancelled());
        CompletionException e = assertThrows(CompletionException.class, expected::join);
        CompletionException a = assertThrows(CompletionException.class, actual::join);
        assertSame(e.getCause(), a.getCause());
        assertArrayEquals(e.getSuppressed(), a.getSuppressed());
        assertSame(assertThrows(ExecutionException.class, expected::get).getCause(),
                assertThrows(ExecutionException.class, actual::get).getCause());
    }

    private static CompletableFuture<SerializedMessage> raw(SerializedMessage response, boolean completed) {
        return completed ? CompletableFuture.completedFuture(response) : new CompletableFuture<>();
    }

    private static SerializedMessage response() {
        return new SerializedMessage(new Data<>(new byte[0], "response", 0),
                Metadata.of("response-key", "value"), "response", 1L);
    }

    private static DefaultGenericGateway gateway(MessageType type, CompletableFuture<SerializedMessage> raw,
                                                 SerializedMessage response, Supplier<?> mapping) {
        Client client = mock(Client.class);
        when(client.forNamespace(null)).thenReturn(client);
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.sendRequest(any(), any())).thenReturn(raw);
        when(handler.sendRequests(anyList(), any())).thenAnswer(invocation -> {
            List<?> requests = invocation.getArgument(0);
            return requests.stream().map(ignored -> raw).toList();
        });
        JacksonSerializer serializer = new JacksonSerializer() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(SerializedObject<byte[]> data) {
                return data == response ? (T) mapping.get() : super.deserialize(data);
            }
        };
        return new DefaultGenericGateway(client, mock(GatewayClient.class), handler, serializer,
                DispatchInterceptor.noOp, type, null, HandlerRegistry.noOp(), mock(ResponseMapper.class));
    }
}
