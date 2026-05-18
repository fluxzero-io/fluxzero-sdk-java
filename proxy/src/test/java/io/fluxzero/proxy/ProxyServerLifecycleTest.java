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

package io.fluxzero.proxy;

import io.fluxzero.common.TestUtils;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.web.WebRequest;
import io.fluxzero.sdk.web.WebResponse;
import io.fluxzero.testserver.TestServer;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static io.fluxzero.sdk.web.HttpRequestMethod.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
class ProxyServerLifecycleTest {

    @Test
    void startWithoutArgumentsStartsHttpAndForwardProxyUsingConfiguredProperties() throws Exception {
        Server testServer = null;
        ProxyServer proxyServer = null;
        Fluxzero requester = null;
        ServerSocket occupiedProxyPort = null;
        String previousFluxzeroProxyPort = System.getProperty("FLUXZERO_PROXY_PORT");
        String previousProxyPort = System.getProperty("PROXY_PORT");
        String previousFluxzeroBaseUrl = System.getProperty("FLUXZERO_BASE_URL");
        String previousFluxBaseUrl = System.getProperty("FLUX_BASE_URL");
        String previousFluxUrl = System.getProperty("FLUX_URL");
        try {
            int fluxPort = TestUtils.getAvailablePort();
            String runtimeUrl = "ws://localhost:" + fluxPort;
            testServer = TestServer.startServer(fluxPort);
            occupiedProxyPort = new ServerSocket(0);

            System.setProperty("FLUXZERO_PROXY_PORT", "0");
            System.setProperty("PROXY_PORT", String.valueOf(occupiedProxyPort.getLocalPort()));
            System.setProperty("FLUXZERO_BASE_URL", runtimeUrl);
            System.clearProperty("FLUX_BASE_URL");
            System.clearProperty("FLUX_URL");

            proxyServer = ProxyServer.start();

            assertTrue(proxyServer.getPort() > 0);
            assertNotEquals(occupiedProxyPort.getLocalPort(), proxyServer.getPort());

            String healthUrl = "http://localhost:" + proxyServer.getPort() + "/proxy/health";
            WebSocketClient.ClientConfig requesterConfig = WebSocketClient.ClientConfig.builder()
                    .name("proxy-lifecycle-test")
                    .runtimeBaseUrl(runtimeUrl)
                    .build();
            requester = DefaultFluxzero.builder()
                    .disableAutomaticTracking()
                    .disableShutdownHook()
                    .disableKeepalive()
                    .build(WebSocketClient.newInstance(requesterConfig));
            WebResponse response = requester.webRequestGateway().sendAndWait(WebRequest.builder()
                                                                                       .url(healthUrl)
                                                                                       .method(GET)
                                                                                       .build());
            assertEquals(200, response.getStatus());
            assertEquals("Healthy", new String(response.<byte[]>getPayload(), StandardCharsets.UTF_8));

            proxyServer.cancel();
            proxyServer.cancel();
        } finally {
            if (requester != null) {
                requester.close(true);
            }
            if (proxyServer != null) {
                proxyServer.cancel();
            }
            if (occupiedProxyPort != null) {
                occupiedProxyPort.close();
            }
            if (testServer != null) {
                testServer.stop();
            }
            restoreProperty("FLUXZERO_PROXY_PORT", previousFluxzeroProxyPort);
            restoreProperty("PROXY_PORT", previousProxyPort);
            restoreProperty("FLUXZERO_BASE_URL", previousFluxzeroBaseUrl);
            restoreProperty("FLUX_BASE_URL", previousFluxBaseUrl);
            restoreProperty("FLUX_URL", previousFluxUrl);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
