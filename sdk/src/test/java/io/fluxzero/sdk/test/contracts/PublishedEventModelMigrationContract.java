/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.test.contracts;

import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.common.api.search.GetDocument;
import io.fluxzero.common.api.search.GetModelMigrations;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.PublishedEventModelMigration;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.*;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.MessageType.COMMAND;
import static io.fluxzero.common.api.tracking.SegmentRange.MAX_SEGMENT;
import static org.junit.jupiter.api.Assertions.*;

/** The same live replay/search/cutover contract runs against LocalClient and PostgreSQL/WebSocket. */
@Timeout(30)
public abstract class PublishedEventModelMigrationContract {
    private static final Instant DELETION_TIME = Instant.parse("2020-01-02T03:04:05Z");

    protected abstract Client[] clients(String namespace);

    @Test
    void maintainsLatestSearchAndGraphsWhileLegacyWritesContinue() throws Exception {
        Client[] clients = clients("live-migration-" + UUID.randomUUID());
        Client storage = clients[0];
        var serializer = new JacksonSerializer();
        try (var reader = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .configureAutomaticModelHandling(AutomaticModelHandling.DISABLED).build(new BorrowedClient(clients[1]))) {
            // This document belongs to the old application and must survive every replay phase untouched.
            var legacy = serializer.toDocument(new Root("root", "legacy"), "root", "legacy-roots",
                    null, null, Metadata.empty());
            storage.getSearchClient().index(List.of(legacy), STORED, false).join();
            reader.registerHandlers(Root.class, Child.class);
            long lastEvent;
            long childRevision;
            try (var migration = migration(storage)) {
                var replay = migration.replay();
                lastEvent = append(storage, serializer, new SetRoot("root", "first"));
                lastEvent = append(storage, serializer, new SetChild("child", "root", "first"));
                awaitBoundary(storage, migration.name(), lastEvent);
                assertCurrent(reader, "first", "first");
                assertTrue(storage.getSearchClient().getModelMigrations(new GetModelMigrations(10)).getMigrations().isEmpty());
                childRevision = reader.modelRepository().loadCurrentState("child", Child.class).head().getSequenceNumber();
                replay.cancel();
            }
            // A new process repeats the already accepted source range. No Model transition or publication repeats.
            storage.getTrackingClient(EVENT).resetPosition("live-model-migration", 0L).join();
            try (var migration = migration(storage)) {
                Registration replay = migration.replay();
                awaitBoundary(storage, migration.name(), lastEvent);
                assertCurrent(reader, "first", "first");
                assertEquals(childRevision,
                        reader.modelRepository().loadCurrentState("child", Child.class).head().getSequenceNumber());
                lastEvent = append(storage, serializer, new SetChild("child", "root", "second"));
                awaitBoundary(storage, migration.name(), lastEvent);
                assertCurrent(reader, "first", "second");
                lastEvent = append(storage, serializer, new SetRoot("root", "second"));
                awaitBoundary(storage, migration.name(), lastEvent);
                assertCurrent(reader, "second", "second");
                lastEvent = append(storage, serializer, new SetChild("child", "root", null));
                awaitBoundary(storage, migration.name(), lastEvent);
                assertCurrent(reader, "second", null);
                assertTrue(reader.modelRepository().loadCurrentState("child", Child.class).head().isDeleted());
                assertEquals(5, storage.getTrackingClient(EVENT).readFromIndex(0L, 100).size(),
                        "Replay must never republish the five original events");
                assertEquals(legacy.deserializeDocument(), storage.getSearchClient().fetch(new GetDocument("root", "legacy-roots"))
                        .orElseThrow().deserializeDocument());
                replay.cancel();
            }
            // Phase three changes the writer, without copying/adopting already live Model documents.
            reader.apply(f -> { Fluxzero.assertAndApply(new SetRoot("root", "native")); return null; });
            assertCurrent(reader, "native", null);
        } finally {
            Arrays.stream(clients).distinct().forEach(Client::shutDown);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesCascadeDeletionWhenReplayingTheSameHistoryIntoEmptyStorage(boolean accept) throws Exception {
        Client[] source = clients("cascade-source-" + UUID.randomUUID());
        Client[] destination = clients("cascade-destination-" + UUID.randomUUID());
        Class<?>[] models = {Root.class, Child.class, RetainedChild.class, Grandchild.class};
        try {
            List<SerializedMessage> history;
            try (var writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                    .build(new BorrowedClient(source[0]))) {
                writer.withClock(Clock.fixed(DELETION_TIME, ZoneOffset.UTC));
                writer.registerHandlers(models);
                writer.apply(f -> {
                    Fluxzero.assertAndApply(new SetRoot("root", "parent"));
                    Fluxzero.assertAndApply(new SetChild("child", "root", "owned"));
                    Fluxzero.assertAndApply(new SetRetainedChild("retained", "root"));
                    Fluxzero.assertAndApply(new SetGrandchild("grandchild", "child"));
                    Fluxzero.assertAndApply(accept ? new AcceptDeleteRoot("root") : new DeleteRoot("root"));
                    assertCascadeState();
                    return null;
                });
                history = source[0].getTrackingClient(EVENT).readFromIndex(0L, 100);
                assertEquals(5, history.size(), "Cascade deletions must not publish extra domain events");
            }
            destination[0].getGatewayClient(EVENT).append(STORED,
                    history.stream().map(event -> new SerializedMessage(
                            event.getData(), event.getMetadata(), event.getMessageId(), event.getTimestamp())).toArray(
                            SerializedMessage[]::new)).join();
            long boundary = destination[0].getTrackingClient(EVENT).readFromIndex(0L, 100).getLast().getIndex();
            List<Long> migratedStateIndices = null;
            for (int pass = 0; pass < 2; pass++) {
                // Reprocess the identical source range after restarting, with fresh application caches.
                destination[0].getTrackingClient(EVENT).resetPosition("cascade-migration", 0L).join();
                try (var migration = PublishedEventModelMigration.builder().name("cascade-migration")
                        .client(new BorrowedClient(destination[0])).modelTypes(models).build()) {
                    migration.replay();
                    awaitBoundary(destination[0], migration.name(), boundary);
                }
                try (var reader = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                        .build(new BorrowedClient(destination[1]))) {
                    reader.registerHandlers(models);
                    reader.apply(f -> { assertCascadeState(); return null; });
                    var stateIndices = List.of(
                            reader.modelRepository().loadCurrentState("root", Root.class).head().getStateIndex(),
                            reader.modelRepository().loadCurrentState("child", Child.class).head().getStateIndex());
                    if (migratedStateIndices == null) { migratedStateIndices = stateIndices; }
                    else { assertEquals(migratedStateIndices, stateIndices, "Restart must not write new revisions"); }
                }
                assertEquals(5, destination[0].getTrackingClient(EVENT).readFromIndex(0L, 100).size());
            }
        } finally {
            Stream.concat(Arrays.stream(source), Arrays.stream(destination))
                    .distinct().forEach(Client::shutDown);
        }
    }

    @Test
    void historicalDeletionEventIncludesItsCascadeFromAFreshReader() throws Exception {
        Client[] clients = clients("historical-cascade-" + UUID.randomUUID());
        var serializer = new JacksonSerializer();
        Class<?>[] models = {HistoricalParent.class, HistoricalChild.class, HistoricalRetained.class};
        try {
            append(clients[0], serializer, new CreateHistoricalParent("parent"));
            append(clients[0], serializer, new CreateHistoricalChild("owned", "parent"));
            append(clients[0], serializer, new CreateHistoricalRetained("retained", "parent"));
            long deletion = append(clients[0], serializer, new DeleteHistoricalParent("parent"));
            try (var migration = PublishedEventModelMigration.builder().name("historical-cascade")
                    .client(new BorrowedClient(clients[0])).modelTypes(models).build()) {
                migration.replay();
                awaitBoundary(clients[0], migration.name(), deletion);
            }
            try (var reader = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                    .build(new BorrowedClient(clients[1]))) {
                reader.registerHandlers(models);
                var repository = reader.modelRepository();
                assertHistoricalDeletion(repository, deletion);
                repository.followPublishedEventMigration("historical-cascade");
                var event = serializer.deserializeMessage(
                        clients[0].getTrackingClient(EVENT).readFromIndex(0L, 100).getLast(), EVENT);
                event.apply(ignored -> reader.apply(f -> {
                    assertNull(Fluxzero.loadGraph("owned", HistoricalChild.class).get());
                    return null;
                }));
            }
            assertEquals(4, clients[0].getTrackingClient(EVENT).readFromIndex(0L, 100).size());
            long recreated = append(clients[0], serializer, new CreateHistoricalParent("parent"));
            try (var migration = PublishedEventModelMigration.builder().name("historical-cascade")
                    .client(new BorrowedClient(clients[0])).modelTypes(models).build()) {
                migration.replay();
                awaitBoundary(clients[0], migration.name(), recreated);
            }
            try (var reader = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                    .build(new BorrowedClient(clients[1]))) {
                reader.registerHandlers(models);
                assertHistoricalDeletion(reader.modelRepository(), deletion);
                assertEquals(new HistoricalParent("parent"), reader.modelRepository().loadGraph(
                        "parent", HistoricalParent.class, Graph.Options.DEFAULT).get());
            }
            assertEquals(5, clients[0].getTrackingClient(EVENT).readFromIndex(0L, 100).size());
        } finally {
            Arrays.stream(clients).distinct().forEach(Client::shutDown);
        }
    }

    private static void assertHistoricalDeletion(
            io.fluxzero.sdk.persisting.repository.ModelRepository repository, long deletion) {
        var after = ModelReadBoundary.event(deletion);
        var before = after.asBefore();
        assertEquals(new HistoricalParent("parent"), repository.loadGraph(
                "parent", HistoricalParent.class, before, Graph.Options.DEFAULT).get());
        assertEquals(new HistoricalChild("owned", "parent"), repository.loadGraph(
                "owned", HistoricalChild.class, before, Graph.Options.DEFAULT).get());
        assertEquals(new HistoricalRetained("retained", "parent"), repository.loadGraph(
                "retained", HistoricalRetained.class, before, Graph.Options.DEFAULT).get());
        assertEquals(List.of(new HistoricalChild("owned", "parent")), repository.loadGraph(
                "parent", HistoricalParent.class, before, Graph.Options.DEFAULT).children(HistoricalChild.class)
                .stream().map(Graph::get).toList());
        assertEquals(List.of(new HistoricalRetained("retained", "parent")), repository.loadGraph(
                "parent", HistoricalParent.class, before, Graph.Options.DEFAULT).children(HistoricalRetained.class)
                .stream().map(Graph::get).toList());
        assertNull(repository.loadGraph("parent", HistoricalParent.class, after, Graph.Options.DEFAULT).get());
        assertTrue(repository.loadGraph("parent", HistoricalParent.class, after, Graph.Options.DEFAULT)
                .children(HistoricalChild.class).isEmpty());
        assertNull(repository.loadGraph("owned", HistoricalChild.class, after, Graph.Options.DEFAULT).get());
        assertEquals(new HistoricalRetained("retained", "parent"), repository.loadGraph(
                "retained", HistoricalRetained.class, after, Graph.Options.DEFAULT).get());
        assertNull(repository.loadGraph("owned", HistoricalChild.class, Graph.Options.DEFAULT).get());
        assertTrue(repository.loadGraph("retained", HistoricalRetained.class,
                after, Graph.Options.DEFAULT).parents().isEmpty());
    }

    @Model(name = "HistoricalMigrationParent", searchable = false)
    public record HistoricalParent(@EntityId String id) { }
    @Model(name = "HistoricalMigrationChild", searchable = false)
    public record HistoricalChild(@EntityId String id, @Parent(value = HistoricalParent.class) String parentId) { }
    @Model(name = "HistoricalMigrationRetained", searchable = false)
    public record HistoricalRetained(@EntityId String id,
                                     @Parent(value = HistoricalParent.class, deleteOnParentDeletion = false)
                                     String parentId) { }
    public record CreateHistoricalParent(String id) {
        @Apply HistoricalParent apply(@Nullable HistoricalParent previous) { return new HistoricalParent(id); }
    }
    public record CreateHistoricalChild(String id, String parentId) {
        @Apply HistoricalChild apply(@Nullable HistoricalChild previous) { return new HistoricalChild(id, parentId); }
    }
    public record CreateHistoricalRetained(String id, String parentId) {
        @Apply HistoricalRetained apply(@Nullable HistoricalRetained previous) { return new HistoricalRetained(id, parentId); }
    }
    public record DeleteHistoricalParent(String id) {
        @Apply HistoricalParent apply(HistoricalParent previous) { return null; }
    }

    private static void assertCascadeState() {
        assertNull(Fluxzero.loadModel("root", Root.class).get());
        assertNull(Fluxzero.loadModel("child", Child.class).get());
        assertNull(Fluxzero.loadModel("grandchild", Grandchild.class).get());
        assertEquals(new RetainedChild("retained", "root"),
                Fluxzero.loadModel("retained", RetainedChild.class).get());
        assertTrue(Fluxzero.search(Root.class).fetchAll().isEmpty());
        assertTrue(Fluxzero.search(Child.class).fetchAll().isEmpty());
        assertTrue(Fluxzero.searchGraph(Root.class).fetchAll().isEmpty());
        assertEquals(DELETION_TIME, Fluxzero.loadCurrentGraph("grandchild", Grandchild.class).timestamp());
        assertEquals(1L, Fluxzero.loadModel("grandchild", Grandchild.class).sequenceNumber());
    }

    private static PublishedEventModelMigration migration(Client client) {
        return PublishedEventModelMigration.builder().name("live-model-migration")
                .client(new BorrowedClient(client)).modelTypes(Root.class, Child.class).build();
    }

    private static long append(Client client, JacksonSerializer serializer, Object event) {
        client.getGatewayClient(EVENT).append(STORED, new Message(event).serialize(serializer)).join();
        return client.getTrackingClient(EVENT).readFromIndex(0L, 100).getLast().getIndex();
    }

    private static void awaitBoundary(Client client, String name, long boundary) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (client.getTrackingClient(EVENT).getPosition(name)
                .lowestIndexForSegment(new int[]{0, MAX_SEGMENT}).orElse(-1L) < boundary) {
            assertTrue(System.nanoTime() < deadline, "Replay must durably reach its source boundary");
            Thread.sleep(10L);
        }
    }

    private static void assertCurrent(Fluxzero reader, String rootName, String childName) {
        reader.apply(f -> {
            assertEquals(List.of(new Root("root", rootName)), Fluxzero.search(Root.class).fetchAll());
            var children = Fluxzero.search(Child.class).fetchAll();
            assertEquals(childName == null ? List.of() : List.of(new Child("child", "root", childName)), children);
            var graphs = Fluxzero.searchGraph(Root.class).fetchAll();
            assertEquals(1, graphs.size());
            assertEquals(new Root("root", rootName), graphs.getFirst().get());
            assertEquals(childName == null ? List.of() : List.of(new Child("child", "root", childName)),
                    graphs.getFirst().children("children", Child.class).stream().map(Graph::get).toList());
            assertEquals(childName == null ? null : new Child("child", "root", childName),
                    Fluxzero.loadCurrentGraph("child", Child.class).get());
            return null;
        });
    }

    private record BorrowedClient(@lombok.experimental.Delegate Client delegate) implements Client {
        @Override public void shutDown() { }
    }

    @Model(name = "LiveMigrationRoot", persistence = ModelPersistence.DOCUMENT, searchable = true,
            searchSettings = @SearchSettings(collection = "migration-roots"),
            graphProjection = @GraphProjection(mode = GraphProjectionMode.AWAIT, collection = "migration-root-graphs"))
    public record Root(@EntityId String id, String name) { }

    @Model(name = "LiveMigrationChild", persistence = ModelPersistence.DOCUMENT, searchable = true,
            searchSettings = @SearchSettings(collection = "migration-children"))
    public record Child(@EntityId String id, @Parent(value = Root.class, pathInParent = "children") String rootId,
                        String name) { }

    @Model(name = "MigrationRetainedChild", searchable = false)
    public record RetainedChild(@EntityId String id,
                                @Parent(value = Root.class, deleteOnParentDeletion = false) String rootId) { }

    public record SetRetainedChild(String id, String rootId) {
        @Apply RetainedChild apply(@Nullable RetainedChild previous) { return new RetainedChild(id, rootId); }
    }

    @Model(name = "MigrationGrandchild", searchable = false)
    public record Grandchild(@EntityId String id, @Parent(value = Child.class) String childId) { }

    public record SetGrandchild(String id, String childId) {
        @Apply Grandchild apply(@Nullable Grandchild previous) { return new Grandchild(id, childId); }
    }

    public record DeleteRoot(String id) {
        @AssertLegal
        void assertCommand(DeserializingMessage message) {
            assertEquals(COMMAND, message.getMessageType(),
                    "Migration must not rerun command assertions");
        }
        @Apply Root apply(Root previous) { return null; }
    }

    public record AcceptDeleteRoot(String id) {
        @AssertLegal
        void assertCommand(DeserializingMessage message) {
            assertEquals(COMMAND, message.getMessageType(), "Migration must not rerun command assertions");
        }
        @Apply(conflictPolicy = ModelConflictPolicy.ACCEPT) Root apply(Root previous) { return null; }
    }

    public record SetRoot(String id, String name) {
        @Apply Root apply(@Nullable Root previous) { return new Root(id, name); }
    }
    public record SetChild(String id, String rootId, String name) {
        @Apply Child apply(@Nullable Child previous) { return name == null ? null : new Child(id, rootId, name); }
    }
}
