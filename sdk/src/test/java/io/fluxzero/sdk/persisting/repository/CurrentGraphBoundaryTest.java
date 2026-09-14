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
package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Graphs;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.Parent;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CurrentGraphBoundaryTest {
    @Test
    void currentGraphIncludesCommittedChildrenWhileRootCacheTrackingLags() throws Exception {
        var client = new DelayedTrackingClient();
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            try {
                var repository = (DefaultModelRepository) app.modelRepository();
                assertTrue(repository.cacheTrackingReadiness().get(5, TimeUnit.SECONDS));
                commit(app, new CreateRoot("root"));
                repository.load("root", FreshnessRoot.class);
                client.eventQueries.clear();
                assertTrue(repository.resolveGraphIdentity("root", FreshnessRoot.class,
                                                           ModelReadBoundary.current()).present());
                assertTrue(client.eventQueries.isEmpty(), "The root has a usable current cache entry");

                commit(app, new UpsertChild("child", "root"));
                app.apply(fc -> fc.eventStore().getEvents("root").findFirst().orElseThrow().apply(event -> {
                    assertTrue(Graphs.lazy("root", FreshnessRoot.class, repository)
                                       .namedChildren("freshness-child").isEmpty());
                    client.eventQueries.clear();
                    Graph<FreshnessRoot> current = Graphs.lazyCurrent("root", FreshnessRoot.class, repository);
                    assertEquals(List.of("child"), ids(current));
                    assertEquals(1, client.eventQueries.size(), "An explicit current read pins a fresh head");
                    assertTrue(client.eventQueries.stream().flatMap(query -> query.getRequests().stream())
                                       .allMatch(request -> request.getMaxSize() == 0),
                               "Identity and membership inspection must not replay model values");
                    return null;
                }));
            } finally {
                client.releaseUpdates.complete(null);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"add", "remove", "reparent"})
    void publicCurrentGraphPinsChangedMembershipAndDefersValues(String change) throws Exception {
        var client = new DelayedTrackingClient();
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            try {
                var repository = (DefaultModelRepository) app.modelRepository();
                assertTrue(repository.cacheTrackingReadiness().get(5, TimeUnit.SECONDS));
                commit(app, new CreateRoot("root"));
                commit(app, new CreateRoot("other"));
                if (!change.equals("add")) {
                    commit(app, new UpsertChild("child", "root"));
                }
                repository.load("root", FreshnessRoot.class);
                Graph<FreshnessRoot> before = app.apply(fc -> Fluxzero.loadCurrentGraph("root", FreshnessRoot.class));
                List<Object> initialChildren = change.equals("add") ? List.of() : List.of("child");

                commit(app, switch (change) {
                    case "add" -> new UpsertChild("child", "root");
                    case "remove" -> new DeleteChild("child");
                    default -> new UpsertChild("child", "other");
                });
                client.eventQueries.clear();
                Graph<FreshnessRoot> current = app.apply(fc -> Fluxzero.loadCurrentGraph("root", FreshnessRoot.class));
                assertEquals(1, client.eventQueries.size());
                assertTrue(client.eventQueries.getFirst().getRequests().stream()
                                   .allMatch(request -> request.getMaxSize() == 0));

                // Neither graph has inspected its relationships or its value yet. Later writes must not move it.
                commit(app, new UpdateRoot("root"));
                commit(app, change.equals("add") ? new DeleteChild("child") : new UpsertChild("child", "root"));
                assertEquals(initialChildren, ids(before));
                assertEquals(change.equals("add") ? List.of("child") : List.of(), ids(current));
                assertEquals(new FreshnessRoot("root", 1), before.get());
                assertEquals(new FreshnessRoot("root", 1), current.get());
                assertEquals(new FreshnessRoot("root", 2),
                             app.apply(fc -> Fluxzero.loadCurrentGraph("root", FreshnessRoot.class)).get());
            } finally {
                client.releaseUpdates.complete(null);
            }
        }
    }

    private static List<Object> ids(Graph<?> graph) {
        return graph.namedChildren("freshness-child").stream().map(Graph::id).toList();
    }

    private static void commit(Fluxzero app, Object command) {
        app.apply(fc -> fc.executeModelCommit(new Message(command)).join());
    }

    private static class DelayedTrackingClient extends LocalClient {
        final CompletableFuture<Void> releaseUpdates = new CompletableFuture<>();
        final List<GetModelEvents> eventQueries = new CopyOnWriteArrayList<>();

        DelayedTrackingClient() {
            super(null);
        }

        @Override
        protected EventStoreClient createEventStoreClient() {
            EventStoreClient delegate = super.createEventStoreClient();
            return (EventStoreClient) Proxy.newProxyInstance(EventStoreClient.class.getClassLoader(),
                    new Class<?>[]{EventStoreClient.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("trackModelUpdates")) {
                            TrackModelUpdates request = (TrackModelUpdates) arguments[0];
                            if (request.getMaxWaitMillis() > 0) {
                                return releaseUpdates.thenCompose(ignored -> delegate.trackModelUpdates(request));
                            }
                        }
                        if (method.getName().equals("getModelEvents")) {
                            eventQueries.add((GetModelEvents) arguments[0]);
                        }
                        try {
                            return method.invoke(delegate, arguments);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    @Model(name = "freshness-root")
    record FreshnessRoot(@EntityId String rootId, int version) {}

    @Model(name = "freshness-child")
    record FreshnessChild(@EntityId String childId,
                          @Parent(value = FreshnessRoot.class, pathInParent = "children") String rootId) {}

    record CreateRoot(String rootId) {
        @Apply FreshnessRoot apply() { return new FreshnessRoot(rootId, 1); }
    }

    record UpdateRoot(String rootId) {
        @Apply FreshnessRoot apply(FreshnessRoot root) { return new FreshnessRoot(rootId, root.version() + 1); }
    }

    record DeleteChild(String childId) {
        @Apply FreshnessChild apply(FreshnessChild child) { return null; }
    }

    record UpsertChild(String childId, String rootId) {
        @Apply FreshnessChild apply(@jakarta.annotation.Nullable FreshnessChild existing) { return new FreshnessChild(childId, rootId); }
    }
}
