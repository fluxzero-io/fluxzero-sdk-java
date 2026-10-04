/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.sdk.test.contracts;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.modeling.*;
import io.fluxzero.common.api.search.GetDocument;
import io.fluxzero.common.modeling.ModelDocumentProof;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.*;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.Tracker;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** Same guarded reindex contracts on LocalClient, TestServer and PostgreSQL Runtime. */
public abstract class GraphReindexContract {
    protected abstract Client client(String namespace);

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void reindexBackfillsWithoutChangingHistoryAndSkipsRepeatedEvents(GraphProjectionMode mode) throws Exception {
        Class<?> type = type(mode);
        try (var app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .build(client("reindex-" + UUID.randomUUID()))) {
            app.apply(fc -> {
                ((DefaultModelRepository) fc.modelRepository()).configureModelTypes(() -> List.of(type));
                // Seed the event-only storage envelope independently of current searchable declarations.
                seed(app, type, 1);
                Graph<?> historical = Fluxzero.loadGraph("node", type);
                assertTrue(historical.get().toString().contains("version=1"));
                seed(app, type, 2);
                var events = heads(app);
                assertNull(source(app, type).getDocument());
                long cutoff = (System.currentTimeMillis() - 1) << 16;
                Tracker old = Tracker.current.get();
                Tracker.current.set(new Tracker("reindex", MessageType.EVENT, null,
                        ConsumerConfiguration.builder().name("reindex").maxIndexExclusive(cutoff).build(), null));
                try {
                    historical.reindex();
                    if (mode == GraphProjectionMode.AWAIT) {
                        assertEquals(1, Fluxzero.searchGraph(type).match(2, "version").fetchAll().size(),
                                "AWAIT must expose the new projection immediately after reindex");
                    }
                    var written = source(app, type);
                    assertTrue(written.isModelStateVerified());
                    assertNotNull(written.getModelStorageIndex());
                    assertTrue(written.getModelStorageIndex() >= cutoff);
                    assertEquals(events.getStreams(), heads(app).getStreams());
                    assertEquals(events.getStateIndex(), heads(app).getStateIndex());
                    assertTrue(app.documentStore().getSerializer().fromDocument(written.getDocument(), type)
                            .toString().contains("version=2"));
                    for (int i = 0; i < 20; i++) { historical.reindex(); }
                    assertEquals(written.getModelStorageIndex(), source(app, type).getModelStorageIndex());
                    assertTrue(historical.get().toString().contains("version=1"));
                    assertTrue(Fluxzero.loadGraph("node", type).previous().get().toString().contains("version=1"));
                } finally { Tracker.current.set(old); }
                fc.modelRepository().registerGraphProjection(type, true).join();
                io.fluxzero.common.TimingUtils.retryOnFailure(() -> {
                    assertEquals(1, Fluxzero.searchGraph(type).match(2, "version").fetchAll().size());
                    return null;
                }, io.fluxzero.common.RetryConfiguration.builder().maxRetries(100).delay(Duration.ofMillis(10))
                        .errorTest(failure -> failure instanceof AssertionError).throwOnFailingErrorTest(true).build());
                // An unconditional refresh is safe and parallel calls never change the Model stream.
                CompletableFuture.allOf(java.util.stream.IntStream.range(0, 4).mapToObj(i ->
                        CompletableFuture.runAsync(historical::reindex)).toArray(CompletableFuture[]::new)).join();
                assertEquals(events.getStreams(), heads(app).getStreams());
                assertEquals(events.getStateIndex(), heads(app).getStateIndex());
                long normalCutoff = (System.currentTimeMillis() - 1) << 16;
                app.executeModelCommit(new Message(event(type, 3))).join();
                var normal = source(app, type);
                assertTrue(normal.getModelStorageIndex() >= normalCutoff);
                Tracker.current.set(new Tracker("normal", MessageType.EVENT, null,
                        ConsumerConfiguration.builder().name("normal").maxIndexExclusive(normalCutoff).build(), null));
                try {
                    historical.reindex();
                    assertEquals(normal.getModelStorageIndex(), source(app, type).getModelStorageIndex());
                } finally { Tracker.current.set(old); }
                // A later migration boundary must refresh even though the Model's business head is unchanged.
                long nextCutoff = ((normal.getModelStorageIndex() >>> 16) + 1) << 16;
                io.fluxzero.common.TimingUtils.retryOnFailure(() -> {
                    assertTrue((System.currentTimeMillis() << 16) >= nextCutoff);
                    return null;
                }, io.fluxzero.common.RetryConfiguration.builder().maxRetries(100).delay(Duration.ofMillis(10))
                        .errorTest(failure -> failure instanceof AssertionError).throwOnFailingErrorTest(true).build());
                Tracker.current.set(new Tracker("next", MessageType.EVENT, null,
                        ConsumerConfiguration.builder().name("next").maxIndexExclusive(nextCutoff).build(), null));
                try {
                    historical.reindex();
                    var refreshed = source(app, type);
                    assertTrue(refreshed.getModelStorageIndex() >= nextCutoff);
                    assertEquals(normal.getModelHead(), refreshed.getModelHead());
                } finally { Tracker.current.set(old); }
                app.modelRepository().deleteModel("node", ModelDeletionCascade.NONE).join();
                historical.reindex();
                assertNull(source(app, type).getDocument());
                return null;
            });
        }
    }

    @ParameterizedTest @EnumSource(GraphProjectionMode.class)
    void rejectsStaleHeadAndUntrustedSourceAndFutureCutoff(GraphProjectionMode mode) {
        Class<?> type = type(mode);
        try (var app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .build(client("reindex-guards-" + UUID.randomUUID()))) {
            app.apply(fc -> {
                ((DefaultModelRepository) fc.modelRepository()).configureModelTypes(() -> List.of(type));
                seed(app, type, 1);
                var graph = Fluxzero.loadGraph("node", type);
                graph.reindex();
                var old = source(app, type);
                seed(app, type, 2);
                var stale = new ReindexModel(old.getDocument(), old.getModelHead(),
                        ModelDocumentProof.of(old.getDocument(), old.getModelHead()), null);
                assertFalse(fc.client().getEventStoreClient().reindexModel(stale).join());
                // A source left behind by an older writer can be refreshed against a newer authoritative head.
                graph.reindex();
                assertTrue(app.documentStore().getSerializer().fromDocument(source(app, type).getDocument(), type)
                        .toString().contains("version=2"));
                var trusted = source(app, type);
                assertEquals(2L, trusted.getDocument().getTimestamp());
                var untrusted = new io.fluxzero.common.api.search.SerializedDocument(trusted.getDocument().deserializeDocument()
                        .toBuilder().revision(999).build());
                app.client().getSearchClient().index(List.of(untrusted), Guarantee.STORED, false).join();
                assertThrows(Exception.class, graph::reindex);
                assertEquals(999, app.client().getSearchClient().fetch(new GetDocument("node",
                        untrusted.getCollection())).orElseThrow().getDocument().getRevision());

                Tracker previous = Tracker.current.get();
                Tracker.current.set(new Tracker("future", MessageType.EVENT, null,
                        ConsumerConfiguration.builder().name("future").maxIndexExclusive(Long.MAX_VALUE).build(), null));
                try { assertThrows(IllegalArgumentException.class, graph::reindex); }
                finally { Tracker.current.set(previous); }
                return null;
            });
        }
    }

    private static io.fluxzero.common.api.search.GetDocumentResult source(Fluxzero app, Class<?> type) {
        return app.client().getSearchClient().fetchModelDocument(new GetDocument("node",
                EntityMetadata.of(type).modelSourceDocumentCollection("").orElseThrow(), true, true));
    }

    private static GetModelEventsResult heads(Fluxzero app) {
        return app.client().getEventStoreClient().getModelEvents(new GetModelEvents(
                List.of(new ModelEventStreamRequest("node", -1L, 100)), ModelReadBoundary.current(), 0));
    }

    private static Object event(Class<?> type, int version) {
        return switch (type.getSimpleName()) {
            case "ReindexLive" -> new PutLive("node", version);
            case "ReindexAsync" -> new PutAsync("node", version);
            default -> new PutAwait("node", version);
        };
    }

    private static void seed(Fluxzero app, Class<?> type, int version) {
        Object event = event(type, version);
        var target = ModelCommitTarget.builder().modelId("node").modelType(type.getSimpleName())
                .expectedSequenceNumber((long) version - 2).storeEvent(true).updateState(true)
                .relationships(List.of()).build();
        var result = app.client().getEventStoreClient().commitModels(new CommitModels(UUID.randomUUID().toString(),
                version == 1 ? -1 : heads(app).getStateIndex(), List.of("node"),
                List.of(new ModelCommitStep(new Message(event).serialize(app.serializer()), true, List.of(target))),
                ModelConflictPolicy.RETRY, Guarantee.STORED, false)).join();
        assertTrue(result.isAccepted());
    }

    private static Class<?> type(GraphProjectionMode mode) {
        return switch (mode) { case NONE -> ReindexLive.class; case ASYNC -> ReindexAsync.class; case AWAIT -> ReindexAwait.class; };
    }
    @Model(searchable = true, searchSettings = @SearchSettings(timestampPath = "timestamp")) public record ReindexLive(@EntityId String id, int version) { public java.time.Instant getTimestamp() { return java.time.Instant.ofEpochMilli(version); } }
    @Model(searchable = true, searchSettings = @SearchSettings(timestampPath = "timestamp"), graphProjection = @GraphProjection(mode = GraphProjectionMode.ASYNC))
    public record ReindexAsync(@EntityId String id, int version) { public java.time.Instant getTimestamp() { return java.time.Instant.ofEpochMilli(version); } }
    @Model(searchable = true, searchSettings = @SearchSettings(timestampPath = "timestamp"), graphProjection = @GraphProjection(mode = GraphProjectionMode.AWAIT))
    public record ReindexAwait(@EntityId String id, int version) { public java.time.Instant getTimestamp() { return java.time.Instant.ofEpochMilli(version); } }
    public record PutLive(String id, int version) {
        @Apply ReindexLive apply(@jakarta.annotation.Nullable ReindexLive previous) { return new ReindexLive(id, version); }
    }
    public record PutAsync(String id, int version) {
        @Apply ReindexAsync apply(@jakarta.annotation.Nullable ReindexAsync previous) { return new ReindexAsync(id, version); }
    }
    public record PutAwait(String id, int version) {
        @Apply ReindexAwait apply(@jakarta.annotation.Nullable ReindexAwait previous) { return new ReindexAwait(id, version); }
    }
}
