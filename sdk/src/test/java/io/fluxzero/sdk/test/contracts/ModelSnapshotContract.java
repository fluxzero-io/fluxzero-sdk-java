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

import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.ModelDeletionCascade;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.common.api.modeling.ModelSnapshotMutation;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.Client;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.Parent;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Snapshot history and erasure contract shared by local, TestServer and JDBC clients. */
@Timeout(30)
public abstract class ModelSnapshotContract {
    /** Returns two clients sharing isolated storage, for a writer followed by a fresh reader application. */
    protected abstract Client[] clients(String namespace);

    @Test
    void unlimitedSnapshotsSurviveClientRestartAndRemainErasable() {
        Client[] clients = clients("snapshot-contract-" + UUID.randomUUID());
        long boundary;
        try (Fluxzero writer = app(clients[0])) {
            writer.apply(fc -> fc.executeModelCommit(new Message(new SetCounter("counter", 1))).join());
            boundary = writer.apply(fc -> Fluxzero.loadGraph("counter", Counter.class).revisionStateIndex());
            write(writer, new SetCounter("counter", 2));
            write(writer, new SetCounterTwice("counter", 3));
            assertEquals(4, writer.documentStore().search(ModelSnapshotMutation.COLLECTION).count());
        }
        try (Fluxzero reader = app(clients[1])) {
            reader.apply(fc -> {
                Graph<Counter> graph = Fluxzero.loadGraph("counter", Counter.class);
                for (int value = 4; value >= 1; value--) {
                    assertNotNull(graph, "Missing Graph for value " + value);
                    assertNotNull(graph.get(), "Missing state for value " + value + " at " + graph.stateIndex()
                            + ", revision " + graph.revisionStateIndex() + ", sequence " + graph.sequenceNumber());
                    assertEquals(value, graph.get().value());
                    graph = graph.previous();
                }
                assertNull(graph, "Creation must terminate the revision chain");
                assertEquals(1, fc.modelRepository().loadGraphAt("counter", Counter.class,
                        boundary, Graph.Options.DEFAULT).get().value());
                assertEquals(4, fc.client().getEventStoreClient().getModelEvents(new GetModelEvents(
                        List.of(new ModelEventStreamRequest("counter", -1, 10)), ModelReadBoundary.current(), 0))
                        .getStreams().getFirst().getMemberships().size());
                fc.modelRepository().deleteModel("counter", ModelDeletionCascade.NONE).join();
                assertEquals(0, fc.documentStore().search(ModelSnapshotMutation.COLLECTION).count());
                assertNull(Fluxzero.loadCurrentGraph("counter", Counter.class).get());
                return null;
            });
        }
    }

    @Test
    void boundedSnapshotPredecessorsRetainTheSurroundingGraphBoundary() {
        Client[] clients = clients("snapshot-boundary-" + UUID.randomUUID());
        try (Fluxzero writer = app(clients[0])) {
            write(writer, new SetParent("parent", 1));
            write(writer, new AddChild("first", "parent"));
            write(writer, new SetParent("parent", 2));
            write(writer, new AddChild("second", "parent"));
            write(writer, new SetParent("parent", 3));
        }
        try (Fluxzero reader = app(clients[1])) {
            reader.apply(fc -> {
                Graph<SnapshotParent> graph = Fluxzero.loadGraph("parent", SnapshotParent.class).previous();
                assertNotNull(graph);
                assertEquals(2, graph.get().value());
                assertEquals(2, graph.children("children", SnapshotChild.class).size());
                graph = graph.previous();
                assertNotNull(graph);
                assertEquals(1, graph.get().value());
                assertEquals(List.of("first"), graph.children("children", SnapshotChild.class)
                        .stream().map(child -> child.get().id()).toList());
                assertNull(graph.previous());
                assertEquals(2, fc.documentStore().search(ModelSnapshotMutation.COLLECTION).count());
                return null;
            });
        }
    }

    private static void write(Fluxzero app, Object command) {
        app.apply(fc -> fc.executeModelCommit(new Message(command)).join());
    }

    @Model(snapshotPeriod = 1, maxSnapshotCount = 2, cached = false)
    public record SnapshotParent(@EntityId String id, int value) {}

    @Model(cached = false)
    public record SnapshotChild(@EntityId String id,
                                @Parent(value = SnapshotParent.class, pathInParent = "children") String parentId) {}

    public record SetParent(String id, int value) {
        @Apply SnapshotParent apply(@Nullable SnapshotParent previous) { return new SnapshotParent(id, value); }
    }

    public record AddChild(String id, String parentId) {
        @Apply SnapshotChild apply() { return new SnapshotChild(id, parentId); }
    }

    public record SetCounterTwice(String id, int value) {
        @InterceptApply List<SetCounter> split() {
            return List.of(new SetCounter(id, value), new SetCounter(id, value + 1));
        }
    }

    private static Fluxzero app(Client client) {
        Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client);
        app.registerHandlers(Counter.class, SnapshotParent.class, SnapshotChild.class);
        return app;
    }

    @Model(snapshotPeriod = 1, maxSnapshotCount = -7, cached = false)
    public record Counter(@EntityId String id, int value) {}

    public record SetCounter(String id, int value) {
        @Apply Counter apply(@Nullable Counter previous) { return new Counter(id, value); }
    }
}
