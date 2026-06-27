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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultGenericGatewayTest {

    @Test
    void sendAndForgetRegistersAppendFutureWithActiveCompletionScope() throws Exception {
        GatewayClient gatewayClient = mock(GatewayClient.class);
        CompletableFuture<Void> appendCompletion = new CompletableFuture<>();
        CountDownLatch appendStarted = new CountDownLatch(1);
        when(gatewayClient.append(eq(Guarantee.STORED), any(SerializedMessage[].class))).thenAnswer(invocation -> {
            appendStarted.countDown();
            return appendCompletion;
        });
        DefaultGenericGateway gateway = gateway(gatewayClient);

        CompletableFuture<Void> scopedCompletion = CompletableFuture.runAsync(
                () -> AsyncCompletionScope.runAndAwait(
                        () -> gateway.sendAndForget(Guarantee.STORED, new Message("command"))));

        assertTrue(appendStarted.await(1, TimeUnit.SECONDS));
        assertFalse(scopedCompletion.isDone());

        appendCompletion.complete(null);

        assertDoesNotThrow(() -> scopedCompletion.get(1, TimeUnit.SECONDS));
    }

    @Test
    void closeCompletesPendingRequestCallbacks() {
        RequestHandler requestHandler = mock(RequestHandler.class);
        CompletableFuture<SerializedMessage> pendingResponse = new CompletableFuture<>();
        when(requestHandler.sendRequest(any(SerializedMessage.class), any(), any(Duration.class))).thenReturn(
                pendingResponse);
        DefaultGenericGateway gateway = gateway(mock(GatewayClient.class), requestHandler);

        CompletableFuture<Message> result = gateway.sendForMessage(new Message("query"), Duration.ofSeconds(60));

        gateway.close();

        ExecutionException error = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    private static DefaultGenericGateway gateway(GatewayClient gatewayClient) {
        return gateway(gatewayClient, mock(RequestHandler.class));
    }

    private static DefaultGenericGateway gateway(GatewayClient gatewayClient, RequestHandler requestHandler) {
        Client client = mock(Client.class);
        when(client.namespace()).thenReturn(null);
        return new DefaultGenericGateway(
                client,
                gatewayClient,
                requestHandler,
                new JacksonSerializer(),
                DispatchInterceptor.noOp,
                MessageType.COMMAND,
                null,
                HandlerRegistry.noOp(),
                mock(ResponseMapper.class));
    }
}
