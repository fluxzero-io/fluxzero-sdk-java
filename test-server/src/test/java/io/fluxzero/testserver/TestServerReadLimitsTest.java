/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.testserver;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.client.TrackingClient;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.EVENT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TestServerReadLimitsTest {
    private static final long TIMEOUT_SECONDS = 5L;
    private static Server server;
    private static int port;

    @BeforeAll
    static void beforeAll() {
        server = TestServer.startServer(0);
        port = localPort(server);
    }

    @AfterAll
    static void afterAll() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void trackingReadsHonorMaxFetchBytesOverFullServer() throws Exception {
        WebSocketClient client = client("tracking-byte-limit");
        try {
            GatewayClient gateway = client.getGatewayClient(EVENT);
            TrackingClient tracking = client.getTrackingClient(EVENT);
            await(gateway.append(STORED,
                                 message("first", "aaaa".getBytes(UTF_8)),
                                 message("second", "bbbb".getBytes(UTF_8)),
                                 message("third", "cccc".getBytes(UTF_8))));

            List<SerializedMessage> direct = tracking.readFromIndex(0, 10, 5L);
            assertEquals(List.of("aaaa"), direct.stream().map(TestServerReadLimitsTest::payload).toList());

            String consumer = "contract-byte-limit-consumer";
            ConsumerConfiguration config = ConsumerConfiguration.builder()
                    .name(consumer)
                    .maxFetchSize(10)
                    .maxFetchBytes(5L)
                    .maxWaitDuration(Duration.ZERO)
                    .build();

            String trackerId = "byte-tracker";
            MessageBatch firstBatch = await(tracking.read(trackerId, null, config));
            assertEquals(List.of("aaaa"),
                         firstBatch.getMessages().stream().map(TestServerReadLimitsTest::payload).toList());
            assertEquals(4L, firstBatch.getBytes());
            assertEquals(firstBatch.getMessages().getFirst().getIndex(), firstBatch.getLastIndex());
            assertFalse(firstBatch.isCaughtUp());

            await(tracking.storePosition(consumer, firstBatch.getSegment(), firstBatch.getLastIndex(), STORED));
            MessageBatch secondBatch = await(tracking.read(trackerId, firstBatch.getLastIndex(), config));
            assertEquals(List.of("bbbb"),
                         secondBatch.getMessages().stream().map(TestServerReadLimitsTest::payload).toList());
        } finally {
            client.shutDown();
        }
    }

    private static WebSocketClient client(String testName) {
        String uniqueId = testName + "-" + UUID.randomUUID();
        return WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                                                   .runtimeBaseUrl("ws://localhost:" + port)
                                                   .namespace("contract-" + uniqueId)
                                                   .name("TestServer contract " + testName)
                                                   .id("contract-client-" + uniqueId)
                                                   .disableMetrics(true)
                                                   .eventSourcingSessions(1)
                                                   .keyValueSessions(1)
                                                   .searchSessions(1)
                                                   .build());
    }

    private static int localPort(Server server) {
        for (var connector : server.getConnectors()) {
            if (connector instanceof ServerConnector serverConnector) {
                int localPort = serverConnector.getLocalPort();
                if (localPort > 0) {
                    return localPort;
                }
            }
        }
        throw new IllegalStateException("Started test server has no bound local port");
    }

    private static SerializedMessage message(String id, byte[] value) {
        return new SerializedMessage(new Data<>(value, String.class.getName(), 0, "text/plain"),
                                     Metadata.empty(), id + "-" + UUID.randomUUID(), Instant.now().toEpochMilli());
    }

    private static String payload(SerializedMessage message) {
        return new String(message.getData().getValue(), UTF_8);
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
