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
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.modeling.*;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.search.client.SearchClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.sdk.test.contracts.ModelSnapshotContract.*;
import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.awaitCacheTracking;
import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.clearCacheAndAwait;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** One-request cold reconstruction, shared by in-process, TestServer and Runtime transports. */
@Timeout(30)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public abstract class ModelReconstructionContract {
    private final String idPrefix = UUID.randomUUID() + "-";
    private final String canonicalId = idPrefix + "canonical", aliasId = idPrefix + "friendly",
            otherId = idPrefix + "other", absentId = idPrefix + "absent";

    protected abstract Client[] clients(String namespace);

    /** May share storage for cases with independent Model IDs; empty namespaces and physical erasure stay separate. */
    protected Client[] clientsForIndependentModels(String namespace) { return clients(namespace); }

    @ParameterizedTest
    @CsvSource({"canonical,250,0", "friendly,250,0", "canonical,252,2", "friendly,252,2",
            "canonical,1,1", "friendly,1,1"})
    void coldReadUsesOneRequest(String id, int history, int payloads) {
        id = idPrefix + id;
        var clients = clientsForIndependentModels("reconstruction-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0])) { seed(writer, history); }
        var observed = new Reads(clients[1], true);
        try (Fluxzero reader = app(observed.client)) {
            assertEquals(new AliasedCounter(canonicalId, aliasId, history), read(reader, id));
            assertEquals(1, observed.combined.get());
            assertEquals(0, observed.events.get());
            assertEquals(0, observed.searches.get());
            assertEquals(payloads, observed.payloads.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"canonical", "friendly"})
    void olderPeerRetainsSeparateReads(String id) {
        id = idPrefix + id;
        var clients = clientsForIndependentModels("legacy-reconstruction-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0])) { seed(writer, 250); }
        var observed = new Reads(clients[1], false);
        try (Fluxzero reader = app(observed.client)) {
            assertEquals(new AliasedCounter(canonicalId, aliasId, 250), read(reader, id));
            assertEquals(0, observed.combined.get());
            assertEquals(id.equals(canonicalId) ? 1 : 2, observed.events.get());
            assertEquals(id.equals(canonicalId) ? 1 : 2, observed.searches.get());
            assertEquals(0, observed.payloads.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"canonical", "friendly"})
    void capabilityLossBeforeSendStillSelectsTheOrdinarySnapshot(String id) {
        id = idPrefix + id;
        var clients = clientsForIndependentModels("reconstruction-downgrade-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0])) { seed(writer, 250); }
        var observed = new Reads(clients[1], true);
        observed.unavailable.set(true);
        try (Fluxzero reader = app(observed.client)) {
            assertEquals(new AliasedCounter(canonicalId, aliasId, 250), read(reader, id));
            assertEquals(1, observed.combined.get(), "attempt is completed locally without sending to the old peer");
            assertEquals(id.equals(canonicalId) ? 1 : 2, observed.events.get());
            assertEquals(id.equals(canonicalId) ? 1 : 2, observed.searches.get());
            assertEquals(0, observed.payloads.get(), "the ordinary snapshot must still be selected");
        }
    }

    @Test
    void customSearchDecoratorKeepsItsSnapshotRoute() {
        var clients = clientsForIndependentModels("reconstruction-custom-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0])) { seed(writer, 250); }
        var observed = new Reads(clients[1], true);
        doAnswer(inv -> observed.delegate.supportsModelReconstruction(inv.getArgument(0)))
                .when(observed.observed).supportsModelReconstruction(any());
        try (Fluxzero reader = app(observed.client)) {
            assertEquals(new AliasedCounter(canonicalId, aliasId, 250), read(reader, aliasId));
            assertEquals(0, observed.combined.get());
            assertEquals(2, observed.searches.get(), "the custom SearchClient must still observe snapshot reads");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {25, 275})
    void strayLiteralSnapshotCannotSeedAnotherAliasOwner(int history) {
        var clients = clientsForIndependentModels("reconstruction-stray-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0])) {
            seed(writer, history);
            var stray = new ModelSnapshotMutation(new Data<>("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "unused.snapshot.type", 0, "application/json"), 0L, 25, -1, null).toDocument(aliasId, 99L, 0L);
            writer.client().getSearchClient().index(List.of(stray), Guarantee.STORED, false).join();
        }
        var observed = new Reads(clients[1], true);
        try (Fluxzero reader = app(observed.client)) {
            assertEquals(new AliasedCounter(canonicalId, aliasId, history), read(reader, aliasId));
            assertEquals(1, observed.combined.get());
            assertEquals(0, observed.events.get());
            assertEquals(0, observed.payloads.get(), "only the canonical owner's snapshot/suffix reaches the SDK");
        }
    }

    @Test
    void deletedPrimaryKeepsPrecedenceAndReplaysOnlyTheDeletionAfterItsSnapshot() {
        var clients = clientsForIndependentModels("reconstruction-deleted-" + UUID.randomUUID());
        try (Fluxzero writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .replacePropertySource(ignored -> new io.fluxzero.common.application.SimplePropertySource(
                        java.util.Map.of("fluxzero.model.aliasTargets", "true"))).build(clients[0])) {
            writer.registerHandlers(AliasedCounter.class);
            seed(writer, 25);
            write(writer, new DeleteAliasedCounter(canonicalId));
            write(writer, new SetAliasedHistory(otherId, canonicalId, 25));
        }
        var observed = new Reads(clients[1], true);
        try (Fluxzero reader = app(observed.client)) {
            assertNull(read(reader, canonicalId));
            assertEquals(1, observed.combined.get());
            assertEquals(1, observed.payloads.get(), "retained snapshot plus deletion, without replaying older events");
            assertEquals(0, observed.events.get());
        }
    }

    public record DeleteAliasedCounter(String id) {
        @io.fluxzero.sdk.persisting.eventsourcing.Apply AliasedCounter apply(AliasedCounter previous) { return null; }
    }

    @Test
    void historicalReadKeepsItsSnapshotBoundary() {
        var clients = clientsForIndependentModels("historical-reconstruction-" + UUID.randomUUID());
        long boundary;
        try (Fluxzero writer = app(clients[0])) {
            seed(writer, 250);
            boundary = writer.client().getEventStoreClient().getModelEvents(new GetModelEvents(
                    List.of(new ModelEventStreamRequest(canonicalId, -1L, 0)), ModelReadBoundary.current(), 0L)).getStateIndex();
            write(writer, new SetAliasedCounter(canonicalId, aliasId, 251));
        }
        var observed = new Reads(clients[1], true);
        try (Fluxzero reader = app(observed.client)) {
            long pinned = boundary;
            assertEquals(new AliasedCounter(canonicalId, aliasId, 250), reader.apply(fc ->
                    fc.modelRepository().loadGraphAt(canonicalId, AliasedCounter.class, pinned, io.fluxzero.sdk.modeling.Graph.Options.DEFAULT).get()));
            assertEquals(0, observed.combined.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cachePublicationRetainsTheFenceFromBeforeTheRead(boolean clear) {
        var clients = clientsForIndependentModels("reconstruction-fence-" + UUID.randomUUID());
        var observed = new Reads(clients[1], true);
        try (Fluxzero writer = app(clients[0]); Fluxzero reader = app(observed.client)) {
            seed(writer, 250);
            awaitCacheTracking(reader);
            if (clear) { observed.afterRead.set(() -> clearCacheAndAwait(reader)); }
            reader.apply(fc -> {
                var first = Fluxzero.loadModel(canonicalId, AliasedCounter.class, false).get();
                var next = Fluxzero.loadModel(canonicalId, AliasedCounter.class, false).get();
                assertEquals(first, next);
                if (clear) { assertNotSame(first, next); } else { assertSame(first, next); }
                return null;
            });
            assertEquals(clear ? 2 : 1, observed.combined.get());
        }
    }

    @Test
    void unreadableSeedFallsBackToReplayAtTheSelectedBoundary() {
        var clients = clientsForIndependentModels("unreadable-reconstruction-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0])) {
            seed(writer, 25);
            var retained = writer.documentStore().search(ModelSnapshotMutation.COLLECTION)
                    .matchFacet(ModelSnapshotMutation.MODEL_ID_FACET, canonicalId)
                    .fetchFirst(io.fluxzero.common.api.search.SerializedDocument.class).orElseThrow();
            var corrupt = new ModelSnapshotMutation(new Data<>("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "missing.snapshot.type", 5, "application/json"), 0L, 25, -1, null).toDocument(canonicalId, 24L, ModelReconstructionLoader.stateIndex(retained));
            writer.client().getSearchClient().index(List.of(corrupt), Guarantee.STORED, false).join();
        }
        var observed = new Reads(clients[1], true);
        try (Fluxzero reader = app(observed.client)) {
            assertEquals(new AliasedCounter(canonicalId, aliasId, 25), read(reader, aliasId));
            assertEquals(1, observed.combined.get());
            assertEquals(1, observed.events.get());
            assertEquals(25, observed.payloads.get());
        }
    }

    @Test
    void primaryIdentityStillWinsOverAnEqualAlias() {
        var clients = clientsForIndependentModels("reconstruction-shadow-" + UUID.randomUUID());
        try (Fluxzero writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .replacePropertySource(ignored -> new io.fluxzero.common.application.SimplePropertySource(
                        java.util.Map.of("fluxzero.model.aliasTargets", "true"))).build(clients[0])) {
            writer.registerHandlers(AliasedCounter.class);
            seed(writer, 250);
            write(writer, new SetAliasedHistory(aliasId, otherId, 25));
        }
        var observed = new Reads(clients[1], true);
        try (Fluxzero reader = app(observed.client)) {
            assertEquals(new AliasedCounter(aliasId, otherId, 25), read(reader, aliasId));
            assertEquals(1, observed.combined.get());
            assertEquals(0, observed.payloads.get());
        }
    }

    @Test
    void suffixContinuesInCanonicalPagesAtTheSelectedBoundary() {
        var clients = clientsForIndependentModels("reconstruction-pages-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0])) {
            seed(writer, 25);
            for (int i = 26; i <= 175; i++) { write(writer, new SetAliasedCounter(canonicalId, aliasId, i)); }
            var snapshots = writer.documentStore().search(ModelSnapshotMutation.COLLECTION)
                    .matchFacet(ModelSnapshotMutation.MODEL_ID_FACET, canonicalId)
                    .fetchAll(io.fluxzero.common.api.search.SerializedDocument.class);
            for (var snapshot : snapshots) {
                if (ModelReconstructionLoader.sequence(snapshot) > 24L) {
                    writer.documentStore().deleteDocument(snapshot.getId(), ModelSnapshotMutation.COLLECTION, Guarantee.STORED).join();
                }
            }
        }
        var observed = new Reads(clients[1], true);
        try (Fluxzero reader = app(observed.client)) {
            assertEquals(new AliasedCounter(canonicalId, aliasId, 175), read(reader, aliasId));
            assertEquals(1, observed.combined.get());
            assertEquals(1, observed.events.get(), "the suffix continues after the first 128 memberships");
            assertEquals(150, observed.payloads.get());
            assertEquals(0, observed.searches.get());
        }
    }

    @Test
    void missingModelReturnsEmptyWithoutSnapshotSearchFromSdk() {
        var clients = clients("reconstruction-missing-" + UUID.randomUUID());
        if (clients[0] != clients[1]) { clients[0].shutDown(); }
        var observed = new Reads(clients[1], true);
        try (Fluxzero reader = app(observed.client)) {
            assertNull(read(reader, absentId));
            assertEquals(1, observed.combined.get());
            assertEquals(0, observed.searches.get());
        }
    }

    private void seed(Fluxzero writer, int history) {
        int end = Math.max(1, history - history % 25);
        write(writer, new SetAliasedHistory(canonicalId, aliasId, end));
        for (int i = end + 1; i <= history; i++) { write(writer, new SetAliasedCounter(canonicalId, aliasId, i)); }
    }
    private static Fluxzero app(Client client) {
        Fluxzero result = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client);
        result.registerHandlers(AliasedCounter.class);
        return result;
    }
    private static Object read(Fluxzero app, String id) {
        return app.apply(fc -> Fluxzero.loadModel(id, AliasedCounter.class, false).get());
    }
    private static void write(Fluxzero app, Object command) {
        app.apply(fc -> fc.executeModelCommit(new Message(command)).join());
    }

    private static final class Reads {
        final Client client;
        final AtomicInteger combined = new AtomicInteger(), events = new AtomicInteger(), searches = new AtomicInteger(),
                payloads = new AtomicInteger();
        final AtomicReference<Runnable> afterRead = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean unavailable = new java.util.concurrent.atomic.AtomicBoolean();
        final EventStoreClient observed, delegate;
        Reads(Client storage, boolean capable) {
            client = mock(Client.class, delegatesTo(storage));
            delegate = storage.getEventStoreClient();
            observed = mock(EventStoreClient.class, delegatesTo(delegate));
            doAnswer(inv -> capable && delegate.supportsModelReconstruction(storage.getSearchClient())).when(observed).supportsModelReconstruction(any());
            doAnswer(inv -> {
                combined.incrementAndGet();
                var request = (GetModelReconstruction) inv.getArgument(0);
                var result = unavailable.get() ? GetModelReconstructionResult.unsupportedPeer(request.getRequestId())
                        : delegate.getModelReconstruction(request);
                if (result.isSupported()) { payloads.addAndGet(result.getEvents().getPayloads().size()); }
                Runnable action = afterRead.getAndSet(null); if (action != null) { action.run(); }
                return result;
            }).when(observed).getModelReconstruction(any());
            doAnswer(inv -> {
                events.incrementAndGet(); var result = delegate.getModelEvents(inv.getArgument(0));
                payloads.addAndGet(result.getPayloads().size()); return result;
            }).when(observed).getModelEvents(any());
            doReturn(observed).when(client).getEventStoreClient();
            var search = storage.getSearchClient(); var observedSearch = mock(SearchClient.class, delegatesTo(search));
            doAnswer(inv -> { searches.incrementAndGet(); return search.search(inv.getArgument(0), inv.getArgument(1)); })
                    .when(observedSearch).search(any(), anyInt());
            doReturn(observedSearch).when(client).getSearchClient();
            doAnswer(inv -> inv.getArgument(0) == null ? client : storage.forNamespace(inv.getArgument(0)))
                    .when(client).forNamespace(any());
        }
    }
}
