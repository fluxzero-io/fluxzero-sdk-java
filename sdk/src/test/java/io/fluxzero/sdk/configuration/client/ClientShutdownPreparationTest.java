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

package io.fluxzero.sdk.configuration.client;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class ClientShutdownPreparationTest {
    @Test
    void directShutdownPreparesExistingAndLateNamespacesBeforeCallbacks() {
        WebSocketClient client = websocketClient();
        WebSocketClient child = (WebSocketClient) client.forNamespace("child");
        AtomicInteger callbacks = new AtomicInteger();
        client.beforeShutdown(() -> {
            assertTrue(client.isShuttingDown());
            assertTrue(child.isShuttingDown());
            assertTrue(((WebSocketClient) client.forNamespace("late")).isShuttingDown());
            callbacks.incrementAndGet();
        });
        client.shutDown();
        client.shutDown();
        assertEquals(1, callbacks.get());
    }

    @Test
    void failedCustomPreparationDoesNotSkipWebsocketCleanup() {
        WebSocketClient client = spy(websocketClient());
        doThrow(new IllegalStateException("custom preparation failed")).when(client).prepareShutdown();
        AtomicInteger callbacks = new AtomicInteger();
        client.beforeShutdown(callbacks::incrementAndGet);
        assertDoesNotThrow(client::shutDown);
        assertEquals(1, callbacks.get());
        assertTrue(client.getNamespaces().isCompletedExceptionally());
    }

    @Test
    void failedCustomPreparationDoesNotSkipApplicationOrClientCallbacks() {
        LocalClient client = spy(LocalClient.newInstance(null));
        doThrow(new IllegalStateException("custom preparation failed")).when(client).prepareShutdown();
        Fluxzero fluxzero = DefaultFluxzero.builder().disableAutomaticTracking().disableTrackingMetrics()
                .disableCacheEvictionMetrics().disableApplicationLifecycleMetrics()
                .disableShutdownHook().disableKeepalive().build(client);
        AtomicInteger callbacks = new AtomicInteger();
        fluxzero.beforeShutdown(callbacks::incrementAndGet);
        client.beforeShutdown(callbacks::incrementAndGet);
        assertDoesNotThrow(() -> fluxzero.close(true));
        assertEquals(2, callbacks.get());
    }

    private static WebSocketClient websocketClient() {
        return WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost:1").name("shutdown").disableMetrics(true).build());
    }
}
