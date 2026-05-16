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

package io.fluxzero.testserver;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.Relationship;
import io.fluxzero.common.api.modeling.RepairRelationships;
import io.fluxzero.common.api.modeling.UpdateRelationships;
import io.fluxzero.common.api.scheduling.SerializedSchedule;
import io.fluxzero.common.api.search.CreateAuditTrail;
import io.fluxzero.common.api.search.DocumentUpdate;
import io.fluxzero.common.api.search.FacetEntry;
import io.fluxzero.common.api.search.GetDocument;
import io.fluxzero.common.api.search.GetDocuments;
import io.fluxzero.common.api.search.HasDocument;
import io.fluxzero.common.api.search.SearchDocuments;
import io.fluxzero.common.api.search.SearchQuery;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.common.api.tracking.ClaimSegmentResult;
import io.fluxzero.common.api.tracking.MessageBatch;
import io.fluxzero.common.api.tracking.Position;
import io.fluxzero.common.api.tracking.SegmentRange;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import io.fluxzero.sdk.common.websocket.JdkWebsocketConnector;
import io.fluxzero.sdk.common.websocket.ServiceUrlBuilder;
import io.fluxzero.sdk.common.websocket.WebsocketCloseReason;
import io.fluxzero.sdk.common.websocket.WebsocketConnectionOptions;
import io.fluxzero.sdk.common.websocket.WebsocketEndpoint;
import io.fluxzero.sdk.common.websocket.WebsocketSession;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.persisting.eventsourcing.AggregateEventStream;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.keyvalue.client.KeyValueClient;
import io.fluxzero.sdk.persisting.search.SearchHit;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import io.fluxzero.sdk.publishing.client.GatewayClient;
import io.fluxzero.sdk.scheduling.client.SchedulingClient;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.client.TrackingClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.CUSTOM;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.api.search.BulkUpdate.Type.delete;
import static io.fluxzero.common.api.search.BulkUpdate.Type.index;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestServerWebsocketContractTest {
    private static final int PORT = 9131;
    private static final int[] FULL_SEGMENT = new int[]{0, SegmentRange.MAX_SEGMENT};
    private static final long TIMEOUT_SECONDS = 5L;

    @BeforeAll
    static void beforeAll() {
        TestServer.start(PORT);
    }

    @Test
    void supportedCompressionAlgorithmsRoundTripOverFullServer() throws Exception {
        for (CompressionAlgorithm algorithm : CompressionAlgorithm.values()) {
            WebSocketClient client = client("compression-" + algorithm, List.of(algorithm));
            try {
                String value = "compressed-" + algorithm;
                await(client.getGatewayClient(EVENT).append(STORED, message(value)));

                List<SerializedMessage> messages = client.getTrackingClient(EVENT).readFromIndex(0, 10);
                assertEquals(List.of(value), messages.stream().map(TestServerWebsocketContractTest::payload).toList());
            } finally {
                client.shutDown();
            }
        }
    }

    @Test
    void namespacesAndTopicsAreIsolatedOverFullServer() throws Exception {
        WebSocketClient namespaceA = client("namespace-a", "contract-namespace-a-" + UUID.randomUUID());
        WebSocketClient namespaceB = client("namespace-b", "contract-namespace-b-" + UUID.randomUUID());
        WebSocketClient topics = client("topics");
        try {
            await(namespaceA.getGatewayClient(EVENT).append(STORED, message("namespace-a-event")));
            await(namespaceB.getGatewayClient(EVENT).append(STORED, message("namespace-b-event")));

            assertEquals(List.of("namespace-a-event"),
                         namespaceA.getTrackingClient(EVENT).readFromIndex(0, 10).stream()
                                 .map(TestServerWebsocketContractTest::payload).toList());
            assertEquals(List.of("namespace-b-event"),
                         namespaceB.getTrackingClient(EVENT).readFromIndex(0, 10).stream()
                                 .map(TestServerWebsocketContractTest::payload).toList());

            await(topics.getGatewayClient(CUSTOM, "topic-a").append(STORED, message("topic-a-event")));
            await(topics.getGatewayClient(CUSTOM, "topic-b").append(STORED, message("topic-b-event")));

            assertEquals(List.of("topic-a-event"),
                         topics.getTrackingClient(CUSTOM, "topic-a").readFromIndex(0, 10).stream()
                                 .map(TestServerWebsocketContractTest::payload).toList());
            assertEquals(List.of("topic-b-event"),
                         topics.getTrackingClient(CUSTOM, "topic-b").readFromIndex(0, 10).stream()
                                 .map(TestServerWebsocketContractTest::payload).toList());
        } finally {
            namespaceA.shutDown();
            namespaceB.shutDown();
            topics.shutDown();
        }
    }

    @Test
    void malformedPayloadsDoNotCloseTheSessionOrBreakTheServer() throws Exception {
        String namespace = "contract-malformed-" + UUID.randomUUID();
        WebSocketClient.ClientConfig rawConfig = clientConfig("malformed-raw", namespace, List.of(CompressionAlgorithm.NONE));
        RecordingRawEndpoint rawEndpoint = new RecordingRawEndpoint();
        WebsocketSession rawSession = new JdkWebsocketConnector().connect(
                rawEndpoint,
                new WebsocketConnectionOptions(Map.of(), Map.of(), Duration.ofSeconds(5), List.of()),
                URI.create(ServiceUrlBuilder.gatewayUrl(EVENT, null, rawConfig)));
        WebSocketClient client = WebSocketClient.newInstance(rawConfig);
        try {
            rawSession.sendBinary(ByteBuffer.wrap("not json".getBytes(UTF_8)));
            rawSession.sendBinary(ByteBuffer.wrap("{\"@type\":\"notAFluxzeroRequest\"}".getBytes(UTF_8)));

            Thread.sleep(250L);
            assertTrue(rawSession.isOpen());
            assertFalse(rawEndpoint.awaitClose(100, TimeUnit.MILLISECONDS));
            assertNull(rawEndpoint.error());
            rawSession.sendPing(ByteBuffer.wrap(new byte[]{1}));

            await(client.getGatewayClient(EVENT).append(STORED, message("after-malformed")));
            assertEquals(List.of("after-malformed"), client.getTrackingClient(EVENT).readFromIndex(0, 10).stream()
                    .map(TestServerWebsocketContractTest::payload).toList());
        } finally {
            client.shutDown();
            rawSession.close();
        }
    }

    @Test
    void pendingReadIsRetriedAfterReconnectOverFullServer() throws Exception {
        String namespace = "contract-reconnect-" + UUID.randomUUID();
        WebSocketClient.ClientConfig config = clientConfig("reconnect", namespace, null);
        WebSocketClient client = WebSocketClient.newInstance(config);
        ReconnectObservingTrackingClient tracking =
                new ReconnectObservingTrackingClient(URI.create(ServiceUrlBuilder.trackingUrl(EVENT, null, config)),
                                                     client);
        try {
            CompletableFuture<MessageBatch> read = tracking.read("reconnect-tracker", null,
                                                                 ConsumerConfiguration.builder()
                                                                         .name("reconnect-consumer")
                                                                         .maxWaitDuration(Duration.ofSeconds(30))
                                                                         .build());
            assertTrue(tracking.awaitFirstOpen(5, TimeUnit.SECONDS));
            Thread.sleep(100L);

            tracking.closeFirstSession();

            assertTrue(tracking.awaitReconnect(10, TimeUnit.SECONDS));
            await(client.getGatewayClient(EVENT).append(STORED, message("after-reconnect")));

            MessageBatch batch = await(read);
            assertEquals(List.of("after-reconnect"),
                         batch.getMessages().stream().map(TestServerWebsocketContractTest::payload).toList());
        } finally {
            tracking.close();
            client.shutDown();
        }
    }

    @Test
    void gatewayAndTrackingRequestsRoundTripOverFullServer() throws Exception {
        WebSocketClient client = client("tracking");
        try {
            GatewayClient gateway = client.getGatewayClient(EVENT);
            TrackingClient tracking = client.getTrackingClient(EVENT);
            SerializedMessage largeMessage = message("large", randomBytes(96 * 1024));
            SerializedMessage smallMessage = message("small", "small-event".getBytes(UTF_8));

            await(gateway.setRetentionTime(Duration.ofMinutes(5), STORED));
            await(gateway.append(STORED, largeMessage, smallMessage));

            List<SerializedMessage> messages = tracking.readFromIndex(0, 10);
            assertEquals(2, messages.size());
            assertArrayEquals(largeMessage.getData().getValue(), messages.getFirst().getData().getValue());
            assertEquals("small-event", payload(messages.getLast()));

            String consumer = "contract-consumer";
            long lastIndex = messages.getLast().getIndex();
            await(tracking.storePosition(consumer, FULL_SEGMENT, lastIndex, STORED));
            assertPosition(lastIndex, tracking.getPosition(consumer));

            await(tracking.resetPosition(consumer, 42L, STORED));
            assertPosition(42L, tracking.getPosition(consumer));

            ClaimSegmentResult claim = await(tracking.claimSegment("tracker-1", 42L,
                                                                    ConsumerConfiguration.builder()
                                                                            .name(consumer)
                                                                            .clientControlledIndex(true)
                                                                            .maxWaitDuration(Duration.ofMillis(100))
                                                                            .build()));
            assertArrayEquals(FULL_SEGMENT, claim.getSegment());
            assertEquals(42L, claim.getPosition().lowestIndexForSegment(claim.getSegment()).orElseThrow());
            await(tracking.disconnectTracker(consumer, "tracker-1", false, STORED));
        } finally {
            client.shutDown();
        }
    }

    @Test
    void truncateCustomTopicOverFullServerClearsMessagesPositionsAndAllowsTrackingToContinue() throws Exception {
        WebSocketClient client = client("truncate-custom-topic");
        try {
            String topic = "orders-" + UUID.randomUUID();
            GatewayClient gateway = client.getGatewayClient(CUSTOM, topic);
            TrackingClient tracking = client.getTrackingClient(CUSTOM, topic);
            String consumer = "truncate-custom-topic-consumer";

            await(gateway.append(STORED, message("before-1"), message("before-2")));
            MessageBatch beforeTruncate = await(tracking.read(
                    "tracker-before", null, ConsumerConfiguration.builder().name(consumer).build()));
            assertEquals(2, beforeTruncate.getSize());
            await(tracking.storePosition(consumer, beforeTruncate.getSegment(), beforeTruncate.getLastIndex(), STORED));
            assertFalse(tracking.getPosition(consumer).isNew(FULL_SEGMENT));

            await(gateway.truncate(STORED));

            assertTrue(tracking.readFromIndex(0, 10).isEmpty());
            assertTrue(tracking.getPosition(consumer).isNew(FULL_SEGMENT));

            await(gateway.append(STORED, message("after")));
            MessageBatch afterTruncate = await(tracking.read(
                    "tracker-after", null, ConsumerConfiguration.builder().name(consumer).build()));
            assertEquals(1, afterTruncate.getSize());
            await(tracking.storePosition(consumer, afterTruncate.getSegment(), afterTruncate.getLastIndex(), STORED));
        } finally {
            client.shutDown();
        }
    }

    @Test
    void truncateCustomTopicOverFullServerDisconnectsActiveWaitingTracker() throws Exception {
        WebSocketClient client = client("truncate-custom-topic-waiting");
        try {
            String topic = "live-" + UUID.randomUUID();
            GatewayClient gateway = client.getGatewayClient(CUSTOM, topic);
            TrackingClient tracking = client.getTrackingClient(CUSTOM, topic);
            String consumer = "truncate-custom-topic-waiting-consumer";

            CompletableFuture<MessageBatch> waitingBatch = tracking.read(
                    "waiting-tracker", null, ConsumerConfiguration.builder()
                            .name(consumer).maxWaitDuration(Duration.ofSeconds(30)).build());
            Thread.sleep(250L);
            assertFalse(waitingBatch.isDone());

            await(gateway.truncate(STORED));

            MessageBatch finalBatch = await(waitingBatch);
            assertEquals(0, finalBatch.getSize());
            assertArrayEquals(new int[]{0, 0}, finalBatch.getSegment());
            assertTrue(finalBatch.isCaughtUp());

            await(gateway.append(STORED, message("after")));
            MessageBatch afterDelete = await(tracking.read(
                    "tracker-after", null, ConsumerConfiguration.builder().name(consumer).build()));
            assertEquals(1, afterDelete.getSize());
        } finally {
            client.shutDown();
        }
    }

    @Test
    void truncateEventLogOverFullServerClearsMessagesPositionsAndAllowsTrackingToContinue() throws Exception {
        WebSocketClient client = client("truncate-event-log");
        try {
            GatewayClient gateway = client.getGatewayClient(EVENT);
            TrackingClient tracking = client.getTrackingClient(EVENT);
            String consumer = "truncate-event-log-consumer";

            await(gateway.append(STORED, message("before")));
            MessageBatch beforeTruncate = await(tracking.read(
                    "tracker-before", null, ConsumerConfiguration.builder().name(consumer).build()));
            assertEquals(1, beforeTruncate.getSize());
            await(tracking.storePosition(consumer, beforeTruncate.getSegment(), beforeTruncate.getLastIndex(), STORED));

            await(gateway.truncate(STORED));

            assertTrue(tracking.readFromIndex(0, 10).isEmpty());
            assertTrue(tracking.getPosition(consumer).isNew(FULL_SEGMENT));
            await(gateway.append(STORED, message("after")));
            MessageBatch afterTruncate = await(tracking.read(
                    "tracker-after", null, ConsumerConfiguration.builder().name(consumer).build()));
            assertEquals(1, afterTruncate.getSize());
        } finally {
            client.shutDown();
        }
    }

    @Test
    void eventSourcingRequestsRoundTripOverFullServer() throws Exception {
        WebSocketClient client = client("event-sourcing");
        try {
            EventStoreClient eventStore = client.getEventStoreClient();
            String aggregateId = "aggregate-" + UUID.randomUUID();
            List<SerializedMessage> events = List.of(message("event-1"), message("event-2"));

            await(eventStore.storeEvents(aggregateId, events, true, STORED));

            AggregateEventStream<SerializedMessage> stream = eventStore.getEvents(aggregateId, -1L, 10);
            assertEquals(List.of("event-1", "event-2"), stream.map(TestServerWebsocketContractTest::payload).toList());
            assertEquals(1L, stream.getLastSequenceNumber().orElseThrow());

            Relationship stale = relationship("stale-entity", aggregateId, "ContractAggregate");
            Relationship fresh = relationship("fresh-entity", aggregateId, "ContractAggregate");
            await(eventStore.updateRelationships(new UpdateRelationships(Set.of(stale), Set.of(), STORED)));
            assertEquals(Map.of(aggregateId, "ContractAggregate"), eventStore.getAggregatesFor("stale-entity"));

            await(eventStore.repairRelationships(
                    new RepairRelationships(aggregateId, "ContractAggregate", Set.of("fresh-entity"), STORED)));
            assertTrue(eventStore.getAggregatesFor("stale-entity").isEmpty());
            assertEquals(Map.of(aggregateId, "ContractAggregate"), eventStore.getAggregatesFor("fresh-entity"));
            assertEquals(List.of(fresh), eventStore.getRelationships("fresh-entity"));

            await(eventStore.deleteEvents(aggregateId, STORED));
            assertTrue(eventStore.getEvents(aggregateId).toList().isEmpty());
        } finally {
            client.shutDown();
        }
    }

    @Test
    void keyValueAndSchedulingRequestsRoundTripOverFullServer() throws Exception {
        WebSocketClient client = client("key-value-scheduling");
        try {
            KeyValueClient keyValue = client.getKeyValueClient();
            String key = "key-" + UUID.randomUUID();

            await(keyValue.putValue(key, data("first-value"), STORED));
            assertEquals("first-value", value(keyValue.getValue(key)));
            assertFalse(await(keyValue.putValueIfAbsent(key, data("second-value"))));
            assertEquals("first-value", value(keyValue.getValue(key)));

            String absentKey = key + "-absent";
            assertTrue(await(keyValue.putValueIfAbsent(absentKey, data("absent-value"))));
            assertEquals("absent-value", value(keyValue.getValue(absentKey)));

            await(keyValue.deleteValue(key, STORED));
            assertNull(keyValue.getValue(key));

            SchedulingClient scheduling = client.getSchedulingClient();
            String scheduleId = "schedule-" + UUID.randomUUID();
            SerializedSchedule schedule = new SerializedSchedule(
                    scheduleId, Instant.now().plusSeconds(60).toEpochMilli(), message("scheduled-command"), false);
            await(scheduling.schedule(STORED, schedule));
            assertEquals(scheduleId, scheduling.getSchedule(scheduleId).getScheduleId());

            await(scheduling.cancelSchedule(scheduleId, STORED));
            assertNull(scheduling.getSchedule(scheduleId));
        } finally {
            client.shutDown();
        }
    }

    @Test
    void searchRequestsRoundTripOverFullServer() throws Exception {
        WebSocketClient client = client("search");
        try {
            SearchClient search = client.getSearchClient();
            String collection = "collection-" + UUID.randomUUID();
            String movedCollection = collection + "-moved";
            SearchQuery query = SearchQuery.builder().collection(collection).build();
            SerializedDocument doc1 = document("doc-1", collection, "alpha", Set.of(new FacetEntry("kind", "primary")));
            SerializedDocument doc2 = document("doc-2", collection, "beta", Set.of(new FacetEntry("kind", "primary")));

            await(search.index(List.of(doc1, doc2), STORED, false));

            assertTrue(search.documentExists(new HasDocument("doc-1", collection)));
            assertEquals("doc-1", search.fetch(new GetDocument("doc-1", collection)).orElseThrow().getId());
            assertEquals(2, search.fetch(new GetDocuments(List.of("doc-1", "doc-2", "missing"), collection)).size());
            assertEquals(List.of("doc-1", "doc-2"), search.search(SearchDocuments.builder().query(query).build(), 100)
                    .map(SearchHit::getValue).map(SerializedDocument::getId).sorted().toList());
            assertEquals(2, search.fetchFacetStats(query).stream()
                    .filter(s -> "kind".equals(s.getName()) && "primary".equals(s.getValue()))
                    .findFirst().orElseThrow().getCount());

            await(search.createAuditTrail(new CreateAuditTrail(collection, 60L, STORED)));
            await(search.move("doc-1", collection, movedCollection, STORED));
            assertFalse(search.documentExists(new HasDocument("doc-1", collection)));
            assertTrue(search.documentExists(new HasDocument("doc-1", movedCollection)));

            await(search.bulkUpdate(List.of(
                    DocumentUpdate.builder().type(index).id("doc-3").collection(collection)
                            .object(document("doc-3", collection, "gamma", Set.of())).build(),
                    DocumentUpdate.builder().type(delete).id("doc-2").collection(collection).build()), STORED));
            assertTrue(search.documentExists(new HasDocument("doc-3", collection)));
            assertFalse(search.documentExists(new HasDocument("doc-2", collection)));

            await(search.deleteCollection(movedCollection, STORED));
            assertFalse(search.documentExists(new HasDocument("doc-1", movedCollection)));
        } finally {
            client.shutDown();
        }
    }

    private static WebSocketClient client(String testName) {
        return client(testName, "contract-" + testName + "-" + UUID.randomUUID(), null);
    }

    private static WebSocketClient client(String testName, List<CompressionAlgorithm> compressionAlgorithms) {
        return client(testName, "contract-" + testName + "-" + UUID.randomUUID(), compressionAlgorithms);
    }

    private static WebSocketClient client(String testName, String namespace) {
        return client(testName, namespace, null);
    }

    private static WebSocketClient client(String testName, String namespace,
                                          List<CompressionAlgorithm> compressionAlgorithms) {
        return WebSocketClient.newInstance(clientConfig(testName, namespace, compressionAlgorithms));
    }

    private static WebSocketClient.ClientConfig clientConfig(String testName, String namespace,
                                                             List<CompressionAlgorithm> compressionAlgorithms) {
        String uniqueId = testName + "-" + UUID.randomUUID();
        var builder = WebSocketClient.ClientConfig.builder()
                .runtimeBaseUrl("ws://localhost:" + PORT)
                .namespace(namespace)
                .name("TestServer contract " + testName)
                .id("contract-client-" + uniqueId)
                .disableMetrics(true)
                .eventSourcingSessions(1)
                .keyValueSessions(1)
                .searchSessions(1);
        if (compressionAlgorithms != null) {
            builder.supportedCompressionAlgorithms(compressionAlgorithms);
        }
        return builder.build();
    }

    private static SerializedMessage message(String value) {
        return message(value, value.getBytes(UTF_8));
    }

    private static SerializedMessage message(String id, byte[] value) {
        return new SerializedMessage(new Data<>(value, String.class.getName(), 0, "text/plain"),
                                     Metadata.empty(), id + "-" + UUID.randomUUID(), Instant.now().toEpochMilli());
    }

    private static SerializedDocument document(String id, String collection, String value, Set<FacetEntry> facets) {
        return new SerializedDocument(id, Instant.now().toEpochMilli(), null, collection, data(value), value, facets,
                                      Set.of());
    }

    private static Data<byte[]> data(String value) {
        return new Data<>(value.getBytes(UTF_8), String.class.getName(), 0, "text/plain");
    }

    private static String value(Data<byte[]> data) {
        return new String(data.getValue(), UTF_8);
    }

    private static String payload(SerializedMessage message) {
        return value(message.getData());
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(1234L).nextBytes(bytes);
        return bytes;
    }

    private static Relationship relationship(String entityId, String aggregateId, String aggregateType) {
        return Relationship.builder().entityId(entityId).aggregateId(aggregateId).aggregateType(aggregateType).build();
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void assertPosition(long expectedIndex, Position position) {
        assertEquals(expectedIndex, position.lowestIndexForSegment(FULL_SEGMENT).orElseThrow());
    }

    private static class RecordingRawEndpoint implements WebsocketEndpoint {
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override
        public void onOpen(WebsocketSession session) {
        }

        @Override
        public void onMessage(byte[] bytes, WebsocketSession session) {
        }

        @Override
        public void onPong(ByteBuffer data, WebsocketSession session) {
        }

        @Override
        public void onClose(WebsocketSession session, WebsocketCloseReason closeReason) {
            closed.countDown();
        }

        @Override
        public void onError(WebsocketSession session, Throwable error) {
            this.error.set(error);
        }

        boolean awaitClose(long timeout, TimeUnit unit) throws InterruptedException {
            return closed.await(timeout, unit);
        }

        Throwable error() {
            return error.get();
        }
    }

    private static class ReconnectObservingTrackingClient extends io.fluxzero.sdk.tracking.client.WebsocketTrackingClient {
        private final CountDownLatch firstOpen = new CountDownLatch(1);
        private final CountDownLatch reconnected = new CountDownLatch(1);
        private final AtomicReference<WebsocketSession> firstSession = new AtomicReference<>();

        ReconnectObservingTrackingClient(URI endpoint, WebSocketClient client) {
            super(endpoint, client, EVENT, null, false);
        }

        @Override
        public void onOpen(WebsocketSession session) {
            super.onOpen(session);
            if (firstSession.compareAndSet(null, session)) {
                firstOpen.countDown();
            } else {
                reconnected.countDown();
            }
        }

        void closeFirstSession() throws Exception {
            firstSession.get().close(
                    new WebsocketCloseReason(WebsocketCloseReason.UNEXPECTED_CONDITION, "reconnect test close"));
        }

        boolean awaitFirstOpen(long timeout, TimeUnit unit) throws InterruptedException {
            return firstOpen.await(timeout, unit);
        }

        boolean awaitReconnect(long timeout, TimeUnit unit) throws InterruptedException {
            return reconnected.await(timeout, unit);
        }
    }
}
