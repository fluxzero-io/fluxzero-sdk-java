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
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ModelGraphReadConflictTest {
    private static final AtomicInteger attempts = new AtomicInteger();

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
        return fluxzero.apply(fc -> fc.executeModelCommit(new Message(command)).join());
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
        ParentModel apply() {
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
        Child apply() {
            return new Child(childId, parentId);
        }
    }

    record AcceptChild(String childId, String parentId) {
        @AssertLegal
        void check(Graph<ParentModel> parent) {
            new CreateChild(childId, parentId).check(parent);
        }

        @Apply(conflictPolicy = ModelConflictPolicy.ACCEPT)
        Child apply() {
            return new Child(childId, parentId);
        }
    }

    @Model
    record Receipt(@EntityId String receiptId, int observed) {
    }

    record AcceptGraphRead(String receiptId, String parentId) {
        @Apply(conflictPolicy = ModelConflictPolicy.ACCEPT)
        Receipt apply(Graph<ParentModel> parent) {
            return new Receipt(receiptId, parent.children("children", Child.class).size());
        }
    }

    record ObserveParentValue(String receiptId, String parentId) {
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
        Receipt apply(Graph<ParentModel> parent) {
            return new Receipt(receiptId, parent.get() == null ? 0 : 1);
        }
    }

    record IgnoreParentGraph(String receiptId, String parentId) {
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
        Receipt apply(Graph<ParentModel> parent) {
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

    record MoveChild(String childId, String parentId) {
        @Apply
        Child apply() {
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
        Receipt apply() {
            return new Receipt(receiptId, observed);
        }
    }
}
