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

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.tracking.handling.HandlerRegistry;
import io.fluxzero.sdk.tracking.handling.LocalHandlerResult;
import io.fluxzero.sdk.tracking.handling.Request;
import io.fluxzero.sdk.tracking.handling.ResponseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RequestPublicationTest {
    @Test
    void directGatewayUsesSentWithoutAnOptIn() {
        try (var fixture = new Fixture()) {
            var gateway = new DefaultGenericGateway(fixture.client, fixture.transport, fixture.handler,
                    new JacksonSerializer(), DispatchInterceptor.noOp, MessageType.COMMAND, null,
                    HandlerRegistry.noOp(), mock(ResponseMapper.class));
            var result = gateway.sendForMessage(new Message("request"), null);
            assertEquals(Guarantee.SENT, fixture.publications.getFirst().guarantee());
            fixture.publications.getFirst().acknowledgment()
                    .completeExceptionally(new IllegalStateException("publication rejected"));
            assertThrows(CompletionException.class, result::join);
            gateway.close();
        }
    }

    @Test
    void singleRequestRetainsVirtualDispatchAndIntermediateAggregation() throws Exception {
        try (var fixture = new Fixture()) {
            var handler = spy(fixture.handler);
            var serializer = new JacksonSerializer();
            var request = new Message("request").serialize(serializer);
            var result = handler.sendRequest(request, ignored -> {}, Duration.ofSeconds(30));
            verify(handler).sendRequest(eq(request), any(), eq(Duration.ofSeconds(30)), any());
            var first = new Message("first").serialize(serializer);
            first.setRequestId(request.getRequestId());
            first.setMetadata(first.getMetadata().with(io.fluxzero.common.api.HasMetadata.FINAL_CHUNK, false));
            var last = new Message("last").serialize(serializer);
            last.setRequestId(request.getRequestId());
            handler.handleResults(List.of(first, last));
            assertArrayEquals(io.fluxzero.common.ObjectUtils.join(first.data().getValue(), last.data().getValue()),
                              result.get(2, TimeUnit.SECONDS).data().getValue());
            assertFalse(handler.failPending(request.getRequestId()));
            handler.close();
        }
    }

    @ParameterizedTest
    @EnumSource(value = Guarantee.class, names = {"NONE", "SENT", "STORED"})
    void requestUsesSharedDefaultAndResponseDoesNotWaitForAck(Guarantee configuredDefault) throws Exception {
        try (var fixture = new Fixture()) {
            fixture.gateway.withDefaultGuarantee(configuredDefault);
            var result = fixture.gateway.sendForMessage(new Message("request"), null);
            var publication = fixture.publications.getFirst();
            assertEquals(configuredDefault, publication.guarantee());
            assertFalse(result.isDone());
            fixture.handler.respond(publication.messages()[0]);
            assertEquals("response", result.get(2, TimeUnit.SECONDS).getPayload());
            assertFalse(publication.acknowledgment().isDone());
            publication.acknowledgment().completeExceptionally(new IllegalStateException("late ack failure"));
            assertEquals("response", result.join().getPayload());
            assertEquals(1, fixture.publications.size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void requestPublicationFailureRemovesExistingResponseCallback(boolean synchronous) {
        try (var fixture = new Fixture()) {
            var failure = new IllegalStateException("append rejected");
            if (synchronous) {
                doAnswer(invocation -> {
                    fixture.failedRequest = ((SerializedMessage[]) invocation.getRawArguments()[1])[0];
                    throw failure;
                }).when(fixture.transport).append(any(), any(SerializedMessage[].class));
            }
            var result = fixture.gateway.sendForMessage(new Message("request"), Duration.ofSeconds(30));
            var request = synchronous ? fixture.failedRequest : fixture.publications.getFirst().messages()[0];
            if (!synchronous) {
                fixture.publications.getFirst().acknowledgment().completeExceptionally(failure);
            }
            assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
            assertFalse(fixture.handler.failPending(request.getRequestId()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "uniform", "mixed"})
    void batchesPreserveTimeoutsOrderAndIndependentResponses(String timeoutMode) throws Exception {
        try (var fixture = new Fixture()) {
            var results = fixture.gateway.sendForMessages(messages(timeoutMode));
            assertEquals(timeoutMode.equals("mixed") ? 2 : 1, fixture.publications.size());
            var requests = fixture.requests();
            assertEquals(2, requests.size());
            String expectedFirst = timeoutMode.equals("default") ? "200000" : "30000";
            assertEquals(expectedFirst, requests.getFirst().getMetadata().get(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY));
            assertEquals(timeoutMode.equals("mixed") ? "31000" : expectedFirst,
                         requests.getLast().getMetadata().get(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY));
            fixture.publications.forEach(p -> assertEquals(Guarantee.STORED, p.guarantee()));
            fixture.handler.respond(requests.getLast());
            assertEquals("response", results.getLast().get(2, TimeUnit.SECONDS).getPayload());
            assertFalse(results.getFirst().isDone());
            var failure = new IllegalStateException("append rejected");
            fixture.publications.forEach(p -> p.acknowledgment().completeExceptionally(failure));
            assertSame(failure, assertThrows(CompletionException.class, results.getFirst()::join).getCause());
            assertEquals("response", results.getLast().join().getPayload());
            requests.forEach(r -> assertFalse(fixture.handler.failPending(r.getRequestId())));
        }
    }

    @Test
    void deferredCustomRequestHandlerStillObservesPublicationFailure() {
        try (var fixture = new Fixture()) {
            var custom = mock(RequestHandler.class);
            var sender = new AtomicReference<Consumer<SerializedMessage>>();
            var serializedRequest = new AtomicReference<SerializedMessage>();
            var response = new CompletableFuture<SerializedMessage>();
            when(custom.sendRequest(any(), any())).thenAnswer(invocation -> {
                serializedRequest.set(invocation.getArgument(0));
                sender.set(invocation.getArgument(1));
                return response;
            });
            var gateway = fixture.gateway(custom, HandlerRegistry.noOp(), DispatchInterceptor.noOp);
            var result = gateway.sendForMessage(new Message("request"), null);
            assertTrue(fixture.publications.isEmpty());
            sender.get().accept(serializedRequest.get());
            var failure = new IllegalStateException("deferred append rejected");
            fixture.publications.getFirst().acknowledgment().completeExceptionally(failure);
            assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
            assertTrue(response.isCompletedExceptionally());
        }
    }

    @Test
    void localAndSuppressedRequestsKeepTheirExistingPaths() throws Exception {
        try (var fixture = new Fixture()) {
            var local = mock(HandlerRegistry.class);
            when(local.handleResult(any(), eq(false)))
                    .thenReturn(LocalHandlerResult.asynchronous(CompletableFuture.completedFuture("local")));
            var localGateway = fixture.gateway(fixture.handler, local, DispatchInterceptor.noOp);
            assertEquals("local", localGateway.sendForMessage(new Message(new LocalCommand()))
                    .get(2, TimeUnit.SECONDS).getPayload());
            var suppress = mock(DispatchInterceptor.class);
            var suppressedGateway = fixture.gateway(fixture.handler, HandlerRegistry.noOp(), suppress);
            assertNull(suppressedGateway.sendForMessage(new Message("suppressed"))
                    .get(2, TimeUnit.SECONDS).getPayload());
            assertTrue(fixture.publications.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(value = Guarantee.class, names = {"DEFAULT", "NONE", "SENT", "STORED"})
    void perCallGuaranteeReachesBuiltInGatewaysWithoutWaitingForStorage(Guarantee guarantee) {
        try (var fixture = new Fixture()) {
            fixture.onAppend = fixture.handler::respond;
            CommandGateway commands = new DefaultCommandGateway(fixture.gateway);
            QueryGateway queries = new DefaultQueryGateway(fixture.gateway);
            Request<String> typed = new TypedRequest("value");
            assertEquals("response", commands.send(typed, Metadata.empty(), guarantee).join());
            assertEquals("response", commands.send(typed, Metadata.of("tag", "value"), guarantee).join());
            assertEquals("response", commands.sendAndWait(typed, Metadata.empty(), guarantee));
            assertEquals("response", commands.sendAndWait((Object) typed, Metadata.empty(), guarantee));
            assertEquals("response", queries.send(typed, Metadata.empty(), guarantee).join());
            assertEquals("response", queries.sendAndWait(typed, Metadata.empty(), guarantee));
            assertEquals("response", commands.sendForMessages(guarantee, new Message(typed)).getFirst().join().getPayload());
            assertEquals("response", queries.sendForMessages(guarantee, new Message(typed)).getFirst().join().getPayload());
            commands.sendForMessages(guarantee, new Message("first"), new Message("second"))
                    .forEach(f -> assertEquals("response", f.join().getPayload()));
            queries.sendForMessages(guarantee, new Message("first"), new Message("second"))
                    .forEach(f -> assertEquals("response", f.join().getPayload()));
            var timeoutRequest = fixture.gateway.sendForMessage(new Message("timed"),
                                                                Duration.ofSeconds(7), guarantee);
            assertEquals("response", timeoutRequest.join().getPayload());
            assertEquals("7000", fixture.requests().getLast().getMetadata()
                    .get(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY));
            fixture.publications.forEach(publication -> {
                assertEquals(guarantee == Guarantee.DEFAULT ? Guarantee.STORED : guarantee,
                             publication.guarantee());
                assertFalse(publication.acknowledgment().isDone(), "Business responses must not wait for receipts");
            });
            // One override must not leak into the next call's application policy.
            commands.send("default after explicit override").join();
            assertEquals(Guarantee.STORED, fixture.publications.getLast().guarantee());
        }
    }

    @Test
    void deferredRequestsRetainTheirOwnGuarantee() {
        try (var fixture = new Fixture()) {
            RequestHandler custom = mock(RequestHandler.class);
            List<Runnable> appendLater = new ArrayList<>();
            when(custom.sendRequest(any(), any())).thenAnswer(invocation -> {
                SerializedMessage message = invocation.getArgument(0);
                Consumer<SerializedMessage> sender = invocation.getArgument(1);
                appendLater.add(() -> sender.accept(message));
                return new CompletableFuture<SerializedMessage>();
            });
            var gateway = fixture.gateway(custom, HandlerRegistry.noOp(), DispatchInterceptor.noOp);
            var sent = gateway.sendForMessage(new Message("sent"), null, Guarantee.SENT);
            var stored = gateway.sendForMessage(new Message("stored"), null, Guarantee.STORED);
            // A custom handler may postpone publication and invoke callbacks in another order.
            appendLater.getLast().run();
            appendLater.getFirst().run();
            assertEquals(List.of(Guarantee.STORED, Guarantee.SENT),
                         fixture.publications.stream().map(Publication::guarantee).toList());
            var failure = new IllegalStateException("publication failed");
            fixture.publications.forEach(p -> p.acknowledgment().completeExceptionally(failure));
            assertSame(failure, assertThrows(CompletionException.class, stored::join).getCause());
            assertSame(failure, assertThrows(CompletionException.class, sent::join).getCause());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"uniform", "mixed"})
    void perCallGuaranteeSurvivesMixedTimeoutsAndChunkedPublication(String timeouts) {
        try (var fixture = new Fixture()) {
            int size = timeouts.equals("mixed") ? 2 : 8193;
            Message[] input = new Message[size];
            for (int i = 0; i < size; i++) input[i] = new Message("request-" + i)
                    .addMetadata(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY,
                                 timeouts.equals("mixed") ? String.valueOf(30000 + i) : "30000");
            var results = fixture.gateway.sendForMessages(Guarantee.SENT, input);
            assertEquals(size, fixture.requests().size());
            fixture.publications.forEach(p -> assertEquals(Guarantee.SENT, p.guarantee()));
            var actual = fixture.requests();
            for (int i = 0; i < size; i++) {
                assertEquals(input[i].getMessageId(), actual.get(i).getMessageId());
            }
            fixture.handler.respond(fixture.requests().getLast());
            assertEquals("response", results.getLast().join().getPayload());
            assertFalse(results.getFirst().isDone());
        }
    }

    @Test
    void invalidGuaranteesCannotInvokeLocalHandlersOrPublish() {
        try (var fixture = new Fixture()) {
            HandlerRegistry local = mock(HandlerRegistry.class);
            DispatchInterceptor interceptor = mock(DispatchInterceptor.class);
            var gateway = fixture.gateway(fixture.handler, local, interceptor);
            assertThrows(NullPointerException.class,
                         () -> gateway.sendForMessage(new Message("request"), Duration.ZERO, null));
            verifyNoInteractions(local, interceptor);
            assertTrue(fixture.publications.isEmpty());
        }
    }

    @Test
    void defaultOverloadsPreserveCustomGatewayEntryPointsAndRejectUnsupportedOverrides() throws Exception {
        for (Class<?> gatewayType : List.of(GenericGateway.class, CommandGateway.class, QueryGateway.class,
                                            DefaultCommandGateway.class, DefaultQueryGateway.class)) {
            Object gateway = mock(gatewayType, invocation -> {
                var method = invocation.getMethod();
                if (java.util.Arrays.asList(method.getParameterTypes()).contains(Guarantee.class)) {
                    return invocation.callRealMethod();
                }
                String marker = method.toGenericString();
                if (method.getReturnType() == CompletableFuture.class) {
                    return CompletableFuture.completedFuture(marker);
                }
                if (method.getReturnType() == List.class) {
                    return List.of(CompletableFuture.completedFuture(marker));
                }
                return marker;
            });
            for (String name : List.of("send", "sendAndWait")) {
                for (Class<?> payloadType : List.of(Object.class, Request.class)) {
                    var old = gatewayType.getMethod(name, payloadType, Metadata.class);
                    var added = gatewayType.getMethod(name, payloadType, Metadata.class, Guarantee.class);
                    Object payload = payloadType == Object.class ? "payload" : new TypedRequest("payload");
                    Object actual = added.invoke(gateway, payload, Metadata.empty(), Guarantee.DEFAULT);
                    assertEquals(old.toGenericString(), actual instanceof CompletableFuture<?> f ? f.join() : actual);
                    if (gatewayType.isInterface()) {
                        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                                () -> added.invoke(gateway, payload, Metadata.empty(), Guarantee.STORED));
                        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
                    }
                }
            }
            var oldBatch = gatewayType.getMethod("sendForMessages", Message[].class);
            var newBatch = gatewayType.getMethod("sendForMessages", Guarantee.class, Message[].class);
            @SuppressWarnings("unchecked") var batch = (List<CompletableFuture<?>>) newBatch.invoke(
                    gateway, Guarantee.DEFAULT, new Message[]{new Message("request")});
            assertEquals(oldBatch.toGenericString(), batch.getFirst().join());
        }
        GenericGateway generic = mock(GenericGateway.class, CALLS_REAL_METHODS);
        Message request = new Message("request");
        var result = CompletableFuture.completedFuture(new Message("custom"));
        doReturn(result).when(generic).sendForMessage(request, Duration.ofSeconds(2));
        assertSame(result, generic.sendForMessage(request, Duration.ofSeconds(2), Guarantee.DEFAULT));
        assertThrows(UnsupportedOperationException.class,
                     () -> generic.sendForMessage(request, Duration.ofSeconds(2), Guarantee.STORED));
    }

    private record TypedRequest(String value) implements Request<String> {}

    private static Message[] messages(String timeoutMode) {
        var first = new Message("first");
        var second = new Message("second");
        if (!timeoutMode.equals("default")) {
            first = first.addMetadata(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY, "30000");
            second = second.addMetadata(RequestHandler.REQUEST_TIMEOUT_METADATA_KEY,
                                        timeoutMode.equals("mixed") ? "31000" : "30000");
        }
        return new Message[]{first, second};
    }

    @LocalOnly
    private record LocalCommand() {}

    private record Publication(Guarantee guarantee, SerializedMessage[] messages,
                               CompletableFuture<Void> acknowledgment) {}

    private static class Fixture implements AutoCloseable {
        private final Client client = mock(Client.class);
        private final GatewayClient transport = mock(GatewayClient.class);
        private final List<Publication> publications = new ArrayList<>();
        private final RecordingHandler handler;
        private final DefaultGenericGateway gateway;
        private SerializedMessage failedRequest;
        private Consumer<SerializedMessage> onAppend = ignored -> {};

        private Fixture() {
            when(client.forNamespace(null)).thenReturn(client);
            when(client.id()).thenReturn("request-publication-test");
            when(transport.append(any(), any(SerializedMessage[].class))).thenAnswer(invocation -> {
                var acknowledgment = new CompletableFuture<Void>();
                publications.add(new Publication(invocation.getArgument(0),
                        (SerializedMessage[]) invocation.getRawArguments()[1], acknowledgment));
                for (SerializedMessage message : (SerializedMessage[]) invocation.getRawArguments()[1]) {
                    onAppend.accept(message);
                }
                return acknowledgment;
            });
            handler = new RecordingHandler(client);
            gateway = gateway(handler, HandlerRegistry.noOp(), DispatchInterceptor.noOp);
        }

        private DefaultGenericGateway gateway(RequestHandler requestHandler, HandlerRegistry local,
                                              DispatchInterceptor interceptor) {
            var mapper = mock(ResponseMapper.class);
            when(mapper.map(any())).thenAnswer(invocation -> new Message(invocation.getArgument(0)));
            return new DefaultGenericGateway(client, transport, requestHandler, new JacksonSerializer(), interceptor,
                                             MessageType.COMMAND, null, local, mapper).withDefaultGuarantee(Guarantee.STORED);
        }

        private List<SerializedMessage> requests() {
            return publications.stream().flatMap(p -> java.util.Arrays.stream(p.messages())).toList();
        }

        @Override
        public void close() {
            handler.close();
            gateway.close();
        }
    }

    private static class RecordingHandler extends DefaultRequestHandler {
        private RecordingHandler(Client client) {
            super(client, MessageType.RESULT);
        }

        @Override
        protected void ensureStarted() {
            // Deliver controlled responses directly while exercising real correlation and timeout cleanup.
        }

        private void respond(SerializedMessage request) {
            var response = new Message("response").serialize(new JacksonSerializer());
            response.setRequestId(request.getRequestId());
            handleResults(List.of(response));
        }

        private boolean failPending(int requestId) {
            return completeRequestExceptionally(requestId, new IllegalStateException("test cleanup"));
        }
    }
}
