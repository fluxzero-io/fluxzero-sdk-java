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
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.tracking.handling.HandlerRegistry;
import io.fluxzero.sdk.tracking.handling.ResponseMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultGenericGatewayTest {

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

    private static DefaultGenericGateway gateway(GatewayClient gatewayClient) {
        Client client = mock(Client.class);
        when(client.namespace()).thenReturn(null);
        when(client.forNamespace(null)).thenReturn(client);
        return gateway(client, gatewayClient, HandlerRegistry.noOp());
    }

    private static DefaultGenericGateway gateway(Client client, GatewayClient gatewayClient,
                                                 HandlerRegistry handlerRegistry) {
        return new DefaultGenericGateway(
                client,
                gatewayClient,
                mock(RequestHandler.class),
                new JacksonSerializer(),
                DispatchInterceptor.noOp,
                MessageType.COMMAND,
                null,
                handlerRegistry,
                mock(ResponseMapper.class));
    }
}
