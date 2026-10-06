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

package io.fluxzero.testserver;

import io.fluxzero.common.websocket.WebSocketTransportFormat;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NamespaceDiscoveryTest {
    @ParameterizedTest
    @EnumSource(WebSocketTransportFormat.class)
    void discoversInactiveNamespacesWithoutCreatingTheQueryNamespace(WebSocketTransportFormat format) throws Exception {
        Server server = TestServer.startServer(new InetSocketAddress("127.0.0.1", 0));
        int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
        var config = WebSocketClient.ClientConfig.builder().runtimeBaseUrl("ws://127.0.0.1:" + port)
                .name("namespace-test").namespace("discovery-only").disableMetrics(true)
                .supportedTransportFormats(List.of(format)).build();
        var client = WebSocketClient.newInstance(config);
        try {
            assertFalse(client.getNamespaces().get(5, TimeUnit.SECONDS).contains("discovery-only"));
            var writer = client.forNamespace("tenant-b");
            writer.getKeyValueClient().getValue("missing");
            client.forNamespace("tenant-a").getKeyValueClient().getValue("missing");
            writer.shutDown();
            var namespaces = client.getNamespaces().get(5, TimeUnit.SECONDS);
            assertTrue(namespaces.containsAll(List.of("tenant-a", "tenant-b")));
            assertFalse(namespaces.contains("discovery-only"));
            assertEquals(namespaces.stream().distinct().sorted().toList(), namespaces);
            assertEquals(namespaces, client.forNamespace("tenant-a").getNamespaces().get(5, TimeUnit.SECONDS));
            client.shutDown();
            assertThrows(java.util.concurrent.ExecutionException.class,
                         () -> client.getNamespaces().get(5, TimeUnit.SECONDS));
        } finally {
            client.shutDown();
            server.stop();
        }
    }
}
