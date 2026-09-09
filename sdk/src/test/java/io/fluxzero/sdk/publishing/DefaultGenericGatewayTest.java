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
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.AsyncCompletionScope;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.Serializer;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.tracking.handling.HandlerRegistry;
import io.fluxzero.sdk.tracking.handling.LocalHandlerResult;
import io.fluxzero.sdk.tracking.handling.LocalHandlerSelection;
import io.fluxzero.sdk.tracking.handling.ResponseMapper;
import io.fluxzero.sdk.tracking.handling.authentication.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultGenericGatewayTest {

    @ParameterizedTest
    @ValueSource(ints = {255, 256, 512, 8193})
    void sendAndForgetPreservesSerializationContext(int batchSize) {
        assertSerializationContext(batchSize, false);
    }

    @ParameterizedTest
    @ValueSource(ints = {255, 256, 512, 8193})
    void sendForMessagesPreservesSerializationContext(int batchSize) {
        assertSerializationContext(batchSize, true);
    }

    private void assertSerializationContext(int batchSize, boolean requestResults) {
        Client client = mock(Client.class);
        when(client.forNamespace(null)).thenReturn(client);
        GatewayClient gatewayClient = mock(GatewayClient.class);
        List<SerializedMessage> published = new ArrayList<>();
        when(gatewayClient.append(any(Guarantee.class), any(SerializedMessage[].class))).thenAnswer(invocation -> {
            published.addAll(Arrays.asList((SerializedMessage[]) invocation.getRawArguments()[1]));
            return CompletableFuture.completedFuture(null);
        });
        RequestHandler requestHandler = mock(RequestHandler.class);
        SerializedMessage response = new Message("ok").serialize(new JacksonSerializer());
        when(requestHandler.sendRequests(anyList(), any())).thenAnswer(invocation -> {
            List<SerializedMessage> requests = invocation.getArgument(0);
            published.addAll(requests);
            return requests.stream().map(ignored -> CompletableFuture.completedFuture(response)).toList();
        });
        DefaultGenericGateway gateway = new DefaultGenericGateway(
                client, gatewayClient, requestHandler, new JacksonSerializer(), DispatchInterceptor.noOp,
                MessageType.COMMAND, null, HandlerRegistry.noOp(), mock(ResponseMapper.class));
        Message[] batch = IntStream.range(0, batchSize).mapToObj(ignored -> new Message(new ContextPayload()))
                .toArray(Message[]::new);
        User user = new User() {
            @Override
            public String getName() {
                return "tenant-a";
            }

            @Override
            public boolean hasRole(String role) {
                return false;
            }
        };
        Runnable send = () -> {
            if (requestResults) {
                gateway.sendForMessages(batch).forEach(CompletableFuture::join);
            } else {
                gateway.sendAndForget(Guarantee.STORED, batch).join();
            }
        };

        user.run(send::run);
        assertEquals(batchSize, published.size());
        published.forEach(message -> assertEquals("{\"user\":\"tenant-a\"}",
                                                 new String(message.getData().getValue(), StandardCharsets.UTF_8)));
        assertNull(User.getCurrent());
        published.clear();
        send.run();
        assertEquals(batchSize, published.size());
        published.forEach(message -> assertEquals("{\"user\":\"none\"}",
                                                 new String(message.getData().getValue(), StandardCharsets.UTF_8)));
    }

    static class ContextPayload {
        public String getUser() {
            User user = User.getCurrent();
            return user == null ? "none" : user.getName();
        }
    }

    @Test
    void parallelSendAndForgetRetainsChunkBoundariesAndOrder() {
        GatewayClient gatewayClient = mock(GatewayClient.class);
        when(gatewayClient.append(eq(Guarantee.STORED), any(SerializedMessage[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        DefaultGenericGateway gateway = gateway(gatewayClient);
        Message[] messages = IntStream.range(0, 8_193)
                .mapToObj(index -> new Message("command-" + index))
                .toArray(Message[]::new);

        gateway.sendAndForget(Guarantee.STORED, messages).join();

        ArgumentCaptor<SerializedMessage[]> chunks = ArgumentCaptor.forClass(SerializedMessage[].class);
        verify(gatewayClient, times(2)).append(eq(Guarantee.STORED), chunks.capture());
        assertEquals(8_192, chunks.getAllValues().getFirst().length);
        assertEquals(1, chunks.getAllValues().get(1).length);
        assertEquals("\"command-0\"", new String(
                chunks.getAllValues().getFirst()[0].getData().getValue(), StandardCharsets.UTF_8));
        assertEquals("\"command-8192\"", new String(
                chunks.getAllValues().get(1)[0].getData().getValue(), StandardCharsets.UTF_8));
    }

    @Test
    void sendAndForgetRegistersAppendFutureWithActiveCompletionScope() throws Exception {
        GatewayClient gatewayClient = mock(GatewayClient.class);
        CompletableFuture<Void> appendCompletion = new CompletableFuture<>();
        when(gatewayClient.append(eq(Guarantee.STORED), any(SerializedMessage[].class))).thenReturn(
                appendCompletion);
        DefaultGenericGateway gateway = gateway(gatewayClient);

        CompletableFuture<Void> scopedCompletion = CompletableFuture.runAsync(
                () -> AsyncCompletionScope.runAndAwait(
                        () -> gateway.sendAndForget(Guarantee.STORED, new Message("command"))));

        TimeUnit.MILLISECONDS.sleep(50L);
        assertFalse(scopedCompletion.isDone());

        appendCompletion.complete(null);

        assertDoesNotThrow(() -> scopedCompletion.get(1, TimeUnit.SECONDS));
    }

    @Test
    void customNamespaceInvokesLocalHandlers() {
        Client applicationClient = mock(Client.class);
        Client customClient = mock(Client.class);
        GatewayClient gatewayClient = mock(GatewayClient.class);
        HandlerRegistry localHandlers = mock(HandlerRegistry.class);
        when(customClient.namespace()).thenReturn("tenant");
        when(customClient.forNamespace(null)).thenReturn(applicationClient);
        when(gatewayClient.append(eq(Guarantee.STORED), any(SerializedMessage[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(localHandlers.handle(any())).thenReturn(Optional.of(CompletableFuture.completedFuture(null)));
        DefaultGenericGateway gateway = gateway(customClient, gatewayClient, localHandlers);

        gateway.sendAndForget(Guarantee.STORED, new Message("command")).join();

        verify(localHandlers).handle(any());
        verify(gatewayClient, never()).append(eq(Guarantee.STORED), any(SerializedMessage[].class));
    }

    @Test
    void localOnlyDispatchFailsBeforeMonitoringSerializationOrPublication() {
        Client client = mock(Client.class);
        GatewayClient gatewayClient = mock(GatewayClient.class);
        HandlerRegistry localHandlers = mock(HandlerRegistry.class);
        Serializer serializer = mock(Serializer.class);
        DispatchInterceptor interceptor = mock(DispatchInterceptor.class);
        Message message = new Message(new LocalOnlyCommand());
        when(client.namespace()).thenReturn(null);
        when(client.forNamespace(null)).thenReturn(client);
        when(interceptor.interceptDispatch(message, MessageType.COMMAND, null, null)).thenReturn(message);
        when(localHandlers.selectSingleHandler(any())).thenReturn(LocalHandlerSelection.noMatch());
        DefaultGenericGateway gateway = gateway(client, gatewayClient, localHandlers, serializer, interceptor);

        assertThrows(LocalOnlyDispatchException.class, () -> gateway.sendAndWait(message));

        verify(interceptor, never()).monitorDispatch(any(), eq(MessageType.COMMAND), isNull(), isNull(), anyBoolean());
        verify(interceptor, never()).modifySerializedMessage(any(), any(), any(), any());
        verify(serializer, never()).serialize(any(), any());
        verify(gatewayClient, never()).append(any(), any(SerializedMessage[].class));
    }

    @Test
    void customRegistryMustExplicitlySupportExactLocalSelection() {
        HandlerRegistry customRegistry = mock(HandlerRegistry.class, CALLS_REAL_METHODS);
        when(customRegistry.handle(any())).thenReturn(Optional.of(CompletableFuture.completedFuture("unsafe")));
        DefaultGenericGateway gateway = gateway(mock(GatewayClient.class), customRegistry);

        LocalOnlyDispatchException error = assertThrows(
                LocalOnlyDispatchException.class, () -> gateway.sendAndWait(new LocalOnlyCommand()));

        assertTrue(error.getMessage().contains("cannot guarantee exact local selection"));
        verify(customRegistry, never()).handle(any());
    }

    @Test
    void invalidCustomSelectionCannotFallBackToExternalDispatch() {
        GatewayClient gatewayClient = mock(GatewayClient.class);
        HandlerRegistry customRegistry = mock(HandlerRegistry.class);
        when(customRegistry.selectSingleHandler(any())).thenReturn(
                LocalHandlerSelection.selected(LocalHandlerResult::notHandled));
        DefaultGenericGateway gateway = gateway(gatewayClient, customRegistry);

        LocalOnlyDispatchException error = assertThrows(
                LocalOnlyDispatchException.class, () -> gateway.sendAndWait(new LocalOnlyCommand()));

        assertTrue(error.getMessage().contains("selected local handler did not accept the message"));
        verify(gatewayClient, never()).append(any(), any(SerializedMessage[].class));
    }

    private static DefaultGenericGateway gateway(GatewayClient gatewayClient) {
        Client client = mock(Client.class);
        when(client.namespace()).thenReturn(null);
        when(client.forNamespace(null)).thenReturn(client);
        return gateway(client, gatewayClient, HandlerRegistry.noOp());
    }

    private static DefaultGenericGateway gateway(GatewayClient gatewayClient, HandlerRegistry handlerRegistry) {
        Client client = mock(Client.class);
        when(client.namespace()).thenReturn(null);
        when(client.forNamespace(null)).thenReturn(client);
        return gateway(client, gatewayClient, handlerRegistry);
    }

    private static DefaultGenericGateway gateway(Client client, GatewayClient gatewayClient,
                                                 HandlerRegistry handlerRegistry) {
        return gateway(client, gatewayClient, handlerRegistry, new JacksonSerializer(), DispatchInterceptor.noOp);
    }

    private static DefaultGenericGateway gateway(Client client, GatewayClient gatewayClient,
                                                 HandlerRegistry handlerRegistry, Serializer serializer,
                                                 DispatchInterceptor dispatchInterceptor) {
        return new DefaultGenericGateway(
                client,
                gatewayClient,
                mock(RequestHandler.class),
                serializer,
                dispatchInterceptor,
                MessageType.COMMAND,
                null,
                handlerRegistry,
                mock(ResponseMapper.class));
    }

    @LocalOnly
    private record LocalOnlyCommand() {
    }
}
