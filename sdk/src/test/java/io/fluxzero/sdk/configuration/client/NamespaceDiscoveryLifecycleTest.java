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

package io.fluxzero.sdk.configuration.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NamespaceDiscoveryLifecycleTest {
    @Test
    void shutdownCanCloseDiscoveryWhileSessionCreationBlocks() throws Exception {
        var client = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost:1").name("discovery").disableMetrics(true).build());
        var transport = mock(WebsocketNamespaceClient.class);
        var field = WebSocketClient.class.getDeclaredField("namespaceClient");
        field.setAccessible(true);
        field.set(client, transport);
        var entered = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        when(transport.getNamespaces()).thenAnswer(ignored -> {
            entered.countDown();
            assertTrue(released.await(5, TimeUnit.SECONDS));
            return CompletableFuture.completedFuture(List.of());
        });
        doAnswer(ignored -> { released.countDown(); return null; }).when(transport).close();
        var completion = new CompletableFuture<Void>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                client.getNamespaces().join();
                completion.complete(null);
            } catch (Throwable e) {
                completion.completeExceptionally(e);
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var closed = new CompletableFuture<Void>();
            Thread.ofVirtual().start(() -> {
                try { client.shutDown(); closed.complete(null); }
                catch (Throwable e) { closed.completeExceptionally(e); }
            });
            closed.get(5, TimeUnit.SECONDS);
            completion.get(5, TimeUnit.SECONDS);
            verify(transport).close();
        } finally {
            released.countDown();
            worker.join(5000);
            client.shutDown();
        }
    }
}
