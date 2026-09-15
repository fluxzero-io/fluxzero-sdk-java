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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.common.api.modeling.TrackModelUpdates;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.Graphs;
import io.fluxzero.sdk.modeling.Id;
import io.fluxzero.sdk.modeling.AssertLegal;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.Parent;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CurrentGraphBoundaryTest {
    @ParameterizedTest
    @EnumSource(value = ModelConflictPolicy.class, names = {"ACCEPT", "FAIL", "RETRY"})
    void nextCommandObservesAnotherApplicationsCommitDuringTheReceivedBatch(
            ModelConflictPolicy policy) throws Exception {
        var client = new DelayedTrackingClient();
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .configureModelConflictHandling(policy,
                        io.fluxzero.sdk.modeling.ModelConflictResolver.retryIfAllowed(), 3).build(client);
             Fluxzero writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            try {
                assertTrue(((DefaultModelRepository) app.modelRepository()).cacheTrackingReadiness()
                                   .get(5, TimeUnit.SECONDS));
                commit(app, new CreateRoot("root"));
                var serializer = new JacksonSerializer();
                List<DeserializingMessage> received = List.of(
                        new ObserveRoot("root", 1, List.of()),
                        new ObserveRoot("root", 1, List.of("child")))
                        .stream().map(payload -> new DeserializingMessage(
                                new Message(payload), MessageType.COMMAND, serializer)).toList();
                app.apply(fc -> {
                    DeserializingMessage.forEachInBatch(received, message -> {
                        if (DeserializingMessage.getMessageBatchIndex() == 1) {
                            // This writer belongs to another repository, not this command's pending overlay.
                            CompletableFuture.runAsync(() -> commit(writer, new UpsertChild("child", "root")),
                                    task -> Thread.ofVirtual().name("external-model-writer").start(task))
                                    .orTimeout(5, TimeUnit.SECONDS).join();
                        }
                        fc.executeModelCommit(new Message(message.getPayload())).join();
                    });
                    return null;
                });
            } finally {
                client.releaseUpdates.complete(null);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"version,ACCEPT", "version,RETRY", "version,FAIL",
                "add,ACCEPT", "add,RETRY", "add,FAIL",
                "remove,ACCEPT", "remove,RETRY", "remove,FAIL",
                "reparent,ACCEPT", "reparent,RETRY", "reparent,FAIL"})
    void receivedBatchDoesNotShareItsFirstCommandsGraphBoundary(
            String change, ModelConflictPolicy policy) throws Exception {
        var client = new DelayedTrackingClient();
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .configureModelConflictHandling(policy,
                        io.fluxzero.sdk.modeling.ModelConflictResolver.retryIfAllowed(), 3).build(client)) {
            try {
                assertTrue(((DefaultModelRepository) app.modelRepository()).cacheTrackingReadiness()
                                   .get(5, TimeUnit.SECONDS));
                commit(app, new CreateRoot("root"));
                commit(app, new CreateRoot("other"));
                List<Object> before = change.equals("remove") || change.equals("reparent")
                        ? List.of("child") : List.of();
                if (!before.isEmpty()) {
                    commit(app, new UpsertChild("child", "root"));
                }
                Object update = switch (change) {
                    case "version" -> new UpdateRoot("root");
                    case "add" -> new UpsertChild("child", "root");
                    case "remove" -> new DeleteChild("child");
                    default -> new UpsertChild("child", "other");
                };
                List<Object> after = change.equals("add") ? List.of("child") : List.of();
                var serializer = new JacksonSerializer();
                // All three commands are available before processing starts, but are not one transaction.
                List<DeserializingMessage> received = List.of(
                        new ObserveRoot("root", 1, before), update,
                        new ObserveRoot("root", change.equals("version") ? 2 : 1, after))
                        .stream().map(payload -> new DeserializingMessage(
                                new Message(payload), MessageType.COMMAND, serializer)).toList();
                app.apply(fc -> {
                    DeserializingMessage.forEachInBatch(received, message -> {
                        // Completion before the next invocation is intentional: successful writes are
                        // no longer pending overlays. The later Graph must observe them in storage.
                        fc.executeModelCommit(new Message(message.getPayload())).join();
                    });
                    return null;
                });
            } finally {
                client.releaseUpdates.complete(null);
            }
        }
    }

    @Test
    void cachedMissingDocumentMustRecheckANewlyAssignedWritableAlias() throws Exception {
        var client = LocalClient.newInstance();
        try (Fluxzero warm = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client);
             Fluxzero cold = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            assertTrue(((DefaultModelRepository) warm.modelRepository()).cacheTrackingReadiness()
                               .get(5, TimeUnit.SECONDS));
            assertTrue(((DefaultModelRepository) cold.modelRepository()).cacheTrackingReadiness()
                               .get(5, TimeUnit.SECONDS));
            assertTrue(warm.modelRepository().load("alias", AliasedDocument.class).isEmpty());
            commit(cold, new CreateAliasedDocument("different", "alias"));
            for (Fluxzero application : List.of(cold, warm)) {
                Throwable failure = assertThrows(Exception.class,
                        () -> commit(application, new CreateAliasedDocument("alias", "newAlias")));
                while (failure.getCause() != null) {
                    failure = failure.getCause();
                }
                assertTrue(failure.getMessage().contains("resolved through alias"), failure.toString());
            }
            assertEquals(new AliasedDocument("different", "alias"),
                         warm.modelRepository().load("alias", AliasedDocument.class).get());
        }
    }


    @Model(persistence = io.fluxzero.sdk.modeling.ModelPersistence.DOCUMENT)
    record AliasedDocument(@EntityId String documentId, @io.fluxzero.sdk.modeling.Alias String alias) {}
    record CreateAliasedDocument(String documentId, String alias) {
        @Apply AliasedDocument apply(@jakarta.annotation.Nullable AliasedDocument existing) {
            return new AliasedDocument(documentId, alias);
        }
    }

    @Test
    void backgroundReplayUsesItsOwnerEvenWhenFirstLoadedByAnotherApplication() throws Exception {
        var client = LocalClient.newInstance();
        try (Fluxzero owner = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client);
             Fluxzero writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client);
             Fluxzero foreign = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(LocalClient.newInstance())) {
            commit(writer, new CreateRoot("root"));
            var repository = (DefaultModelRepository) owner.modelRepository();
            var replayApplication = new CompletableFuture<Fluxzero>();
            repository.configureReplayRestoration(message -> {
                if (Thread.currentThread().getName().startsWith("fluxzero-model-cache-refresh-")) {
                    replayApplication.complete(Fluxzero.get());
                }
                return message;
            });
            foreign.apply(fc -> repository.load("root", FreshnessRoot.class));
            assertTrue(repository.cacheTrackingReadiness().get(5, TimeUnit.SECONDS));
            foreign.apply(fc -> repository.load("root", FreshnessRoot.class));
            commit(writer, new UpdateRoot("root"));
            assertSame(owner, replayApplication.get(5, TimeUnit.SECONDS));
            assertEquals(new FreshnessRoot("root", 2), repository.load("root", FreshnessRoot.class).get());
        }
    }

    @Test
    void sharedPhysicalCacheNeverBorrowsAnotherRepositoriesValues() {
        var physical = new io.fluxzero.sdk.persisting.caching.DefaultCache();
        try (Fluxzero first = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                     .withModelCache(physical).build(LocalClient.newInstance());
             Fluxzero other = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                     .withModelCache(physical).build(LocalClient.newInstance())) {
            commit(first, new CreateRoot("same"));
            commit(other, new CreateRoot("same"));
            commit(other, new UpdateRoot("same"));
            for (int i = 0; i < 3; i++) {
                assertEquals(new FreshnessRoot("same", 1), first.modelRepository().load("same", FreshnessRoot.class).get());
                assertEquals(new FreshnessRoot("same", 2), other.modelRepository().load("same", FreshnessRoot.class).get());
            }
            ((DefaultModelRepository) first.modelRepository()).invalidateModels(List.of("same"));
            assertEquals(new FreshnessRoot("same", 2), other.modelRepository().load("same", FreshnessRoot.class).get());
        }
    }

    @ParameterizedTest
    @CsvSource({"add,direct,RETRY", "remove,direct,RETRY", "reparent,direct,FAIL",
                "add,nested,FAIL", "remove,nested,RETRY", "reparent,interceptor,RETRY",
                "add,apply,RETRY", "remove,apply,FAIL", "add,document,RETRY", "remove,document,FAIL"})
    void commandAssertionIncludesCommittedChildrenWhileRootCacheTrackingLags(
            String change, String route, io.fluxzero.common.api.modeling.ModelConflictPolicy policy) throws Exception {
        var client = new DelayedTrackingClient();
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                     .configureModelConflictHandling(policy, io.fluxzero.sdk.modeling.ModelConflictResolver.retryIfAllowed(), 3).build(client)) {
            try {
                var repository = (DefaultModelRepository) app.modelRepository();
                assertTrue(repository.cacheTrackingReadiness().get(5, TimeUnit.SECONDS));
                boolean document = route.equals("document");
                commit(app, document ? new CreateDocumentRoot("root") : new CreateRoot("root"));
                commit(app, document ? new CreateDocumentRoot("other") : new CreateRoot("other"));
                if (!change.equals("add")) {
                    commit(app, document ? new UpsertDocumentChild("child", "root")
                            : new UpsertChild("child", "root"));
                }
                if (document) {
                    repository.load("root", DocumentRoot.class);
                } else {
                    repository.load("root", FreshnessRoot.class);
                }
                commit(app, switch (change) {
                    case "add" -> document ? new UpsertDocumentChild("child", "root") : new UpsertChild("child", "root");
                    case "remove" -> document ? new DeleteDocumentChild("child") : new DeleteChild("child");
                    default -> new UpsertChild("child", "other");
                });
                List<Object> expected = change.equals("add") ? List.of("child") : List.of();
                client.eventQueries.clear();
                commit(app, switch (route) {
                    case "nested" -> new NestedMembership("root", expected);
                    case "interceptor" -> new InterceptedMembership("root", expected);
                    case "apply" -> new ApplyMembership("root", expected);
                    case "document" -> new DocumentMembership("root", expected);
                    default -> new RequireMembership("root", expected);
                });
                assertFalse(client.eventQueries.isEmpty(), "A new Graph-dependent evaluation must observe storage");
                if (route.equals("direct") || route.equals("apply")) {
                    assertEquals(1, client.eventQueries.size(),
                                 "Event-sourced targets combine namespace freshness and suffix validation");
                }
            } finally {
                client.releaseUpdates.complete(null);
            }
        }
    }

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
                assertTrue(repository.supplyCurrentModel("root", FreshnessRoot.class,
                        (entity, validThrough, modelStateIndex) -> assertTrue(entity.isPresent())));
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
    @CsvSource({"add,typed,false", "add,typed,true", "add,value,true", "add,id,true", "add,object,true", "add,untyped,true",
                "remove,typed,false", "remove,typed,true", "remove,value,true", "remove,id,true", "remove,object,true", "remove,untyped,true",
                "reparent,typed,false", "reparent,typed,true", "reparent,value,true", "reparent,id,true", "reparent,object,true", "reparent,untyped,true"})
    void ordinaryGraphObservesCompletedRelationshipChangesRegardlessOfReadOrder(
            String change, String route, boolean externalWriter) throws Exception {
        var client = new DelayedTrackingClient();
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client);
             Fluxzero writer = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            try {
                var repository = (DefaultModelRepository) app.modelRepository();
                assertTrue(repository.cacheTrackingReadiness().get(5, TimeUnit.SECONDS));
                commit(app, new CreateRoot("root"));
                commit(app, new CreateRoot("other"));
                if (!change.equals("add")) {
                    commit(app, new UpsertChild("child", "root"));
                }
                repository.load("root", FreshnessRoot.class);
                Fluxzero selectedWriter = externalWriter ? writer : app;
                commit(selectedWriter, switch (change) {
                    case "add" -> new UpsertChild("child", "root");
                    case "remove" -> new DeleteChild("child");
                    default -> new UpsertChild("child", "other");
                });
                client.eventQueries.clear();
                app.apply(fc -> {
                    Graph<?> graph = switch (route) {
                        case "id" -> Fluxzero.loadGraph(new FreshnessRootId("root"));
                        case "object" -> Fluxzero.loadGraph((Object) new FreshnessRootId("root"));
                        case "untyped" -> Fluxzero.loadGraph((Object) "root");
                        default -> Fluxzero.loadGraph("root", FreshnessRoot.class);
                    };
                    if (!route.equals("typed")) {
                        assertEquals(new FreshnessRoot("root", 1), graph.get());
                        if (route.equals("value") || route.equals("id")) {
                            assertEquals(1, client.eventQueries.size(),
                                         "Freshness and cached suffix validation share one storage request");
                        }
                    }
                    List<Object> expected = change.equals("add") ? List.of("child") : List.of();
                    assertEquals(expected, graph.children(FreshnessChild.class).stream().map(Graph::id).toList());
                    assertEquals(new FreshnessRoot("root", 1), graph.get());
                    return null;
                });
            } finally {
                client.releaseUpdates.complete(null);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"add,false", "remove,false", "reparent,false", "add,true", "remove,true", "reparent,true"})
    void publicCurrentGraphPinsChangedMembershipAndDefersValues(String change, boolean shortcut) throws Exception {
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
                Graph<FreshnessRoot> current = shortcut ? before.current()
                        : app.apply(fc -> Fluxzero.loadCurrentGraph("root", FreshnessRoot.class));
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

    static class FreshnessRootId extends Id<FreshnessRoot> {
        FreshnessRootId(String value) { super(value); }
    }

    @Model(name = "freshness-child")
    record FreshnessChild(@EntityId String childId,
                          @Parent(value = FreshnessRoot.class, pathInParent = "children") String rootId) {}

    record CreateRoot(String rootId) {
        @Apply FreshnessRoot apply() { return new FreshnessRoot(rootId, 1); }
    }

    record UpdateRoot(String rootId) {
        @Apply FreshnessRoot apply(FreshnessRoot root) { return new FreshnessRoot(rootId, root.version() + 1); }
    }

    record ObserveRoot(String rootId, int version, List<Object> expectedChildren) {
        @AssertLegal void check(Graph<FreshnessRoot> root) {
            assertEquals(version, root.get().version());
            assertEquals(expectedChildren, root.children(FreshnessChild.class).stream().map(Graph::id).toList());
        }
        @Apply FreshnessRoot apply(FreshnessRoot root) { return root; }
    }

    record RequireMembership(String rootId, List<Object> expected) {
        @AssertLegal void check(Graph<FreshnessRoot> root) { assertEquals(expected, ids(root)); }
        @Apply FreshnessRoot apply(FreshnessRoot root) { return new FreshnessRoot(rootId, root.version() + 1); }
    }

    record NestedMembership(String rootId, List<Object> expected) {
        @AssertLegal Object check() { return new MembershipGuard(expected); }
        @Apply FreshnessRoot apply(FreshnessRoot root) { return new FreshnessRoot(rootId, root.version() + 1); }
    }
    record MembershipGuard(List<Object> expected) {
        @AssertLegal void check(Graph<FreshnessRoot> root) { assertEquals(expected, ids(root)); }
    }
    record InterceptedMembership(String rootId, List<Object> expected) {
        @io.fluxzero.sdk.persisting.eventsourcing.InterceptApply
        Object intercept() { return new RequireMembership(rootId, expected); }
    }
    record ApplyMembership(String rootId, List<Object> expected) {
        @Apply FreshnessRoot apply(Graph<FreshnessRoot> root) {
            assertEquals(expected, ids(root));
            return new FreshnessRoot(rootId, root.get().version() + 1);
        }
    }

    @Model(persistence = io.fluxzero.sdk.modeling.ModelPersistence.DOCUMENT)
    record DocumentRoot(@EntityId String rootId) {}
    @Model(persistence = io.fluxzero.sdk.modeling.ModelPersistence.DOCUMENT)
    record DocumentChild(@EntityId String childId,
                         @Parent(value = DocumentRoot.class, pathInParent = "children") String rootId) {}
    record CreateDocumentRoot(String rootId) {
        @Apply DocumentRoot apply() { return new DocumentRoot(rootId); }
    }
    record UpsertDocumentChild(String childId, String rootId) {
        @Apply DocumentChild apply(@jakarta.annotation.Nullable DocumentChild existing) {
            return new DocumentChild(childId, rootId);
        }
    }
    record DeleteDocumentChild(String childId) {
        @Apply DocumentChild apply(DocumentChild existing) { return null; }
    }
    record DocumentMembership(String rootId, List<Object> expected) {
        @AssertLegal void check(Graph<DocumentRoot> root) {
            assertEquals(expected, root.children(DocumentChild.class).stream().map(Graph::id).toList());
        }
        @Apply DocumentRoot apply(DocumentRoot root) { return root; }
    }

    record DeleteChild(String childId) {
        @Apply FreshnessChild apply(FreshnessChild child) { return null; }
    }

    record UpsertChild(String childId, String rootId) {
        @Apply FreshnessChild apply(@jakarta.annotation.Nullable FreshnessChild existing) { return new FreshnessChild(childId, rootId); }
    }
}
