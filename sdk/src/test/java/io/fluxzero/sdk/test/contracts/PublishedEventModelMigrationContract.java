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
import io.fluxzero.common.api.search.GetDocument;
import io.fluxzero.common.api.search.GetModelMigrations;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.PublishedEventModelMigration;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.*;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.fluxzero.common.Guarantee.STORED;
import static io.fluxzero.common.MessageType.EVENT;
import static io.fluxzero.common.api.tracking.SegmentRange.MAX_SEGMENT;
import static org.junit.jupiter.api.Assertions.*;

/** The same live replay/search/cutover contract runs against LocalClient and PostgreSQL/WebSocket. */
@Timeout(30)
public abstract class PublishedEventModelMigrationContract {
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

    public record SetRoot(String id, String name) {
        @Apply Root apply(@Nullable Root previous) { return new Root(id, name); }
    }
    public record SetChild(String id, String rootId, String name) {
        @Apply Child apply(@Nullable Child previous) { return name == null ? null : new Child(id, rootId, name); }
    }
}
