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
package io.fluxzero.testserver;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.websocket.WebSocketTransportFormat;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.HandleSocketMessage;
import io.fluxzero.sdk.web.HandleSocketOpen;
import io.fluxzero.sdk.web.HandleSocketPong;
import io.fluxzero.sdk.web.Path;
import io.fluxzero.sdk.web.ServeStatic;
import io.fluxzero.sdk.web.SocketSession;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.fluxzero.common.MessageType.WEBREQUEST;
import static io.fluxzero.sdk.web.HttpRequestMethod.*;
import static io.fluxzero.sdk.tracking.BatchInterceptor.preservingConsumerFilter;
import static org.junit.jupiter.api.Assertions.*;

class ConsumerWebRouteContractTest {
    @ParameterizedTest
    @MethodSource("modes")
    void filtersStaticAndHttpRoutesBeforeDeliveryAndKeepsSocketExpansionEligible(
            WebSocketTransportFormat format, boolean mounted) throws Exception {
        var server = TestServer.startServer(0);
        int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
        var delivered = new LinkedBlockingQueue<String>();
        var configuration = new AtomicReference<ConsumerConfiguration>();
        var client = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost:" + port).namespace(UUID.randomUUID().toString())
                .name("web-route-contract").id(UUID.randomUUID().toString())
                .disableMetrics(true).supportedTransportFormats(List.of(format)).build());
        try (Fluxzero app = DefaultFluxzero.builder().disableShutdownHook().disableKeepalive().disableTrackingMetrics()
                .addBatchInterceptor(preservingConsumerFilter((consumer, tracker) -> {
                    configuration.set(tracker.getConfiguration());
                    return batch -> {
                        consumer.accept(batch);
                        batch.getMessages().forEach(m -> delivered.add(
                                m.getMetadata().get("method") + " " + m.getMetadata().get("url")));
                    };
                }), WEBREQUEST).build(client)) {
            app.registerHandlers(mounted ? new MountedSite() : new Site());
            assertTrue(configuration.get().getConsumerBlueprint().getFilter().routeRestricted());
            String root = mounted ? "/site" : "";
            var accepted = List.of(message(GET, root + "/api/ping"), message(GET, root + "/index.html"),
                    message(HEAD, root + "/index.html"), message(OPTIONS, root + "/index.html"),
                    message(GET, root + "/deep/spa/path"));
            var input = new ArrayList<SerializedMessage>();
            input.add(message(POST, root + "/index.html"));
            if (mounted) input.add(message(GET, "/outside"));
            input.addAll(accepted);
            app.client().getGatewayClient(WEBREQUEST).append(Guarantee.STORED,
                    input.toArray(SerializedMessage[]::new)).join();
            assertDelivered(delivered, accepted);

            // Registration must add socket lifecycle inputs to the open read without losing HTTP selection.
            app.registerHandlers(new SocketHandler());
            assertTrue(configuration.get().getConsumerBlueprint().getFilter().routeRestricted());
            var frames = List.of(message(WS_HANDSHAKE, "/socket"), message(WS_OPEN, "/socket"),
                    message(WS_MESSAGE, "/socket"), message(WS_PONG, "/socket"),
                    message(WS_CLOSE, "/unmatched-close-path"));
            var socketInput = new ArrayList<SerializedMessage>();
            socketInput.add(message(POST, root + "/index.html"));
            if (mounted) socketInput.add(message(GET, "/outside"));
            socketInput.add(message(WS_HANDSHAKE, "/elsewhere"));
            socketInput.addAll(frames);
            app.client().getGatewayClient(WEBREQUEST).append(Guarantee.STORED,
                    socketInput.toArray(SerializedMessage[]::new)).join();
            assertDelivered(delivered, frames);
        } finally {
            client.shutDown();
            server.stop();
        }
    }

    static Stream<Arguments> modes() {
        return Stream.of(WebSocketTransportFormat.values())
                .flatMap(format -> Stream.of(false, true).map(mounted -> Arguments.of(format, mounted)));
    }

    private static void assertDelivered(LinkedBlockingQueue<String> delivered, List<SerializedMessage> expected)
            throws InterruptedException {
        for (var message : expected) {
            assertEquals(message.getMetadata().get("method") + " " + message.getMetadata().get("url"),
                         delivered.poll(10, TimeUnit.SECONDS));
        }
        assertTrue(delivered.isEmpty());
    }

    private static SerializedMessage message(String method, String url) {
        return io.fluxzero.sdk.web.WebRequestRoute.update(new SerializedMessage(new Data<>(new byte[0], null, 0, "unknown"),
                Metadata.of("method", method, "url", url, "sessionId", "contract-session"),
                UUID.randomUUID().toString(), System.currentTimeMillis()));
    }

    @Consumer(name = "web-filter-contract", minIndex = 0)
    @ServeStatic(resourcePath = "classpath:/web/filter-static")
    static class Site { @HandleGet("api/ping") void ping() {} }
    @Path("/site") @ServeStatic(resourcePath = "classpath:/web/filter-static")
    static class MountedSite extends Site {}
    @Consumer(name = "web-filter-contract", minIndex = 0)
    static class SocketHandler {
        @HandleSocketOpen("/socket") void open(SocketSession session) {}
        @HandleSocketMessage("/socket") void message() {}
        @HandleSocketPong("/socket") void pong() {}
    }
}
