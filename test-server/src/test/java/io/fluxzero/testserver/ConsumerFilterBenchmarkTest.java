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
import io.fluxzero.common.api.tracking.ConsumerFilter;
import io.fluxzero.common.api.tracking.Read;
import io.fluxzero.common.api.tracking.TrackingWebSocketCodec;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.common.tracking.DefaultTrackingStrategy;
import io.fluxzero.common.tracking.InMemoryPositionStore;
import io.fluxzero.common.tracking.WebSocketTracker;
import io.fluxzero.common.websocket.WebSocketTransportCodecs;
import io.fluxzero.common.websocket.WebSocketTransportFormat;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.registry.ConsumerBlueprint;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.client.InMemoryMessageStore;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static io.fluxzero.common.MessageType.EVENT;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in resource/traffic qualification; no wall-clock performance assertions in the ordinary suite. */
@EnabledIfSystemProperty(named = "fluxzero.test.consumerFilterBenchmark", matches = "true")
class ConsumerFilterBenchmarkTest {
    @Test
    void measuresSelectiveConsumerDeliveryAndDescriptorSizes() throws Exception {
        var server = TestServer.startServer(0);
        int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
        var client = WebSocketClient.newInstance(WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost:" + port).namespace(UUID.randomUUID().toString())
                .name("filter-benchmark").disableMetrics(true).build());
        try {
            List<SerializedMessage> messages = new ArrayList<>();
            for (int i = 0; i < 4096; i++) messages.add(message("Unrelated"));
            messages.add(message("Accepted"));
            client.getGatewayClient(EVENT).append(Guarantee.STORED, messages.toArray(SerializedMessage[]::new)).join();
            var tracking = client.getTrackingClient(EVENT);
            for (boolean filtered : List.of(false, true)) {
                int reads = 0, delivered = 0;
                long start = System.nanoTime();
                for (int i = 0; i < 12; i++) {
                    String name = "consumer-" + filtered + "-" + i;
                    try (var blueprint = new ConsumerBlueprint(new JacksonSerializer(),
                            ConsumerFilterBenchmarkTest::description, new AtomicLong()::incrementAndGet)) {
                        var config = ConsumerConfiguration.builder().name(name).ignoreSegment(true)
                                .clientControlledIndex(true).maxFetchSize(64).maxWaitDuration(Duration.ofMinutes(15))
                                .maxScanLookBack(60_000L).consumerBlueprint(filtered ? blueprint : null).build();
                        Long cursor = -1L;
                        boolean complete = false;
                        while (!complete) {
                            var batch = tracking.read("tracker", cursor, config).get(10, TimeUnit.SECONDS);
                            reads++;
                            delivered += batch.getSize();
                            cursor = batch.getLastIndex();
                            complete = batch.getMessages().stream().anyMatch(m -> "Accepted".equals(m.getType()));
                        }
                        tracking.disconnectTracker(name, "tracker", false, Guarantee.STORED).join();
                    }
                }
                System.out.printf(java.util.Locale.ROOT,
                        "CONSUMER_FILTER_TRAFFIC filtered=%s consumers=12 delivered=%d reads=%d elapsedMs=%.3f%n",
                        filtered, delivered, reads, (System.nanoTime() - start) / 1e6);
                assertEquals(filtered ? 12 : 12 * 4097, delivered);
                assertEquals(filtered ? 12 : 12 * 65, reads);
            }
            var codec = WebSocketTransportCodecs.forFormat(WebSocketTransportFormat.BINARY, JsonUtils.reader,
                    List.of(TrackingWebSocketCodec.INSTANCE));
            Read read = read("size", description().forRevision("subscription", 1, Data.JSON_FORMAT, description().upcastInputs()));
            int full = codec.encode(read).length;
            read.setConsumerFilter(read.getConsumerFilter().asReference());
            System.out.printf("CONSUMER_FILTER_WIRE types=300 migrations=300 fullBytes=%d referenceBytes=%d%n",
                              full, codec.encode(read).length);
        } finally {
            client.shutDown();
            server.stop();
        }
    }

    @Test
    void measuresRepeatedFullReadAllocationsAfterFilterCompilation() {
        var allocation = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        for (boolean filtered : List.of(false, true)) {
            var descriptor = filtered ? description().forRevision("resource", 1, Data.JSON_FORMAT,
                                                                  description().upcastInputs()) : null;
            try (var strategy = new DefaultTrackingStrategy(new InMemoryMessageStore(EVENT), new InMemoryPositionStore())) {
                for (int i = 0; i < 100; i++) strategy.getBatch(new WebSocketTracker(read("resource", descriptor), EVENT, "client", "session")).join();
                long before = allocation.getThreadAllocatedBytes(thread), start = System.nanoTime();
                for (int i = 0; i < 1000; i++) strategy.getBatch(new WebSocketTracker(read("resource", descriptor), EVENT, "client", "session")).join();
                System.out.printf(java.util.Locale.ROOT,
                        "CONSUMER_FILTER_RESOURCE filtered=%s reads=1000 bytesPerRead=%.1f elapsedMs=%.3f%n",
                        filtered, (allocation.getThreadAllocatedBytes(thread) - before) / 1000.0,
                        (System.nanoTime() - start) / 1e6);
            }
        }
    }

    private static ConsumerFilter description() {
        var types = IntStream.range(0, 299).mapToObj(i -> new ConsumerFilter.PayloadType("example.events.Payload" + i, null))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        types.add(new ConsumerFilter.PayloadType("Accepted", null));
        var migrations = IntStream.range(0, 300).mapToObj(i -> new ConsumerFilter.PayloadType("example.legacy.Payload" + i, 0)).toList();
        return ConsumerFilter.select(null, 1, ConsumerFilter.VERSION, Data.JSON_FORMAT, types, null, migrations);
    }

    private static Read read(String consumer, ConsumerFilter filter) {
        var read = new Read(EVENT, consumer, "tracker", 64, 0L, null, false, false, false, true, -1L, null);
        read.setConsumerFilter(filter);
        read.setMaxScanLookBack(60_000L);
        return read;
    }

    private static SerializedMessage message(String type) {
        return new SerializedMessage(new Data<>(new byte[32], type, 0, Data.JSON_FORMAT), Metadata.empty(),
                                     UUID.randomUUID().toString(), System.currentTimeMillis());
    }
}
