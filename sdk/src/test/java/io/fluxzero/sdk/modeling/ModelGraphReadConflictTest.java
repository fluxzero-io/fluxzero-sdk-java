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
package io.fluxzero.sdk.modeling;

import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import io.fluxzero.sdk.tracking.handling.Association;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ModelGraphReadConflictTest {
    private static final AtomicInteger attempts = new AtomicInteger();
    private static final AtomicReference<Runnable> manualReadRace = new AtomicReference<>();
    private static final AtomicReference<Fluxzero> foreignApplication = new AtomicReference<>();
    private static final AtomicReference<Graph<Aliased>> absentAliasGraph = new AtomicReference<>();

    @ParameterizedTest
    @CsvSource({"0,false", "0,true", "1,false", "1,true", "2,false", "2,true"})
    void currentKeepsExactMissingIdentityWhenAnAliasIsAssigned(int readOrder, boolean committed) {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(new GateClient())) {
            commit(app, new ChangeAlias("owner", "old", null, "typed"));
            absentAliasGraph.set(app.apply(fc -> Fluxzero.loadCurrentGraph("free", Aliased.class)));
            if (committed) { commit(app, new ChangeAlias("owner", "free", "old", "typed")); }
            commit(app, new AssignMissingAlias("owner", "free", readOrder));
        } finally { absentAliasGraph.set(null); }
    }
    record AssignMissingAlias(String aliasId, String alias, int readOrder) {
        @Apply Aliased apply(Aliased before) { return new Aliased(aliasId, alias); }
        @AssertLegal(afterHandler = true) void check() {
            Graph<Aliased> current = absentAliasGraph.get().current();
            if (readOrder == 1) { assertEquals("free", current.id()); }
            if (readOrder == 2) { assertTrue(current.namedChildren("unrelated", false).isEmpty()); }
            assertNull(current.get(), "current() must not redirect an exact missing Model to a newly staged alias");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nestedOtherApplicationsAndNamespacesDoNotJoinTheMutation(boolean differentNamespace) {
        LocalClient shared = LocalClient.newInstance();
        GateClient gated = new GateClient();
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(gated);
             Fluxzero other = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                     .build(differentNamespace ? gated.forNamespace("other") : shared)) {
            commit(app, new CreateParent("parent", 10));
            commit(other, new CreateParent("parent", 99));
            commit(other, new CreateParent("foreign-only", 5));
            foreignApplication.set(other);
            gated.beforeCommit = request -> {
                assertFalse(request.getReadModelIds().contains("foreign-only"));
                assertFalse(request.getReadRelationships().stream().anyMatch(read -> read.modelId().equals("foreign-only")));
            };
            commit(app, new InspectForeign("receipt", "parent", differentNamespace));
            assertNull(app.apply(fc -> CommitAttempt.currentReadContext(fc.modelRepository())));
        } finally { foreignApplication.set(null); }
    }
    record InspectForeign(String receiptId, String parentId, boolean differentNamespace) {
        @AssertLegal void check() {
            Fluxzero other = foreignApplication.get();
            // Static Fluxzero loads deliberately inherit the current message's consumer namespace.
            // Select the foreign namespace explicitly to test actual cross-namespace isolation.
            var repository = differentNamespace ? Fluxzero.get().modelRepository().forNamespace("other")
                    : other.modelRepository();
            other.apply(fc -> {
                assertEquals(99, Graphs.lazy(parentId, ParentModel.class, repository).get().capacity());
                assertEquals(5, Graphs.lazy("foreign-only", ParentModel.class, repository).get().capacity());
                assertEquals(0, Graphs.lazy("foreign-only", ParentModel.class, repository).children().size());
                return null;
            });
            assertEquals(10, Fluxzero.loadGraph(parentId, ParentModel.class).get().capacity());
        }
        @Apply Receipt apply() { return new Receipt(receiptId, 0); }
    }

    @Test
    void replayedManualReadsKeepTheEventsBoundaryAndRestoreAmbientContext() {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(new GateClient())) {
            commit(app, new CreateParent("parent", 10));
            commit(app, new HistoricalManualRead("receipt", "parent"));
            commit(app, new CreateChild("child", "parent"));
            app.cache().clear();
            assertEquals(0, app.apply(fc -> Fluxzero.loadModel("receipt", Receipt.class).get().observed()).intValue());
            assertNull(app.apply(fc -> CommitAttempt.currentReadContext(fc.modelRepository())));
            assertThrows(CompletionException.class, () -> commit(app, new ManualChild("rejected", "parent", "typed")));
            assertNull(app.apply(fc -> CommitAttempt.currentReadContext(fc.modelRepository())));
            assertEquals(1, app.apply(fc -> Fluxzero.loadCurrentGraph("parent", ParentModel.class).children().size()).intValue());
        }
    }
    record HistoricalManualRead(String receiptId, String parentId) {
        @Apply Receipt apply() {
            return new Receipt(receiptId, Fluxzero.loadGraph(parentId, ParentModel.class).children().size());
        }
    }

    @Test
    void currentShortcutsKeepTheAttemptBoundaryUntilRetry() {
        GateClient client = new GateClient();
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            commit(app, new CreateParent("parent", 10));
            manualReadRace.set(() -> CompletableFuture.runAsync(() -> commit(app, new CreateChild("child", "parent")),
                    task -> Thread.ofVirtual().name("manual-graph-writer").start(task)).join());
            AtomicInteger submissions = new AtomicInteger();
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("receipt")) { submissions.incrementAndGet(); }
            };
            commit(app, new PinManualReads("receipt", "parent"));
            assertEquals(2, submissions.get());
            assertEquals(1, app.apply(fc -> Fluxzero.loadModel("receipt", Receipt.class).get().observed()).intValue());
        } finally { manualReadRace.set(null); }
    }
    record PinManualReads(String receiptId, String parentId) {
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY) Receipt apply() {
            Graph<ParentModel> first = Fluxzero.loadGraph(parentId, ParentModel.class);
            int size = first.children().size();
            Runnable race = manualReadRace.getAndSet(null);
            if (race != null) { race.run(); }
            Graph<ParentModel> current = Fluxzero.loadCurrentGraph(parentId, ParentModel.class);
            assertEquals(size, current.children().size());
            assertEquals(first.stateIndex(), current.stateIndex());
            assertEquals(size, first.current().children().size());
            return new Receipt(receiptId, size);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"typed", "untyped", "current"})
    void manualReadsSeeOwnStagedAliasChangesAndDeletion(String route) {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(new GateClient())) {
            commit(app, new ChangeAlias("aliased", "old", null, route));
            commit(app, new ChangeAlias("aliased", "new", "old", route));
            commit(app, new ChangeAlias("aliased", null, "new", route));
        }
    }

    @Model record Aliased(@EntityId String aliasId, @Alias String alias) {}

    record ChangeAlias(String aliasId, String alias, String previousAlias, String route) {
        @Apply Aliased apply(@jakarta.annotation.Nullable Aliased before) {
            return alias == null ? null : new Aliased(aliasId, alias);
        }
        @AssertLegal(afterHandler = true)
        void check() {
            if (alias != null) {
                assertEquals(new Aliased(aliasId, alias), read(alias).get());
                assertEquals(aliasId, read(alias).id());
            }
            if (previousAlias != null) { assertEquals(null, read(previousAlias).get()); }
            assertEquals(alias == null ? null : new Aliased(aliasId, alias), read(aliasId).get());
        }
        private Graph<?> read(String id) {
            return switch (route) {
                case "untyped" -> Fluxzero.loadGraph((Object) id);
                case "current" -> Fluxzero.loadCurrentGraph(id, Aliased.class);
                default -> Fluxzero.loadGraph(id, Aliased.class);
            };
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"typed", "untyped", "current", "repository"})
    void mutationDocumentReadsUseCurrentStateWithoutHistoricalReplay(String route) {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(new GateClient())) {
            commit(app, new PutDocument("document", 7));
            commit(app, new ReadDocument("receipt", "document", route));
            assertEquals(7, app.apply(fc -> Fluxzero.loadModel("receipt", Receipt.class).get().observed()).intValue());
        }
    }

    @Model(persistence = ModelPersistence.DOCUMENT)
    record CurrentDocument(@EntityId String documentId, int number) {}
    record PutDocument(String documentId, int number) {
        @Apply CurrentDocument apply(@jakarta.annotation.Nullable CurrentDocument before) {
            return new CurrentDocument(documentId, number);
        }
    }
    record ReadDocument(String receiptId, String documentId, String route) {
        @Apply Receipt apply() {
            Graph<?> graph = switch (route) {
                case "untyped" -> Fluxzero.loadGraph((Object) documentId);
                case "current" -> Fluxzero.loadCurrentGraph(documentId, CurrentDocument.class);
                case "repository" -> Fluxzero.get().modelRepository()
                        .loadGraph(documentId, CurrentDocument.class, Graph.Options.DEFAULT);
                default -> Fluxzero.loadGraph(documentId, CurrentDocument.class);
            };
            return new Receipt(receiptId, ((CurrentDocument) graph.get()).number());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"remove", "reparent"})
    void manualMembershipRetainsAtLeastOneChildAfterConcurrentChange(String change) {
        GateClient client = new GateClient();
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            commit(app, new CreateParent("parent", 10));
            commit(app, new CreateParent("other", 10));
            commit(app, new CreateChild("first", "parent"));
            commit(app, new CreateChild("second", "parent"));
            AtomicBoolean once = new AtomicBoolean();
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("first") && once.compareAndSet(false, true)) {
                    commit(app, change.equals("remove") ? new DeleteChild("second") : new MoveChild("second", "other"));
                }
            };
            var failure = assertThrows(CompletionException.class, () -> commit(app, new RetainChild("first", "parent")));
            assertInstanceOf(IllegalCommandException.class, rootCause(failure));
            assertTrue(once.get());
            assertEquals(List.of("first"), app.apply(fc -> Fluxzero.loadCurrentGraph("parent", ParentModel.class)
                    .children().stream().map(Graph::id).toList()));
        }
    }

    record DeleteChild(String childId) { @Apply Child apply(Child child) { return null; } }
    record RetainChild(String childId, String parentId) {
        @AssertLegal void check() {
            if (Fluxzero.loadGraph(parentId, ParentModel.class).children("children", Child.class).size() < 2) {
                throw new IllegalCommandException("At least one child must remain");
            }
        }
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY) Child apply(Child child) { return null; }
    }

    @ParameterizedTest
    @ValueSource(strings = {"manual", "helper", "caught-helper"})
    void acceptReappliesManualAndNestedApplyDependencies(String route) {
        GateClient client = new GateClient();
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            commit(app, new CreateParent("parent", 10));
            commit(app, new SeedReceipt("receipt"));
            AtomicInteger submissions = new AtomicInteger();
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("receipt") && submissions.incrementAndGet() == 1) {
                    assertFalse(request.getReadRelationships().isEmpty());
                    commit(app, new CreateChild("child", "parent"));
                }
            };
            commit(app, new CountChildren("receipt", "parent", route));
            assertEquals(2, submissions.get());
            assertEquals(1, app.apply(fc -> Fluxzero.loadModel("receipt", Receipt.class).get().observed()).intValue());
        }
    }
    record CountChildren(String receiptId, String parentId, String route) {
        @Apply(conflictPolicy = ModelConflictPolicy.ACCEPT) Receipt apply(Receipt previous) {
            if (!route.equals("manual")) {
                AtomicInteger count = new AtomicInteger();
                try { Fluxzero.assertLegal(new MembershipGuard(parentId, route.equals("caught-helper"), count)); }
                catch (IllegalCommandException ignored) { /* The read still contributed to this decision. */ }
                return new Receipt(receiptId, count.get());
            }
            return new Receipt(receiptId, Fluxzero.loadGraph(parentId, ParentModel.class).children().size());
        }
    }
    record MembershipGuard(String parentId, boolean reject, AtomicInteger count) {
        @AssertLegal void check(Graph<ParentModel> parent) {
            count.set(parent.children().size());
            if (reject) { throw new IllegalCommandException("Deliberate rejection"); }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"replace", "split", "drop"})
    void nestedExplicitAssertionsPreserveStandaloneInterception(String action) {
        try (Fluxzero app = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(new GateClient())) {
            commit(app, new SetProduct("product", true));
            app.apply(fc -> { Fluxzero.assertLegal(new InterceptGuard("product", action)); return null; });
            commit(app, new ReserveIntercepted("reservation", "product", action));
            commit(app, new SetProduct("product", false));
            if (action.equals("drop")) { commit(app, new ReserveIntercepted("second", "product", action)); }
            else {
                var failure = assertThrows(CompletionException.class,
                        () -> commit(app, new ReserveIntercepted("second", "product", action)));
                assertInstanceOf(IllegalCommandException.class, rootCause(failure));
            }
        }
    }
    record InterceptGuard(String productId, String action) {
        @InterceptApply Object intercept() {
            return switch (action) {
                case "drop" -> null;
                case "split" -> List.of(new ProductGuard(productId), new ProductGuard(productId));
                default -> new ProductGuard(productId);
            };
        }
        @AssertLegal void notReached(@jakarta.annotation.Nullable Product product) {
            throw new AssertionError("The interceptor should replace this check");
        }
    }
    record ReserveIntercepted(String reservationId, String productId, String action) {
        @AssertLegal void check() { Fluxzero.assertLegal(new InterceptGuard(productId, action)); }
        @Apply Reservation apply() { return new Reservation(reservationId); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"typed", "untyped", "current", "shortcut", "metadata", "value-first", "repository"})
    void manualGraphAssertionsProtectInitiallyEmptyMembership(String route) {
        GateClient client = new GateClient();
        try (Fluxzero fluxzero = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            commit(fluxzero, new CreateParent("parent", 1));
            AtomicBoolean once = new AtomicBoolean();
            AtomicInteger relationshipReads = new AtomicInteger();
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("contender") && once.compareAndSet(false, true)) {
                    relationshipReads.set(request.getReadRelationships().size());
                    commit(fluxzero, new CreateChild("winner", "parent"));
                }
            };
            CompletionException rejected = assertThrows(CompletionException.class,
                    () -> commit(fluxzero, new ManualChild("contender", "parent", route)));
            Throwable cause = rejected;
            while (cause.getCause() != null) { cause = cause.getCause(); }
            assertInstanceOf(IllegalCommandException.class, cause);
            assertTrue(relationshipReads.get() > 0, "Manual reads must register membership");
            assertEquals(1, fluxzero.apply(fc -> Fluxzero.loadCurrentGraph("parent", ParentModel.class)
                    .children().size()).intValue());
        }
    }

    record ManualChild(String childId, String parentId, String route) {
        @AssertLegal
        void check() {
            Graph<?> parent = switch (route) {
                case "untyped", "metadata" -> Fluxzero.loadGraph((Object) parentId);
                case "current" -> Fluxzero.loadCurrentGraph(parentId, ParentModel.class);
                case "shortcut" -> Fluxzero.loadGraph(parentId, ParentModel.class).current();
                case "repository" -> Fluxzero.get().modelRepository()
                        .loadGraph(parentId, ParentModel.class, Graph.Options.DEFAULT);
                default -> Fluxzero.loadGraph(parentId, ParentModel.class);
            };
            if (route.equals("value-first")) { parent.get(); }
            int size = route.equals("metadata") ? parent.namedChildren("Child", false).size()
                    : parent.children("children", Child.class).size();
            if (size >= 1) { throw new IllegalCommandException("Parent is full"); }
        }

        @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
        Child apply() { return new Child(childId, parentId); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "graph", "returned", "explicit", "metadata"})
    void independentProductValidationUsesTheSelectedIdentity(String route) {
        try (Fluxzero fluxzero = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook()
                .build(new GateClient())) {
            commit(fluxzero, new SetProduct("active", true));
            commit(fluxzero, new SetProduct("inactive", false));
            commit(fluxzero, reservation("allowed", "active", route));
            for (String productId : List.of("inactive", "missing")) {
                String reservationId = "reservation-" + productId;
                CompletionException failure = assertThrows(CompletionException.class,
                        () -> commit(fluxzero, reservation(reservationId, productId, route)));
                assertInstanceOf(IllegalCommandException.class, rootCause(failure));
                assertFalse(fluxzero.apply(fc -> fc.modelRepository().load(reservationId, Reservation.class).isPresent())
                                    .booleanValue());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "graph", "returned", "explicit", "metadata"})
    void independentProductUpdateInvalidatesReservation(String route) {
        GateClient client = new GateClient();
        try (Fluxzero fluxzero = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            commit(fluxzero, new SetProduct("product", true));
            // Warm the ordinary Model cache before evaluating the invariant.
            fluxzero.apply(fc -> fc.modelRepository().load("product", Product.class));
            AtomicBoolean once = new AtomicBoolean();
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("reservation") && once.compareAndSet(false, true)) {
                    commit(fluxzero, new SetProduct("product", false));
                }
            };
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> commit(fluxzero, reservation("reservation", "product", route)));
            assertInstanceOf(IllegalCommandException.class, rootCause(failure));
            assertTrue(once.get(), "The original active read must reach commit before the concurrent update");
            assertFalse(fluxzero.apply(fc -> fc.modelRepository().load("reservation", Reservation.class).isPresent())
                                .booleanValue());
        }
    }

    private static Throwable rootCause(Throwable failure) {
        while (failure.getCause() != null) { failure = failure.getCause(); }
        return failure;
    }

    private static Object reservation(String reservationId, String productId, String route) {
        return switch (route) {
            case "model" -> new ReserveWithModel(reservationId, productId);
            case "graph" -> new ReserveWithGraph(reservationId, productId);
            case "returned" -> new ReserveWithGuard(reservationId, productId);
            case "explicit" -> new ReserveWithHelper(reservationId, productId);
            default -> new Message(new ReserveFromContext(reservationId), Metadata.of("product", productId));
        };
    }

    @Model
    record Product(@EntityId String productId, boolean active) {}

    @Model
    record Reservation(@EntityId String reservationId) {}

    record SetProduct(String productId, boolean active) {
        @Apply Product apply(@jakarta.annotation.Nullable Product current) { return new Product(productId, active); }
    }

    private static void requireActive(Product product) {
        if (product == null || !product.active()) { throw new IllegalCommandException("Product is not active"); }
    }

    record ReserveWithModel(String reservationId, String productId) {
        @AssertLegal void check(@jakarta.annotation.Nullable Product product) { requireActive(product); }
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY) Reservation apply() { return new Reservation(reservationId); }
    }

    record ReserveWithGraph(String reservationId, String productId) {
        @AssertLegal void check(Graph<Product> product) { requireActive(product.get()); }
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY) Reservation apply() { return new Reservation(reservationId); }
    }

    record ReserveWithGuard(String reservationId, String productId) {
        @AssertLegal ProductGuard check() { return new ProductGuard(productId); }
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY) Reservation apply() { return new Reservation(reservationId); }
    }

    record ProductGuard(String productId) {
        @AssertLegal void check(@jakarta.annotation.Nullable Product product) { requireActive(product); }
    }

    record ReserveWithHelper(String reservationId, String productId) {
        @AssertLegal void check() { Fluxzero.assertLegal(new ProductGuard(productId)); }
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY) Reservation apply() { return new Reservation(reservationId); }
    }

    record ReserveFromContext(String reservationId) {
        @AssertLegal void check(@Association("product") @jakarta.annotation.Nullable Product product) {
            requireActive(product);
        }
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY) Reservation apply() { return new Reservation(reservationId); }
    }

    @Test
    void unusedAndValueOnlyDirectGraphInjectionKeepTheOrdinaryCommitProtocol() {
        GateClient client = new GateClient();
        try (Fluxzero fluxzero = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            commit(fluxzero, new CreateParent("parent", 10));
            AtomicInteger submissions = new AtomicInteger();
            client.beforeCommit = request -> {
                submissions.incrementAndGet();
                assertEquals(CommitModels.class, request.getClass());
                assertEquals(List.of(), request.getReadRelationships());
            };
            commit(fluxzero, new SeedReceipt("receipt"));
            commit(fluxzero, new IgnoreParentGraph("receipt", "parent"));
            commit(fluxzero, new ObserveParentValue("receipt", "parent"));
            assertEquals(3, submissions.get());
        }
    }

    @Test
    void dynamicWritesRetryUsingFreshChildRevisions() throws Exception {
        GateClient client = new GateClient();
        try (Fluxzero fluxzero = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            commit(fluxzero, new CreateParent("parent", 10));
            commit(fluxzero, new CreateChild("child", "parent"));
            commit(fluxzero, new SeedLeaf("first-leaf", "child"));
            commit(fluxzero, new SeedLeaf("second-leaf", "child"));
            client.armed.set(true);
            CompletableFuture<?> first = CompletableFuture.supplyAsync(() -> commit(fluxzero, new IncrementLeaves("child")),
                    task -> Thread.ofVirtual().name("dynamic-write-contender").start(task));
            first.whenComplete((result, failure) -> {
                if (failure != null) { client.entered.completeExceptionally(failure); }
            });
            try {
                client.entered.get(10, TimeUnit.SECONDS);
                commit(fluxzero, new SetLeaf("first-leaf", 10));
            } finally {
                client.release.complete(null);
            }
            first.get(10, TimeUnit.SECONDS);
            fluxzero.cache().clear();
            assertEquals(11, fluxzero.apply(fc -> fc.modelRepository().load("first-leaf", Leaf.class).get().version()).intValue());
            assertEquals(1, fluxzero.apply(fc -> fc.modelRepository().load("second-leaf", Leaf.class).get().version()).intValue());
        } finally {
            client.release.complete(null);
        }
    }

    @Test
    void valueOnlyGraphReadsRetryErasureAndAllowFreshAbsence() {
        GateClient client = new GateClient();
        try (Fluxzero fluxzero = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            commit(fluxzero, new CreateParent("parent", 10));
            commit(fluxzero, new SeedReceipt("receipt"));
            AtomicInteger submissions = new AtomicInteger();
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("receipt") && submissions.incrementAndGet() == 1) {
                    assertEquals(List.of(), request.getReadRelationships(), "Value-only reads need no membership proof");
                    fluxzero.apply(fc -> fc.modelRepository().deleteModel("parent",
                            io.fluxzero.common.api.modeling.ModelDeletionCascade.NONE).join());
                }
            };
            commit(fluxzero, new ObserveParentValue("receipt", "parent"));
            assertEquals(2, submissions.get());
            assertEquals(0, fluxzero.apply(fc -> fc.modelRepository().load("receipt", Receipt.class).get().observed())
                    .intValue());
        }
    }

    @Test
    void erasureCannotEraseTheProofOfAChangedChildCollection() {
        GateClient client = new GateClient();
        try (Fluxzero fluxzero = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            commit(fluxzero, new CreateParent("parent", 10));
            commit(fluxzero, new CreateChild("erased", "parent"));
            commit(fluxzero, new SeedReceipt("receipt"));
            AtomicBoolean once = new AtomicBoolean();
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("receipt") && once.compareAndSet(false, true)) {
                    fluxzero.apply(fc -> fc.modelRepository().deleteModel("erased",
                            io.fluxzero.common.api.modeling.ModelDeletionCascade.NONE).join());
                }
            };
            commit(fluxzero, new LazySelection("receipt", "parent"));
            assertEquals(0, fluxzero.apply(fc -> fc.modelRepository().load("receipt", Receipt.class).get().observed())
                    .intValue());
        }
    }

    @Test
    void intermediateReparentingInvalidatesIndirectInjectionAndLazyAncestorNavigation() {
        for (boolean indirect : new boolean[]{true, false}) {
            GateClient client = new GateClient();
            try (Fluxzero fluxzero = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
                commit(fluxzero, new CreateParent("allowed", 10));
                commit(fluxzero, new CreateParent("forbidden", 0));
                commit(fluxzero, new MoveChild("middle", "allowed"));
                commit(fluxzero, new SeedLeaf("leaf", "middle"));
                AtomicBoolean once = new AtomicBoolean();
                client.beforeCommit = request -> {
                    if (request.getReadModelIds().contains("leaf") && once.compareAndSet(false, true)) {
                        commit(fluxzero, new MoveChild("middle", "forbidden"));
                    }
                };
                CompletionException failure = assertThrows(CompletionException.class, () -> commit(fluxzero,
                        indirect ? new InspectIndirectLeaf("leaf") : new InspectLeafGraph("leaf")));
                Throwable cause = failure;
                while (cause.getCause() != null) {
                    cause = cause.getCause();
                }
                assertInstanceOf(IllegalCommandException.class, cause);
                assertEquals(0, fluxzero.apply(fc -> fc.modelRepository().load("leaf", Leaf.class).get().version())
                        .intValue());
            }
        }
    }

    @Test
    void lazyInterceptorOutputIsConsumedInsideTheReadScope() {
        GateClient client = new GateClient();
        try (Fluxzero fluxzero = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            commit(fluxzero, new CreateParent("parent", 10));
            commit(fluxzero, new SeedReceipt("receipt"));
            AtomicBoolean once = new AtomicBoolean();
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("receipt") && once.compareAndSet(false, true)) {
                    commit(fluxzero, new CreateChild("other", "parent"));
                }
            };
            commit(fluxzero, new LazySelection("receipt", "parent"));
            assertEquals(1, fluxzero.apply(fc -> fc.modelRepository().load("receipt", Receipt.class).get().observed())
                    .intValue());
        }
    }

    @Test
    void acceptRetainsRelationshipReadsThroughRepeatedRebases() {
        GateClient client = new GateClient();
        try (Fluxzero fluxzero = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            commit(fluxzero, new CreateParent("parent", 10));
            commit(fluxzero, new SeedReceipt("receipt"));
            AtomicInteger submissions = new AtomicInteger();
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("receipt")) {
                    assertFalse(request.getReadRelationships().isEmpty());
                    int number = submissions.incrementAndGet();
                    if (number <= 2) {
                        commit(fluxzero, new CreateChild("intervening-" + number, "parent"));
                    }
                }
            };
            commit(fluxzero, new AcceptGraphRead("receipt", "parent"));
            assertEquals(3, submissions.get());
            assertEquals(2, fluxzero.apply(fc -> fc.modelRepository().load("receipt", Receipt.class).get().observed())
                    .intValue());
        }
    }

    @Test
    void failRejectsAndAcceptRetainsAnAssertionOnlyDecision() {
        for (boolean fail : new boolean[]{true, false}) {
            GateClient client = new GateClient();
            try (Fluxzero fluxzero = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
                commit(fluxzero, new CreateParent("parent", 1));
                commit(fluxzero, new DetachedChild("contender"));
                AtomicBoolean once = new AtomicBoolean();
                client.beforeCommit = request -> {
                    if (request.getReadModelIds().contains("contender") && once.compareAndSet(false, true)) {
                        commit(fluxzero, new CreateChild("winner", "parent"));
                    }
                };
                if (fail) {
                    CompletionException rejected = assertThrows(CompletionException.class,
                            () -> commit(fluxzero, new FailChild("contender", "parent")));
                    assertInstanceOf(ModelCommitConflictException.class, rejected.getCause());
                } else {
                    commit(fluxzero, new AcceptChild("contender", "parent"));
                }
                assertEquals(fail ? 1 : 2, fluxzero.apply(fc -> fc.modelRepository()
                        .loadGraph("parent", ParentModel.class, Graph.Options.DEFAULT).children().size()).intValue());
            }
        }
    }

    @Test
    void retryRechecksInitiallyEmptyChildrenBeforeCreatingAnotherIdentity() throws Exception {
        GateClient client = new GateClient();
        try (Fluxzero fluxzero = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            commit(fluxzero, new CreateParent("parent", 1));
            attempts.set(0);
            client.armed.set(true);
            // A CompletableFuture wait can help execute queued common-pool tasks on the waiting test thread.
            // The gated contender must run independently so that this thread can release its transport gate.
            CompletableFuture<?> first = CompletableFuture.supplyAsync(() ->
                    commit(fluxzero, new CreateChild("first", "parent")),
                    task -> Thread.ofVirtual().name("graph-conflict-contender").start(task));
            first.whenComplete((result, failure) -> {
                if (failure != null) {
                    client.entered.completeExceptionally(failure);
                }
            });
            try {
                client.entered.get(10, TimeUnit.SECONDS);
                commit(fluxzero, new CreateChild("second", "parent"));
            } finally {
                client.release.complete(null);
            }
            CompletionException failure = assertThrows(CompletionException.class, first::join);
            Throwable cause = failure;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertInstanceOf(IllegalCommandException.class, cause);
            assertEquals(2, attempts.get());
            assertEquals(1, fluxzero.apply(fc -> fc.modelRepository()
                    .loadGraph("parent", ParentModel.class, Graph.Options.DEFAULT)
                    .children("children", Child.class).size()).intValue());
        } finally {
            client.release.complete(null);
        }
    }

    private static Object commit(Fluxzero fluxzero, Object command) {
        return fluxzero.apply(fc -> fc.executeModelCommit(Message.asMessage(command)).join());
    }

    /** Gates transport only; every conflict decision is made by the real LocalClient Model store. */
    private static class GateClient extends LocalClient {
        volatile Consumer<CommitModels> beforeCommit = ignored -> {};
        final AtomicBoolean armed = new AtomicBoolean();
        final CompletableFuture<Void> entered = new CompletableFuture<>();
        final CompletableFuture<Void> release = new CompletableFuture<>();

        GateClient() {
            super(null);
        }

        @Override
        protected EventStoreClient createEventStoreClient() {
            EventStoreClient delegate = super.createEventStoreClient();
            return (EventStoreClient) Proxy.newProxyInstance(EventStoreClient.class.getClassLoader(),
                    new Class<?>[]{EventStoreClient.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("commitModels")) {
                            beforeCommit.accept((CommitModels) arguments[0]);
                        }
                        if (method.getName().equals("commitModels") && armed.compareAndSet(true, false)) {
                            entered.complete(null);
                            release.get(10, TimeUnit.SECONDS);
                        }
                        try {
                            return method.invoke(delegate, arguments);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    @Model(conflictPolicy = ModelConflictPolicy.ACCEPT)
    record ParentModel(@EntityId String parentId, int capacity) {
    }

    @Model
    record Child(@EntityId String childId,
                 @Parent(value = ParentModel.class, pathInParent = "children") String parentId) {
    }

    record CreateParent(String parentId, int capacity) {
        @Apply
        ParentModel apply(@jakarta.annotation.Nullable ParentModel existing) {
            return new ParentModel(parentId, capacity);
        }
    }

    record CreateChild(String childId, String parentId) {
        @AssertLegal
        void check(Graph<ParentModel> parent) {
            if (childId.equals("first")) {
                attempts.incrementAndGet();
            }
            if (parent.children("children", Child.class).size() >= parent.get().capacity()) {
                throw new IllegalCommandException("Parent is full");
            }
        }

        @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
        Child apply() {
            return new Child(childId, parentId);
        }
    }

    record FailChild(String childId, String parentId) {
        @AssertLegal
        void check(Graph<ParentModel> parent) {
            new CreateChild(childId, parentId).check(parent);
        }

        @Apply(conflictPolicy = ModelConflictPolicy.FAIL)
        Child apply(@jakarta.annotation.Nullable Child existing) {
            return new Child(childId, parentId);
        }
    }

    record AcceptChild(String childId, String parentId) {
        @AssertLegal
        void check(Graph<ParentModel> parent) {
            new CreateChild(childId, parentId).check(parent);
        }

        @Apply(conflictPolicy = ModelConflictPolicy.ACCEPT)
        Child apply(@jakarta.annotation.Nullable Child existing) {
            return new Child(childId, parentId);
        }
    }

    @Model
    record Receipt(@EntityId String receiptId, int observed) {
    }

    record AcceptGraphRead(String receiptId, String parentId) {
        @Apply(conflictPolicy = ModelConflictPolicy.ACCEPT)
        Receipt apply(Graph<ParentModel> parent, @jakarta.annotation.Nullable Receipt existing) {
            return new Receipt(receiptId, parent.children("children", Child.class).size());
        }
    }

    record ObserveParentValue(String receiptId, String parentId) {
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
        Receipt apply(Graph<ParentModel> parent, @jakarta.annotation.Nullable Receipt existing) {
            return new Receipt(receiptId, parent.get() == null ? 0 : 1);
        }
    }

    record IgnoreParentGraph(String receiptId, String parentId) {
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
        Receipt apply(Graph<ParentModel> parent, @jakarta.annotation.Nullable Receipt existing) {
            return new Receipt(receiptId, 0);
        }
    }

    record SeedReceipt(String receiptId) {
        @Apply
        Receipt apply() {
            return new Receipt(receiptId, -1);
        }
    }

    record DetachedChild(String childId) {
        @Apply
        Child apply() {
            return new Child(childId, null);
        }
    }

    @Model
    record Leaf(@EntityId String leafId, @Parent(value = Child.class, pathInParent = "leaves") String childId,
                int version) {
    }

    record SeedLeaf(String leafId, String childId) {
        @Apply
        Leaf apply() {
            return new Leaf(leafId, childId, 0);
        }
    }

    record SetLeaf(String leafId, int version) {
        @Apply Leaf apply(Leaf current) { return new Leaf(leafId, current.childId(), version); }
    }

    record IncrementLeaves(String childId) {
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
        List<Leaf> apply(Graph<Child> child) {
            return child.childModels(Leaf.class).stream()
                    .map(leaf -> new Leaf(leaf.leafId(), leaf.childId(), leaf.version() + 1)).toList();
        }
    }

    record MoveChild(String childId, String parentId) {
        @Apply
        Child apply(@jakarta.annotation.Nullable Child existing) {
            return new Child(childId, parentId);
        }
    }

    record InspectIndirectLeaf(String leafId) {
        @AssertLegal
        void check(Graph<ParentModel> ancestor) {
            if (ancestor.get().capacity() == 0) {
                throw new IllegalCommandException("Ancestor is forbidden");
            }
        }

        @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
        Leaf apply(Leaf leaf) {
            return new Leaf(leaf.leafId(), leaf.childId(), leaf.version() + 1);
        }
    }

    record InspectLeafGraph(String leafId) {
        @AssertLegal
        void check(Graph<Leaf> leaf) {
            new InspectIndirectLeaf(leafId).check(leaf.ancestor(ParentModel.class).orElseThrow());
        }

        @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
        Leaf apply(Leaf leaf) {
            return new Leaf(leaf.leafId(), leaf.childId(), leaf.version() + 1);
        }
    }

    record LazySelection(String receiptId, String parentId) {
        @InterceptApply
        java.util.stream.Stream<RetryReceipt> intercept(Graph<ParentModel> parent) {
            return java.util.stream.Stream.of(receiptId).map(id ->
                    new RetryReceipt(id, parent.children("children", Child.class).size()));
        }
    }

    record RetryReceipt(String receiptId, int observed) {
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
        Receipt apply(@jakarta.annotation.Nullable Receipt existing) {
            return new Receipt(receiptId, observed);
        }
    }
}
