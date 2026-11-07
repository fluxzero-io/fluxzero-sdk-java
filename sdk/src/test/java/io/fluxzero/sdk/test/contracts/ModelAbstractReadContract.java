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

import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.ModelSnapshotMutation;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Alias;
import io.fluxzero.sdk.tracking.handling.Association;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelPersistence;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.awaitCacheTracking;
import static io.fluxzero.sdk.persisting.repository.ModelCacheTestSupport.clearCacheAndAwait;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/** Stored-type selection for abstract Model reads, shared by in-process and WebSocket storage. */
@Timeout(30)
public abstract class ModelAbstractReadContract {
    protected abstract Client[] clients(String namespace);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concreteCachePublicationIsFencedBeforeTypeSelection(boolean invalidate) {
        Client[] clients = clients("abstract-cache-" + UUID.randomUUID());
        var observed = new Observed(clients[1]);
        try (Fluxzero writer = app(clients[0]); Fluxzero reader = app(observed.client)) {
            write(writer, new PutCachedCounter("counter", 1));
            awaitCacheTracking(reader);
            if (invalidate) { observed.afterRead.set(() -> clearCacheAndAwait(reader)); }
            reader.apply(fc -> {
                var first = Fluxzero.loadModel("counter", Contract.class, false);
                var next = Fluxzero.loadModel("counter", CachedCounter.class, false);
                assertEquals(new CachedCounter("counter", 1), first.get());
                assertEquals(first.get(), next.get());
                if (invalidate) {
                    assertNotSame(first.get(), next.get(), "a clear during type selection must fence publication");
                } else {
                    assertSame(first.get(), next.get(), "the concrete Model's cache configuration must apply");
                }
                return null;
            });
        }
    }

    @Test
    void independentReaderUsesTheConcreteSnapshotConfiguration() {
        Client[] clients = clients("abstract-snapshot-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0])) {
            write(writer, new PutCounter("counter", 1));
            write(writer, new PutCounter("counter", 2));
            assertEquals(2, writer.documentStore().search(ModelSnapshotMutation.COLLECTION).count());
        }
        var observed = new Observed(clients[1]);
        try (Fluxzero reader = app(observed.client)) {
            reader.apply(fc -> {
                var value = Fluxzero.loadModel("counter", Contract.class, false);
                assertEquals(new Counter("counter", 2), value.get());
                assertEquals(Counter.class, value.type());
                return null;
            });
            assertEquals(0, observed.payloads(), "the concrete snapshot must replace replay");
        }
    }

    @Test
    void snapshotPublishedAfterTypeSelectionCannotAdvanceTheRead() {
        Client[] clients = clients("abstract-boundary-" + UUID.randomUUID());
        var observed = new Observed(clients[1]);
        try (Fluxzero writer = app(clients[0]); Fluxzero reader = app(observed.client)) {
            write(writer, new PutCounter("counter", 1));
            observed.afterRead.set(() -> write(writer, new PutCounter("counter", 2)));
            reader.apply(fc -> {
                assertEquals(new Counter("counter", 1), Fluxzero.loadModel("counter", Contract.class, false).get());
                return null;
            });
            assertNull(observed.afterRead.get(), "the writer must run after the metadata response");
            assertEquals(0, observed.payloads());
            reader.apply(fc -> {
                assertEquals(new Counter("counter", 2), Fluxzero.loadModel("counter", Contract.class, false).get());
                return null;
            });
        }
    }

    @Test
    void batchReadsResolveEachConcreteTypeAndKeepTheRequestedOrder() {
        Client[] clients = clients("abstract-batch-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0]); Fluxzero reader = app(clients[1])) {
            write(writer, new PutCounter("counter", 1));
            write(writer, new PutOther("other", 2));
            reader.apply(fc -> {
                var values = fc.modelRepository().loadAll(List.of("other", "counter"), Contract.class, false);
                assertEquals(List.of(new Other("other", 2), new Counter("counter", 1)),
                        values.stream().map(value -> value.get()).toList());
                assertEquals(List.of(Other.class, Counter.class), values.stream().map(value -> value.type()).toList());
                return null;
            });
        }
    }

    @Test
    void concreteDocumentPersistenceRemainsAuthoritative() {
        Client[] clients = clients("abstract-document-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0]); Fluxzero reader = app(clients[1])) {
            write(writer, new PutDocument("document", 3));
            reader.apply(fc -> {
                var value = Fluxzero.loadModel("document", Contract.class, false);
                assertEquals(new Document("document", 3), value.get());
                assertEquals(Document.class, value.type());
                return null;
            });
        }
    }

    @Test
    void cachedDocumentAliasCanBeReadThroughItsContract() {
        Client[] clients = clients("abstract-document-alias-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0]); Fluxzero reader = app(clients[1])) {
            write(writer, new PutAliasedDocument("document", "friendly", 3));
            awaitCacheTracking(reader);
            reader.apply(fc -> {
                var value = Fluxzero.loadModel("friendly", Contract.class, false);
                assertEquals(new AliasedDocument("document", "friendly", 3), value.get());
                assertEquals(AliasedDocument.class, value.type());
                return null;
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void documentMovingDuringTypeSelectionIsRetriedAsACurrentRead(boolean mixedBatch) {
        Client[] clients = clients("abstract-document-moving-" + UUID.randomUUID());
        var observed = new Observed(clients[1]);
        try (Fluxzero writer = app(clients[0]); Fluxzero reader = app(observed.client)) {
            write(writer, new PutDocument("document", 1));
            if (mixedBatch) { write(writer, new PutCounter("counter", 1)); }
            observed.afterRead.set(() -> {
                if (mixedBatch) { write(writer, new PutCounter("counter", 2)); }
                write(writer, new PutDocument("document", 2));
            });
            reader.apply(fc -> {
                if (mixedBatch) {
                    assertEquals(List.of(new Counter("counter", 2), new Document("document", 2)),
                            fc.modelRepository().loadAll(List.of("counter", "document"), Contract.class, false)
                                    .stream().map(value -> value.get()).toList());
                } else {
                    assertEquals(new Document("document", 2), Fluxzero.loadModel("document", Contract.class, false).get());
                }
                return null;
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interfaceReadDependencyCanBeReplayedAfterACommit(boolean delete) {
        Client[] clients = clients("abstract-dependency-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0]); Fluxzero reader = app(clients[1])) {
            write(writer, new PutOther("owner", 1));
            write(writer, new PutDependent("dependent", "owner"));
            write(writer, delete ? new DeleteOther("owner") : new PutOther("owner", 2));
            reader.apply(fc -> {
                assertEquals(new Dependent("dependent", 1),
                        Fluxzero.loadModel("dependent", Dependent.class, false).get());
                assertEquals(delete ? null : new Other("owner", 2),
                        Fluxzero.loadModel("owner", Contract.class, false).get());
                return null;
            });
        }
    }

    @Test
    void aliasOwnerMovingAfterTypeSelectionKeepsTheSelectedBoundary() {
        Client[] clients = clients("abstract-alias-move-" + UUID.randomUUID());
        var observed = new Observed(clients[1]);
        try (Fluxzero writer = app(clients[0]); Fluxzero reader = app(observed.client)) {
            write(writer, new PutAliasedCounter("first", "friendly", 1));
            observed.afterRead.set(() -> {
                write(writer, new PutAliasedCounter("first", "released", 2));
                write(writer, new PutAliasedCounter("second", "friendly", 3));
            });
            reader.apply(fc -> {
                assertEquals(new AliasedCounter("first", "friendly", 1),
                        Fluxzero.loadModel("friendly", Contract.class, false).get());
                assertEquals(new AliasedCounter("second", "friendly", 3),
                        Fluxzero.loadModel("friendly", Contract.class, false).get());
                return null;
            });
        }
    }

    @Test
    void incompatibleStoredTypeIsRejectedBeforePayloadReplay() {
        Client[] clients = clients("abstract-type-" + UUID.randomUUID());
        var observed = new Observed(clients[1]);
        try (Fluxzero writer = app(clients[0]); Fluxzero reader = app(observed.client)) {
            write(writer, new PutUnrelated("unrelated"));
            reader.apply(fc -> {
                var failure = assertThrows(EventSourcingException.class,
                        () -> Fluxzero.loadModel("unrelated", Contract.class, false));
                assertTrue(failure.getMessage().contains("stored type"));
                return null;
            });
            assertEquals(0, observed.payloads());
        }
    }

    @Test
    void absentModelRetainsTheRequestedContract() {
        Client[] clients = clients("abstract-absent-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0]); Fluxzero reader = app(clients[1])) {
            reader.apply(fc -> {
                var value = Fluxzero.loadModel("missing", Contract.class, false);
                assertTrue(value.isEmpty());
                assertEquals(Contract.class, value.type());
                return null;
            });
        }
    }

    private static Fluxzero app(Client client) {
        var app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client);
        app.registerHandlers(Counter.class, CachedCounter.class, Other.class, Document.class,
                AliasedDocument.class, AliasedCounter.class, Dependent.class, Unrelated.class);
        return app;
    }

    private static void write(Fluxzero app, Object command) {
        app.apply(fc -> fc.executeModelCommit(new Message(command)).join());
    }

    @Model(cached = false)
    public interface Contract { @EntityId String id(); }

    @Model(snapshotPeriod = 1, maxSnapshotCount = -1, cached = false)
    public record Counter(@EntityId String id, int value) implements Contract {}

    @Model
    public record CachedCounter(@EntityId String id, int value) implements Contract {}

    @Model(cached = false)
    public record Other(@EntityId String id, int value) implements Contract {}

    @Model(persistence = ModelPersistence.DOCUMENT, cached = false)
    public record Document(@EntityId String id, int value) implements Contract {}

    @Model(persistence = ModelPersistence.DOCUMENT)
    public record AliasedDocument(@EntityId String id, @Alias String alias, int value) implements Contract {}

    @Model(cached = false)
    public record AliasedCounter(@EntityId String id, @Alias String alias, int value) implements Contract {}

    @Model(cached = false)
    public record Dependent(@EntityId String id, int value) {}

    @Model(cached = false)
    public record Unrelated(@EntityId String id) {}

    public record PutCounter(String id, int value) {
        @Apply Counter apply(@Nullable Counter previous) { return new Counter(id, value); }
    }
    public record PutCachedCounter(String id, int value) {
        @Apply CachedCounter apply() { return new CachedCounter(id, value); }
    }
    public record PutOther(String id, int value) {
        @Apply Other apply(@Nullable Other previous) { return new Other(id, value); }
    }
    public record DeleteOther(String id) {
        @Apply Other apply(Other previous) { return null; }
    }
    public record PutDocument(String id, int value) {
        @Apply Document apply(@Nullable Document previous) { return new Document(id, value); }
    }
    public record PutAliasedDocument(String id, String alias, int value) {
        @Apply AliasedDocument apply() { return new AliasedDocument(id, alias, value); }
    }
    public record PutAliasedCounter(String id, String alias, int value) {
        @Apply AliasedCounter apply(@Nullable AliasedCounter previous) { return new AliasedCounter(id, alias, value); }
    }
    public record PutDependent(String id, String ownerId) {
        @Apply Dependent apply(@Association("ownerId") Contract owner) {
            return new Dependent(id, ((Other) owner).value());
        }
    }
    public record PutUnrelated(String id) {
        @Apply Unrelated apply() { return new Unrelated(id); }
    }

    private static final class Observed {
        final Client client;
        final List<GetModelEventsResult> responses = new CopyOnWriteArrayList<>();
        final AtomicReference<Runnable> afterRead = new AtomicReference<>();

        Observed(Client storage) {
            client = mock(Client.class, delegatesTo(storage));
            EventStoreClient events = storage.getEventStoreClient();
            EventStoreClient observed = mock(EventStoreClient.class, delegatesTo(events));
            doAnswer(invocation -> {
                GetModelEventsResult response = events.getModelEvents(invocation.getArgument(0));
                responses.add(response);
                Runnable action = afterRead.getAndSet(null);
                if (action != null) { action.run(); }
                return response;
            }).when(observed).getModelEvents(any());
            doReturn(observed).when(client).getEventStoreClient();
            doAnswer(invocation -> invocation.getArgument(0) == null ? client
                    : storage.forNamespace(invocation.getArgument(0))).when(client).forNamespace(any());
        }

        long payloads() {
            return responses.stream().mapToLong(response -> response.getPayloads().size()
                    + response.getPayloadStateIndices().length).sum();
        }
    }
}
