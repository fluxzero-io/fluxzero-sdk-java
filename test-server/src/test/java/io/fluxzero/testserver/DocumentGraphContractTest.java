/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.testserver;

import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.test.contracts.DocumentGraphContract;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.CONCURRENT)
@Order(2) // Start expensive classes early in the shared parallel suite.
class DocumentGraphContractTest extends DocumentGraphContract {
    private static Server server;
    private static int port;

    @BeforeAll
    static void start() {
        server = TestServer.startServer(new java.net.InetSocketAddress("127.0.0.1", 0));
        port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @AfterAll
    static void stop() throws Exception { server.stop(); }

    @Override
    protected Client[] clients(String namespace) {
        return new Client[]{client(namespace), client(namespace)};
    }

    private static Client client(String namespace) {
        return new WebSocketClient(WebSocketClient.ClientConfig.builder().name("document-contract")
                .namespace(namespace).runtimeBaseUrl("ws://127.0.0.1:" + port).disableMetrics(true).build(), null) {
            @Override
            protected Client createForNamespace(String requested) {
                // The shared suite's manually dispatched messages use this test's default namespace.
                return requested == null ? this : super.createForNamespace(requested);
            }
        };
    }
}
