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

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.UuidFactory;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.modeling.AssertLegal;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.publishing.routing.RoutingKey;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.ConsumerHandlingMode;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.common.MessageType.COMMAND;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class ModelAsyncContentionTest {
    private static Server server;
    private static int port;

    @BeforeAll
    static void start() {
        server = TestServer.startServer(0, ignored -> {});
        port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @AfterAll
    static void stop() throws Exception { server.stop(); }

    @AfterEach
    void cleanup() { TestFixture.shutDownActiveFixtures(); }

    @ParameterizedTest
    @CsvSource({"SYNC,8,128", "ASYNC,8,128", "ASYNC,64,512", "ASYNC,256,2048"})
    void overlappingReservationsPreserveCapacityAndResults(ConsumerHandlingMode mode, int callers, int requests) {
        var client = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .name("contention-test").runtimeBaseUrl("ws://localhost:" + port).namespace("contention-" + UUID.randomUUID()).build());
        TestFixture.createAsync(DefaultFluxzero.builder().replaceIdentityProvider(ignored -> new UuidFactory())
                        .configureDefaultConsumer(COMMAND, c -> c.toBuilder().handlingMode(mode).build()), client, SetStock.class, Reserve.class)
                .resultTimeout(Duration.ofSeconds(30))
                .givenCommands(new SetStock("stock", requests / 2))
                .whenExecuting(f -> {
                    AtomicInteger next = new AtomicInteger();
                    Set<Integer> accepted = ConcurrentHashMap.newKeySet();
                    var workers = Executors.newVirtualThreadPerTaskExecutor();
                    try {
                        List<Future<?>> tasks = new ArrayList<>();
                        for (int worker = 0; worker < callers; worker++) {
                            tasks.add(workers.submit(() -> {
                                for (int n; (n = next.getAndIncrement()) < requests;) {
                                    int id = n;
                                    f.apply(fc -> {
                                        try {
                                            Fluxzero.sendCommandAndWait(new Reserve("stock", "receipt-" + id));
                                            accepted.add(id);
                                        } catch (IllegalCommandException refused) {
                                            assertEquals("Sold out", refused.getMessage());
                                        }
                                        return null;
                                    });
                                }
                            }));
                        }
                        for (Future<?> task : tasks) { task.get(30, TimeUnit.SECONDS); }
                    } finally {
                        workers.shutdownNow();
                        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
                    }
                    assertEquals(requests / 2, accepted.size());
                    assertEquals(0, Fluxzero.loadModel("stock", Stock.class).get().remaining());
                    for (int i = 0; i < requests; i++) {
                        assertEquals(accepted.contains(i), Fluxzero.loadModel("receipt-" + i, Receipt.class).isPresent());
                    }
                    Fluxzero.sendCommandAndWait(new SetStock("stock", 1));
                    Fluxzero.sendCommandAndWait(new Reserve("stock", "replacement"));
                    assertEquals(0, Fluxzero.loadModel("stock", Stock.class).get().remaining());
                    assertTrue(Fluxzero.loadModel("replacement", Receipt.class).isPresent());
                }).expectSuccessfulResult().expectNoErrors();
    }

    @Model record Stock(@EntityId String stockId, int remaining) {}
    @Model record Receipt(@EntityId String receiptId) {}
    record SetStock(String stockId, int remaining) {
        @Apply Stock apply(@jakarta.annotation.Nullable Stock previous) { return new Stock(stockId, remaining); }
    }
    record Reserve(@RoutingKey String stockId, String receiptId) {
        @AssertLegal void check(Stock stock) {
            if (stock.remaining() == 0) { throw new IllegalCommandException("Sold out"); }
        }
        @Apply Stock take(Stock stock) { return new Stock(stockId, stock.remaining() - 1); }
        @Apply Receipt create() { return new Receipt(receiptId); }
    }
}
