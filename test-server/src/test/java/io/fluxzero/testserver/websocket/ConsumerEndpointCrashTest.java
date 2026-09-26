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
 *
 */

package io.fluxzero.testserver.websocket;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Read;
import io.fluxzero.common.tracking.DefaultTrackingStrategy;
import io.fluxzero.common.tracking.InMemoryPositionStore;
import io.fluxzero.common.tracking.Tracker;
import io.fluxzero.sdk.common.websocket.ServiceUrlBuilder;
import io.fluxzero.sdk.common.websocket.WebsocketCloseReason;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.client.InMemoryMessageStore;
import io.fluxzero.sdk.tracking.client.WebsocketTrackingClient;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.ServicePathBuilder.trackingPath;
import static io.fluxzero.common.api.tracking.SegmentRange.MAX_SEGMENT;
import static io.fluxzero.testserver.websocket.WebsocketDeploymentUtils.deploy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerEndpointCrashTest {
    @TempDir
    Path directory;

    @Test
    void freshClientReadsRetainedMessagesAfterProducerHaltsDuringReadRegistration() throws Exception {
        var messages = new InMemoryMessageStore(COMMAND, null);
        var positions = new InMemoryPositionStore();
        positions.storePosition("recovery", new int[]{0, MAX_SEGMENT}, 10L).join();
        var retained = List.of(message(11, 0), message(12, MAX_SEGMENT - 1));
        messages.append(retained).join();
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var handled = new CountDownLatch(1);
        var strategy = new DefaultTrackingStrategy(messages, positions) {
            @Override
            public CompletableFuture<MessageBatch> getBatch(Tracker tracker) {
                if (tracker.getClientId().equals("departed")) {
                    entered.countDown();
                    await(resume);
                }
                return super.getBatch(tracker);
            }
        };
        var endpoint = new ConsumerEndpoint(strategy, messages, positions, COMMAND) {
            @Override
            public void onClose(ServerWebsocketSession session, WebsocketCloseReason reason) {
                super.onClose(session, reason);
                if (getClientId(session).equals("departed")) {
                    closed.countDown();
                }
            }

            @Override
            protected void handleMessage(ServerWebsocketSession session, JsonType request) {
                try {
                    super.handleMessage(session, request);
                } finally {
                    if (request instanceof Read && getClientId(session).equals("departed")) {
                        handled.countDown();
                    }
                }
            }
        };
        var router = deploy(ignored -> endpoint, "/%s/".formatted(trackingPath(COMMAND)),
                            new JettyWebsocketRouter());
        var server = router.start(new InetSocketAddress("127.0.0.1", 0));
        Process child = null;
        try {
            int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
            Path log = directory.resolve("crash-worker.log");
            child = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "--enable-native-access=ALL-UNNAMED", "-cp",
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    CrashWorker.class.getName(), Integer.toString(port))
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertTrue(entered.await(15, TimeUnit.SECONDS), () -> readLog(log));
            child.getOutputStream().write(1);
            child.getOutputStream().flush();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), () -> readLog(log));
            assertEquals(77, child.exitValue(), () -> readLog(log));
            assertTrue(closed.await(10, TimeUnit.SECONDS), "Server must observe transport loss");
            resume.countDown();
            assertTrue(handled.await(10, TimeUnit.SECONDS));

            var client = client(port, "replacement");
            try (var tracking = tracking(client)) {
                var batch = tracking.read("replacement-tracker", null, configuration()).get(10, TimeUnit.SECONDS);
                assertArrayEquals(new int[]{0, MAX_SEGMENT}, batch.getSegment());
                assertEquals(List.of("retained-11", "retained-12"),
                             batch.getMessages().stream().map(SerializedMessage::getMessageId).toList());
                assertEquals(10L, tracking.getPosition("recovery").getIndex(0).orElseThrow());
                assertEquals(10L, tracking.getPosition("recovery").getIndex(MAX_SEGMENT - 1).orElseThrow());
                assertEquals(retained, messages.getBatch(10L, 10));
            } finally {
                client.shutDown();
            }
        } finally {
            resume.countDown();
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            }
            server.stop();
        }
    }

    public static class CrashWorker {
        public static void main(String[] args) throws Exception {
            var client = client(Integer.parseInt(args[0]), "departed");
            var tracking = tracking(client);
            tracking.read("departed-tracker", null, configuration());
            if (System.in.read() >= 0) {
                Runtime.getRuntime().halt(77);
            }
            throw new IllegalStateException("Crash signal missing");
        }
    }

    private static WebSocketClient client(int port, String id) {
        return WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://127.0.0.1:" + port).namespace("crash-recovery")
                .id(id).name("crash-recovery").disableMetrics(true).build());
    }

    private static WebsocketTrackingClient tracking(WebSocketClient client) {
        return new WebsocketTrackingClient(
                URI.create(ServiceUrlBuilder.trackingUrl(COMMAND, null, client.getClientConfig())),
                client, COMMAND, null, false);
    }

    private static ConsumerConfiguration configuration() {
        return ConsumerConfiguration.builder().name("recovery").maxWaitDuration(Duration.ofSeconds(1)).build();
    }

    private static SerializedMessage message(long index, int segment) {
        var result = new SerializedMessage(new Data<>(new byte[]{1}, "example", 0), Metadata.empty(),
                                           "retained-" + index, System.currentTimeMillis());
        result.setIndex(index);
        result.setSegment(segment);
        return result;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String readLog(Path log) {
        try {
            return Files.readString(log);
        } catch (Exception e) {
            return e.toString();
        }
    }
}
