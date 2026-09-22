/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.sdk.modeling;

import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.persisting.repository.ModelGraphResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

class CurrentDocumentGraphRaceTest {
    @ParameterizedTest
    @CsvSource({"false,create", "true,create", "false,replace", "true,replace", "false,delete", "true,delete"})
    void freshCurrentValueRetriesTheWholeRead(boolean explicitCurrent, String change) {
        try (var test = new Harness()) {
            if (!change.equals("create")) {
                write(test.writer, new CreateDocument("doc"));
            }
            test.afterHead.set(() -> write(test.writer, switch (change) {
                case "create" -> new CreateDocument("doc");
                case "replace" -> new ReplaceDocument("doc");
                default -> new DeleteDocument("doc");
            }));
            test.reader.apply(fc -> {
                Graph<CurrentDocument> graph = explicitCurrent
                        ? Fluxzero.loadCurrentGraph("doc", CurrentDocument.class)
                        : Fluxzero.loadGraph("doc", CurrentDocument.class);
                // An absent head can already establish coherent absence without loading a document.
                CurrentDocument expected = change.equals("delete") || explicitCurrent && change.equals("create")
                        ? null : new CurrentDocument("doc", change.equals("replace") ? 2 : 1);
                assertEquals(expected, graph.get());
                assertEquals(expected, graph.get());
                return null;
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedChangesFailAfterExactlyEightAttempts(boolean explicitCurrent) {
        try (var test = new Harness()) {
            write(test.writer, new CreateDocument("doc"));
            Runnable movingDocument = new Runnable() {
                @Override public void run() {
                    write(test.writer, new ReplaceDocument("doc"));
                    test.afterHead.set(this);
                }
            };
            test.afterHead.set(movingDocument);
            var failure = assertThrows(EventSourcingException.class, () -> test.reader.apply(fc -> explicitCurrent
                    ? Fluxzero.loadCurrentGraph("doc", CurrentDocument.class).get()
                    : Fluxzero.loadGraph("doc", CurrentDocument.class).get()));
            assertEquals(EventSourcingException.class, failure.getClass());
            assertTrue(failure.getMessage().contains("after 8 attempts"));
            assertEquals(8, test.headReads.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"replace", "delete"})
    void explicitCurrentRootKeepsItsValueAfterTheFactoryReturns(String change) {
        try (var test = new Harness()) {
            write(test.writer, new CreateDocument("doc"));
            var graph = test.reader.apply(fc -> Fluxzero.loadCurrentGraph("doc", CurrentDocument.class));
            long boundary = graph.stateIndex();
            write(test.writer, change.equals("delete") ? new DeleteDocument("doc") : new ReplaceDocument("doc"));
            assertEquals(new CurrentDocument("doc", 1), graph.get());
            assertEquals(boundary, graph.stateIndex());
            assertEquals(1, test.headReads.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pinnedCurrentAndHistoricalReadsDoNotAdvance(boolean historical) {
        try (var test = new Harness()) {
            write(test.writer, new CreateDocument("doc"));
            long boundary = test.writer.apply(fc -> Fluxzero.loadCurrentGraph("doc", CurrentDocument.class).stateIndex());
            test.afterHead.set(() -> write(test.writer, new ReplaceDocument("doc")));
            if (historical) {
                var value = test.reader.apply(fc -> ((ModelGraphResolver) fc.modelRepository()).loadGraphValue(
                        "doc", true, CurrentDocument.class, ModelReadBoundary.at(boundary), true));
                // Where the retained stream still proves the old value, historical reconstruction may succeed.
                assertEquals(new CurrentDocument("doc", 1), value.entity().get());
                assertEquals(boundary, value.boundary().stateIndex());
            } else {
                assertThrows(EventSourcingException.class, () -> test.reader.apply(fc ->
                        ((ModelGraphResolver) fc.modelRepository()).loadGraphValue("doc", true, CurrentDocument.class,
                                ModelReadBoundary.at(boundary), false)));
                assertEquals(1, test.headReads.get());
            }
        }
    }

    @Test
    void observedLazyIdentityDoesNotAdvanceItsDocumentRevision() {
        try (var test = new Harness()) {
            write(test.writer, new CreateDocument("doc"));
            var graph = test.reader.apply(fc -> Fluxzero.loadGraph("doc", CurrentDocument.class));
            long revision = graph.revisionStateIndex();
            write(test.writer, new ReplaceDocument("doc"));
            assertThrows(EventSourcingException.class, graph::get);
            assertEquals(revision, graph.revisionStateIndex());
            assertEquals(1, test.headReads.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mutationReadNeverRetriesOnlyItsDocumentGraph(boolean explicitCurrent) {
        try (var test = new Harness()) {
            write(test.writer, new CreateDocument("doc"));
            test.beforeCommit.set(() -> write(test.writer, new ReplaceDocument("doc")));
            var firstObserved = new AtomicInteger();
            write(test.reader, new ReadIntoReceipt("receipt", explicitCurrent, firstObserved));
            assertEquals(1, firstObserved.get());
            var receipt = test.reader.apply(fc -> Fluxzero.loadModel("receipt", Receipt.class));
            assertEquals(new Receipt("receipt", 2), receipt.get());
            assertEquals(0L, receipt.sequenceNumber());
            assertEquals(2, test.commits.get());
            assertEquals(new CurrentDocument("doc", 2),
                    test.writer.apply(fc -> Fluxzero.loadCurrentGraph("doc", CurrentDocument.class).get()));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unrelatedDocumentFailuresAreNotRetried(boolean explicitCurrent) {
        try (var test = new Harness()) {
            write(test.writer, new CreateDocument("doc"));
            var failure = new IllegalArgumentException("document decoder rejected the value");
            test.documentFailure = failure;
            assertSame(failure, assertThrows(IllegalArgumentException.class, () -> test.reader.apply(fc -> explicitCurrent
                    ? Fluxzero.loadCurrentGraph("doc", CurrentDocument.class).get()
                    : Fluxzero.loadGraph("doc", CurrentDocument.class).get())));
            assertEquals(1, test.headReads.get());
        }
    }

    @Test
    void currentShortcutCapturesANewDocumentWithoutChangingTheOriginalGraph() {
        try (var test = new Harness()) {
            write(test.writer, new CreateDocument("doc"));
            var old = test.reader.apply(fc -> Fluxzero.loadCurrentGraph("doc", CurrentDocument.class));
            test.afterHead.set(() -> write(test.writer, new ReplaceDocument("doc")));
            var current = old.current();
            assertEquals(new CurrentDocument("doc", 2), current.get());
            assertEquals(new CurrentDocument("doc", 1), old.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturedOverlayDoesNotReadTheSupersededDocument(boolean deleted) {
        try (var test = new Harness()) {
            write(test.writer, new CreateDocument("doc"));
            if (deleted) { write(test.writer, new DeleteDocument("doc")); }
            Entity<CurrentDocument> staged = test.writer.apply(fc -> Fluxzero.loadModel("doc", CurrentDocument.class));
            if (deleted) { write(test.writer, new CreateDocument("doc")); }
            else { write(test.writer, new ReplaceDocument("doc")); }
            var repository = spy((DefaultModelRepository) test.reader.modelRepository());
            var snapshot = ModelBatchScope.Snapshot.EMPTY.withValues(Map.of("doc", staged));
            doReturn(snapshot).when(repository).graphStagedValues(any());
            test.rejectDocumentRead = true;
            var graph = Graphs.lazyCurrent("doc", CurrentDocument.class, repository);
            assertEquals(staged.get(), graph.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryResolvesTheOriginalAliasAgain(boolean explicitCurrent) {
        try (var test = new Harness()) {
            write(test.writer, new PutAlias("first", "lookup"));
            write(test.writer, new PutAlias("second", null));
            test.gateId = "lookup";
            test.afterHead.set(() -> {
                write(test.writer, new PutAlias("first", null));
                write(test.writer, new PutAlias("second", "lookup"));
            });
            test.reader.apply(fc -> {
                Graph<AliasDocument> graph = explicitCurrent
                        ? Fluxzero.loadCurrentGraph("lookup", AliasDocument.class)
                        : Fluxzero.loadGraph("lookup", AliasDocument.class);
                assertEquals(new AliasDocument("second", "lookup"), graph.get());
                assertEquals("alias-second", graph.id());
                return null;
            });
        }
    }

    private static class Harness implements AutoCloseable {
        final AtomicReference<Runnable> afterHead = new AtomicReference<>();
        final AtomicReference<Runnable> beforeCommit = new AtomicReference<>();
        final AtomicInteger headReads = new AtomicInteger();
        final AtomicInteger commits = new AtomicInteger();
        String gateId = "doc";
        boolean rejectDocumentRead;
        RuntimeException documentFailure;
        final Fluxzero writer;
        final Fluxzero reader;

        Harness() {
            var storage = LocalClient.newInstance(null);
            var readerClient = spy(storage);
            var readerStore = spy(storage.getEventStoreClient());
            var readerSearch = spy(storage.getSearchClient());
            doReturn(readerStore).when(readerClient).getEventStoreClient();
            doReturn(readerSearch).when(readerClient).getSearchClient();
            doAnswer(invocation -> {
                commits.incrementAndGet();
                Runnable write = beforeCommit.getAndSet(null);
                if (write != null) { independently(write); }
                return storage.getEventStoreClient().commitModels(invocation.getArgument(0));
            }).when(readerStore).commitModels(any());
            doAnswer(invocation -> {
                if (rejectDocumentRead) { throw new AssertionError("Superseded document must not be read"); }
                if (documentFailure != null) { throw documentFailure; }
                return storage.getSearchClient().fetchModelDocument(invocation.getArgument(0));
            }).when(readerSearch).fetchModelDocument(any());
            doAnswer(invocation -> {
                GetModelEvents request = invocation.getArgument(0);
                var response = storage.getEventStoreClient().getModelEvents(request);
                headReads.incrementAndGet();
                Runnable write = request.getRequests().stream().anyMatch(stream -> stream.getModelId().equals(gateId))
                        ? afterHead.getAndSet(null) : null;
                if (write != null) { independently(write); }
                return response;
            }).when(readerStore).getModelEvents(any());
            writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(storage);
            reader = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(readerClient);
        }

        @Override public void close() { reader.close(); writer.close(); }
    }

    private static void write(Fluxzero app, Object command) {
        app.apply(fc -> fc.executeModelCommit(Message.asMessage(command)).join());
    }

    private static void independently(Runnable action) {
        CompletableFuture.runAsync(action, task -> Thread.ofVirtual().name("document-race-writer").start(task)).join();
    }

    @Model(persistence = ModelPersistence.DOCUMENT)
    record CurrentDocument(@EntityId String id, int version) {}

    record CreateDocument(String id) {
        @Apply CurrentDocument apply() { return new CurrentDocument(id, 1); }
    }

    record ReplaceDocument(String id) {
        @Apply CurrentDocument apply(CurrentDocument current) { return new CurrentDocument(id, current.version() + 1); }
    }

    record DeleteDocument(String id) {
        @Apply CurrentDocument apply(CurrentDocument current) { return null; }
    }

    @Model record Receipt(@EntityId String id, int version) {}

    record ReadIntoReceipt(String id, boolean explicitCurrent, AtomicInteger firstObserved) {
        @Apply Receipt apply() {
            var graph = explicitCurrent ? Fluxzero.loadCurrentGraph("doc", CurrentDocument.class)
                    : Fluxzero.loadGraph("doc", CurrentDocument.class);
            int version = graph.get().version();
            firstObserved.compareAndSet(0, version);
            return new Receipt(id, version);
        }
    }

    @Model(persistence = ModelPersistence.DOCUMENT)
    record AliasDocument(@EntityId(prefix = "alias-") String id, @Alias String alias) {}

    record PutAlias(String id, String alias) {
        @Apply AliasDocument apply(@jakarta.annotation.Nullable AliasDocument previous) {
            return new AliasDocument(id, alias);
        }
    }
}
