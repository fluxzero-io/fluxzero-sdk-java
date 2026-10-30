/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fluxzero.sdk.modeling;

import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.serialization.JsonUtils;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.GetModelGraph;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.IllegalCommandException;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ModelCascadePreparationTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void deletingIndependentStateDoesNotReconstructAnUnrelatedGraph(boolean async, boolean modifyRoot) {
        CountingClient client = new CountingClient();
        Complete command = new Complete("scratch", "root", "receipt", modifyRoot);
        fixture(client, async)
                .given(fc -> seedHistory(fc, 8, 4))
                .givenCommands(new CreateScratch("scratch"))
                .given(fc -> { fc.cache().clear(); client.reset(); })
                .whenCommand(command)
                .expectSuccessfulResult().expectEvents(command).expectNoErrors()
                .expectThat(fc -> {
                    assertEquals(1, client.commits.size());
                    assertEquals(1, client.commits.getFirst().getSubsteps().size());
                    assertTrue(client.commits.getFirst().getReadModelIds().contains("root"));
                    assertFalse(client.graphRoots.contains("root"), client.graphRoots::toString);
                    assertTrue(client.eventStreams.stream().noneMatch(id -> id.startsWith("entry-")),
                               client.eventStreams::toString);
                    fc.cache().clear();
                    assertNull(Fluxzero.loadModel("scratch", Scratch.class).get());
                    assertEquals(new Receipt("receipt", "root"), Fluxzero.loadModel("receipt", Receipt.class).get());
                    assertEquals(new Root("root", true, modifyRoot ? 1 : 0), Fluxzero.loadModel("root", Root.class).get());
                    assertEquals(new Entry("entry-7", "root", 3), Fluxzero.loadModel("entry-7", Entry.class).get());
                });
    }

    @Test
    void anUnchangedReadDependencyStillRejectsAConcurrentRevocation() {
        CountingClient client = new CountingClient();
        AtomicBoolean raced = new AtomicBoolean();
        try (Fluxzero app = application(client); Fluxzero writer = application(client)) {
            commit(app, new PutRoot("root", true));
            commit(app, new CreateScratch("scratch"));
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("receipt") && raced.compareAndSet(false, true)) {
                    commit(writer, new PutRoot("root", false));
                }
            };
            var failure = assertThrows(CompletionException.class,
                    () -> commit(app, new Complete("scratch", "root", "receipt", false)));
            assertInstanceOf(IllegalCommandException.class, failure.getCause());
            assertTrue(raced.get());
            app.cache().clear();
            app.apply(fc -> {
                assertNotNull(Fluxzero.loadModel("scratch", Scratch.class).get());
                assertNull(Fluxzero.loadModel("receipt", Receipt.class).get());
                assertFalse(Fluxzero.loadModel("root", Root.class).get().enabled());
                return null;
            });
        }
    }

    @Test
    void aChildAttachedAfterTheEmptyGraphReadForcesACompleteCascadeRetry() {
        CountingClient client = new CountingClient();
        AtomicBoolean raced = new AtomicBoolean();
        try (Fluxzero app = application(client); Fluxzero writer = application(client)) {
            app.registerHandlers(Owned.class, Branch.class);
            commit(app, new PutRoot("root", true));
            commit(app, new CreateScratch("scratch"));
            client.beforeCommit = request -> {
                if (request.getReadModelIds().contains("receipt") && raced.compareAndSet(false, true)) {
                    commit(writer, new CreateOwnedTree("late-child", "scratch", "grandchild"));
                }
            };
            commit(app, new Complete("scratch", "root", "receipt", false));
            assertTrue(raced.get());
            assertEquals(2, client.commits.stream().filter(c -> c.getReadModelIds().contains("receipt")).count());
            app.cache().clear();
            app.apply(fc -> {
                assertNull(Fluxzero.loadModel("scratch", Scratch.class).get());
                assertNull(Fluxzero.loadModel("late-child", Owned.class).get());
                assertNull(Fluxzero.loadModel("grandchild", Branch.class).get());
                assertNotNull(Fluxzero.loadModel("receipt", Receipt.class).get());
                return null;
            });
        }
    }

    @Test
    void movingASubtreeIntoADeletedRootThenApplyingANoopStillDeletesItsGrandchildren() {
        TestFixture.create()
                .givenCommands(new PutBranch("deleted", null, null), new PutBranch("retained", null, null),
                               new PutBranch("child", "retained", null), new PutBranch("grandchild", "child", null))
                .whenCommand(new MoveThenDelete("deleted", "child"))
                .expectSuccessfulResult().expectNoErrors()
                .expectThat(fc -> {
                    fc.cache().clear();
                    for (String id : List.of("deleted", "child", "grandchild")) {
                        assertNull(Fluxzero.loadModel(id, Branch.class).get(), id);
                    }
                    assertNotNull(Fluxzero.loadModel("retained", Branch.class).get());
                });
    }

    @Test
    void committedStateCanBeRecoveredByAFreshApplicationAfterALostResponse() {
        CountingClient client = new CountingClient();
        try (Fluxzero first = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
            seedHistory(first, 2, 3);
            commit(first, new CreateScratch("scratch"));
            AtomicReference<CommitModels> original = new AtomicReference<>();
            client.beforeCommit = request -> original.set(JsonUtils.fromJson(JsonUtils.asBytes(request), CommitModels.class));
            client.loseNextResponse.set(true);
            assertThrows(CompletionException.class,
                         () -> commit(first, new Complete("scratch", "root", "receipt", true)));
            CommitModels committed = original.get();
            client.beforeCommit = ignored -> {};
            try (Fluxzero recovered = DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client)) {
                recovered.apply(fc -> {
                    assertNull(Fluxzero.loadModel("scratch", Scratch.class).get());
                    assertEquals(new Receipt("receipt", "root"), Fluxzero.loadModel("receipt", Receipt.class).get());
                    assertEquals(new Root("root", true, 1), Fluxzero.loadModel("root", Root.class).get());
                    long sequence = Fluxzero.loadModel("receipt", Receipt.class).sequenceNumber();
                    assertTrue(client.getEventStoreClient().commitModels(committed).join().isAccepted());
                    fc.cache().clear();
                    assertEquals(sequence, Fluxzero.loadModel("receipt", Receipt.class).sequenceNumber());
                    return null;
                });
            }
        }
    }

    static Fluxzero application(CountingClient client) {
        return DefaultFluxzero.builder().disableKeepalive().disableShutdownHook().build(client);
    }

    static TestFixture fixture(CountingClient client, boolean async) {
        return new TestFixture(DefaultFluxzero.builder(), fc -> List.of(Owned.class, Branch.class), client, !async) {};
    }

    static void seedHistory(Fluxzero fc, int children, int events) {
        commit(fc, new PutRoot("root", true));
        for (int child = 0; child < children; child++) {
            for (int event = 0; event < events; event++) {
                commit(fc, new PutEntry("entry-" + child, "root", event));
            }
        }
    }

    static void commit(Fluxzero fc, Object command) {
        fc.apply(app -> app.executeModelCommit(Message.asMessage(command)).join());
    }

    /** Counts real storage calls; the delegate owns persistence and all conflict decisions. */
    static class CountingClient extends LocalClient {
        final List<String> graphRoots = new CopyOnWriteArrayList<>();
        final List<String> eventStreams = new CopyOnWriteArrayList<>();
        final List<CommitModels> commits = new CopyOnWriteArrayList<>();
        final AtomicLong eventCount = new AtomicLong(), eventBytes = new AtomicLong();
        final AtomicBoolean loseNextResponse = new AtomicBoolean();
        volatile Consumer<CommitModels> beforeCommit = ignored -> {};

        CountingClient() { super(null); }

        void reset() {
            graphRoots.clear(); eventStreams.clear(); commits.clear();
            eventCount.set(0); eventBytes.set(0);
        }

        @Override
        protected EventStoreClient createEventStoreClient() {
            EventStoreClient delegate = super.createEventStoreClient();
            return (EventStoreClient) Proxy.newProxyInstance(EventStoreClient.class.getClassLoader(),
                    new Class<?>[]{EventStoreClient.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("commitModels")) {
                            CommitModels request = (CommitModels) arguments[0];
                            commits.add(request);
                            beforeCommit.accept(request);
                        } else if (method.getName().equals("getModelGraph")) {
                            graphRoots.addAll(((GetModelGraph) arguments[0]).getModelIds());
                        }
                        try {
                            Object result = method.invoke(delegate, arguments);
                            if (method.getName().equals("commitModels") && loseNextResponse.compareAndSet(true, false)) {
                                return ((CompletableFuture<?>) result).thenCompose(ignored ->
                                        CompletableFuture.failedFuture(new IllegalStateException("Lost commit response")));
                            }
                            if (result instanceof GetModelEventsResult events) {
                                events.getStreams().forEach(stream -> eventStreams.add(stream.getModelId()));
                                eventCount.addAndGet(events.getPayloads().size());
                                eventBytes.addAndGet(events.getPayloads().stream().mapToLong(p -> p.getEvent().getBytes()).sum());
                            }
                            return result;
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    @Model(searchable = false)
    record Root(@EntityId String rootId, boolean enabled, int version) {}
    @Model(searchable = false)
    record Entry(@EntityId String entryId,
                 @Parent(value = Root.class, deleteOnParentDeletion = false) String rootId, int version) {}
    @Model(searchable = false, persistence = ModelPersistence.DOCUMENT, cached = false)
    record Scratch(@EntityId String scratchId) {}
    @Model(searchable = false)
    record Receipt(@EntityId String receiptId,
                   @Parent(value = Root.class, deleteOnParentDeletion = false) String rootId) {}
    @Model(searchable = false)
    record Owned(@EntityId String ownedId, @Parent(Scratch.class) String scratchId) {}
    @Model(searchable = false)
    record Branch(@EntityId String branchId, @Parent(Branch.class) String parentId,
                  @Parent(Owned.class) String ownedId) {}

    record PutRoot(String rootId, boolean enabled) {
        @Apply Root apply(@Nullable Root before) { return new Root(rootId, enabled, 0); }
    }
    record PutEntry(String entryId, String rootId, int version) {
        @Apply Entry apply(@Nullable Entry before) { return new Entry(entryId, rootId, version); }
    }
    record CreateScratch(String scratchId) {
        @Apply Scratch apply() { return new Scratch(scratchId); }
    }
    record CreateOwned(String ownedId, String scratchId) {
        @Apply Owned apply() { return new Owned(ownedId, scratchId); }
    }
    record CreateOwnedTree(String ownedId, String scratchId, String branchId) {
        @Apply Owned owned() { return new Owned(ownedId, scratchId); }
        @Apply Branch branch() { return new Branch(branchId, null, ownedId); }
    }
    record PutBranch(String branchId, String parentId, String ownedId) {
        @Apply Branch apply(@Nullable Branch before) { return new Branch(branchId, parentId, ownedId); }
    }
    record Complete(String scratchId, String rootId, String receiptId, boolean modifyRoot) {
        @AssertLegal void check(Root root) {
            if (!root.enabled()) { throw new IllegalCommandException("Root is disabled"); }
        }
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY) Scratch consume(Scratch before) { return null; }
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY) Root update(Root before) {
            return modifyRoot ? new Root(rootId, before.enabled(), before.version() + 1) : before;
        }
        @Apply(conflictPolicy = ModelConflictPolicy.RETRY) Receipt create() { return new Receipt(receiptId, rootId); }
    }
    record DeleteBranch(String branchId) {
        @Apply Branch apply(Branch before) { return null; }
    }
    record TouchBranch(String branchId) {
        @Apply Branch apply(Branch before) { return before; }
    }
    record MoveThenDelete(String branchId, String childId) {
        @InterceptApply List<?> expand() {
            return List.of(new PutBranch(childId, branchId, null), new TouchBranch(childId), new DeleteBranch(branchId));
        }
    }
}
