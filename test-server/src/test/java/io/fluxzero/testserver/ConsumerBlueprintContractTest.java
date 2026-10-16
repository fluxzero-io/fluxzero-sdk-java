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
import io.fluxzero.common.tracking.WebSocketTracker;
import io.fluxzero.common.websocket.WebSocketTransportFormat;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.casting.Upcast;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.tracking.Consumer;
import io.fluxzero.sdk.tracking.handling.HandleEvent;
import io.fluxzero.sdk.web.HandleGet;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.MessageType.WEBREQUEST;
import static org.junit.jupiter.api.Assertions.*;

class ConsumerBlueprintContractTest {
    @ParameterizedTest
    @EnumSource(WebSocketTransportFormat.class)
    void filtersBeforeTransportAndRefreshesOpenReadsForNewHandlersAndUpcasters(WebSocketTransportFormat format)
            throws Exception {
        List<WebSocketTracker> reads = new CopyOnWriteArrayList<>();
        BlockingQueue<WebSocketTracker> references = new LinkedBlockingQueue<>();
        var server = TestServer.startServer(0, tracker -> {
            reads.add(tracker);
            if (tracker.getConsumerFilter() != null && tracker.getConsumerFilter().referenceOnly()) {
                references.add(tracker);
            }
        });
        int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
        List<SerializedMessage> delivered = new CopyOnWriteArrayList<>();
        var client = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost:" + port).namespace(UUID.randomUUID().toString())
                .name("blueprint-contract").id(UUID.randomUUID().toString()).disableMetrics(true)
                .supportedTransportFormats(List.of(format)).build()
                .withTrackingConfig(EVENT, WebSocketClient.TrackingClientConfig.builder().sessions(2).build()));
        try (Fluxzero app = DefaultFluxzero.builder().disableShutdownHook().disableKeepalive().disableTrackingMetrics()
                .addBatchInterceptor((consumer, tracker) -> batch -> {
                    delivered.addAll(batch.getMessages());
                    consumer.accept(batch);
                }, EVENT, WEBREQUEST).build(client)) {
            var first = new FirstHandler();
            app.registerHandlers(first);
            // Partitioning and multiple sessions exercise the effective-consumer scope of the server cache.
            app.client().getGatewayClient(EVENT).append(Guarantee.STORED,
                    event(First.class.getName(), 0), event(First.class.getName(), 127)).join();
            assertNotNull(first.received.poll(10, TimeUnit.SECONDS));
            assertNotNull(first.received.poll(10, TimeUnit.SECONDS));
            assertNotNull(references.poll(10, TimeUnit.SECONDS));
            assertTrue(reads.stream().anyMatch(r -> r.maxTimeout() == 900_000));
            assertTrue(reads.stream().filter(r -> r.getConsumerFilter() != null)
                    .allMatch(r -> (r.maxTimeout() == 900_000 || r.maxTimeout() == 0) && r.maxScanLookBack() == 60_000));

            var second = new SecondHandler();
            app.registerHandlers(second);
            app.serializer().registerUpcasters(new LegacyCaster());
            var relevant = event("LegacySecond", 0);
            app.client().getGatewayClient(EVENT).append(Guarantee.STORED,
                    event("Unrelated", 0), relevant).join();
            assertNotNull(second.received.poll(10, TimeUnit.SECONDS));
            assertEquals(List.of(First.class.getName(), First.class.getName(), "LegacySecond"),
                    delivered.stream().map(SerializedMessage::getType).toList());

            var web = new WebHandler();
            app.registerHandlers(web);
            app.client().getGatewayClient(WEBREQUEST).append(Guarantee.STORED,
                    web("POST", "/items/a"), web("GET", "/elsewhere"), web("GET", "/items/a?x=y")).join();
            assertEquals("called", web.received.poll(10, TimeUnit.SECONDS));
            assertEquals(4, delivered.size());
            assertTrue(reads.stream().anyMatch(r -> r.getMaxSize() == 0 && r.getConsumerFilter() != null
                    && !r.getConsumerFilter().referenceOnly()));
        } finally {
            client.shutDown();
            server.stop();
        }
    }

    private static SerializedMessage event(String type, int segment) {
        var message = new SerializedMessage(new Data<>("{}".getBytes(StandardCharsets.UTF_8), type, 0, Data.JSON_FORMAT),
                Metadata.empty(), UUID.randomUUID().toString(), System.currentTimeMillis());
        message.setSegment(segment);
        return message;
    }

    private static SerializedMessage web(String method, String url) {
        return io.fluxzero.sdk.web.WebRequestRoute.update(
                event(null, 0).withMetadata(Metadata.of("method", method, "url", url)));
    }

    record First() {}
    record Second() {}

    @Consumer(name = "blueprint-events", threads = 2, minIndex = 0, maxFetchSize = 1)
    static class FirstHandler {
        final BlockingQueue<First> received = new LinkedBlockingQueue<>();
        @HandleEvent void handle(First event) { received.add(event); }
    }

    @Consumer(name = "blueprint-events", threads = 2, minIndex = 0, maxFetchSize = 1)
    static class SecondHandler {
        final BlockingQueue<Second> received = new LinkedBlockingQueue<>();
        @HandleEvent void handle(Second event) { received.add(event); }
    }

    static class LegacyCaster {
        @Upcast(type = "LegacySecond", revision = 0)
        Data<com.fasterxml.jackson.databind.node.ObjectNode> rename(Data<com.fasterxml.jackson.databind.node.ObjectNode> input) {
            return input.withType(Second.class.getName());
        }
    }

    @Consumer(name = "blueprint-web", minIndex = 0)
    static class WebHandler {
        final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        @HandleGet("/items/{id}") void handle() { received.add("called"); }
    }
}
