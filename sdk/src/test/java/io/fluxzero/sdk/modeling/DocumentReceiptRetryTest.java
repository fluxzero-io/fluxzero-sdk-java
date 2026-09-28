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

import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.TrackModelUpdatesResult;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.LocalEventStoreClient;
import io.fluxzero.sdk.persisting.search.client.InMemorySearchStore;
import io.fluxzero.sdk.test.TestFixture;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.common.api.modeling.ModelConflictPolicy.RETRY;
import static io.fluxzero.sdk.modeling.AutomaticModelHandling.DISABLED;
import static org.junit.jupiter.api.Assertions.*;

class DocumentReceiptRetryTest {
    private static final ThreadLocal<CyclicBarrier> INITIAL_READS = new ThreadLocal<>();

    @AfterEach
    void close() { TestFixture.shutDownActiveFixtures(); }

    @ParameterizedTest
    @CsvSource({"true,true", "false,true", "true,false", "false,false"})
    void concurrentCompletionWaitsForDocumentsAndRechecksEvidence(boolean synchronous, boolean sameEvidence)
            throws Exception {
        GateClient client = new GateClient();
        TestFixture fixture = new GateFixture(client, synchronous);
        var workId = new WorkId("work");
        var receiptId = new ReceiptId("receipt");
        Fluxzero.assertAndApply(new CreateWork(workId));
        Fluxzero.commit().join();
        var fluxzero = fixture.getFluxzero();
        var initialReads = new CyclicBarrier(2);
        client.delayNextMaterialization.set(true);
        int rejected = 0;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var completions = new ExecutorCompletionService<Boolean>(executor);
            for (int i = 0; i < 2; i++) {
                String evidence = sameEvidence ? "same-evidence" : "evidence-" + i;
                completions.submit(() -> fluxzero.apply(ignored -> {
                    INITIAL_READS.set(initialReads);
                    try {
                        Fluxzero.assertAndApply(new CompleteWork(workId, receiptId, evidence));
                        Fluxzero.commit().join();
                    } finally {
                        INITIAL_READS.remove();
                    }
                    return true;
                }));
            }
            try {
                for (int i = 0; i < 2; i++) {
                    var completed = completions.poll(5, TimeUnit.SECONDS);
                    assertNotNull(completed, "A commit neither completed nor reported its failure");
                    try {
                        assertTrue(completed.get());
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        while (cause.getCause() != null) { cause = cause.getCause(); }
                        assertInstanceOf(IllegalArgumentException.class, cause);
                        assertEquals("Different evidence", cause.getMessage());
                        rejected++;
                    }
                }
            } finally {
                client.allowMaterialization.countDown();
            }
        }
        assertEquals(1, client.pendingMaterializationReads.get(), "RETRY must observe and await the pending fence");
        assertTrue(Fluxzero.loadModel(workId).get().completed());
        assertEquals(sameEvidence ? 0 : 1, rejected);
        String storedEvidence = Fluxzero.loadModel(receiptId).get().evidence();
        assertTrue(sameEvidence ? storedEvidence.equals("same-evidence") : storedEvidence.startsWith("evidence-"));
        assertEquals(2, client.materializedCommits.get(), "Only creation and the winning completion may write documents");
    }

    static class GateFixture extends TestFixture {
        GateFixture(GateClient client, boolean synchronous) {
            super(DefaultFluxzero.builder().disableAutomaticModelCaching(),
                  ignored -> java.util.List.of(Work.class, Receipt.class), client, synchronous);
        }
    }

    static class GateClient extends LocalClient {
        final AtomicBoolean delayNextMaterialization = new AtomicBoolean();
        final AtomicInteger pendingMaterializationReads = new AtomicInteger();
        final AtomicInteger materializedCommits = new AtomicInteger();
        final CountDownLatch allowMaterialization = new CountDownLatch(1);

        GateClient() { super(null); }

        @Override
        protected EventStoreClient createEventStoreClient() {
            LocalEventStoreClient delegate = (LocalEventStoreClient) super.createEventStoreClient();
            InMemorySearchStore documents = (InMemorySearchStore) getSearchClient();
            delegate.getMessageStore().setModelCommitMaterializer((commit, updates, excluded) -> {
                if (delayNextMaterialization.compareAndSet(true, false)) {
                    try {
                        assertTrue(allowMaterialization.await(10, TimeUnit.SECONDS), "RETRY did not await materialization");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
                materializedCommits.incrementAndGet();
                return documents.prepareModelCommit(commit, updates, excluded);
            });
            return (EventStoreClient) Proxy.newProxyInstance(EventStoreClient.class.getClassLoader(),
                    new Class<?>[]{EventStoreClient.class}, (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(delegate, args);
                            if (method.getName().equals("trackModelUpdates")) {
                                @SuppressWarnings("unchecked")
                                var position = ((CompletableFuture<TrackModelUpdatesResult>) result).join();
                                if (allowMaterialization.getCount() > 0
                                        && position.getMaterializedStateIndex() < position.getCurrentStateIndex()) {
                                    pendingMaterializationReads.incrementAndGet();
                                    allowMaterialization.countDown();
                                }
                            }
                            return result;
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    @Model(persistence = ModelPersistence.DOCUMENT, cached = false, eventPublication = EventPublication.NEVER,
            conflictPolicy = ModelConflictPolicy.FAIL, automaticHandling = DISABLED,
            document = @DocumentProjection(searchable = false))
    record Work(@EntityId WorkId workId, boolean completed) {}

    @Model(persistence = ModelPersistence.DOCUMENT, cached = false, eventPublication = EventPublication.NEVER,
            conflictPolicy = ModelConflictPolicy.FAIL, automaticHandling = DISABLED,
            document = @DocumentProjection(searchable = false))
    record Receipt(@EntityId ReceiptId receiptId, String evidence) {}

    static final class WorkId extends Id<Work> { public WorkId(String id) { super(id); } }
    static final class ReceiptId extends Id<Receipt> { public ReceiptId(String id) { super(id); } }

    record CreateWork(WorkId workId) {
        @Apply(automaticHandling = DISABLED)
        Work apply() { return new Work(workId, false); }
    }

    record CompleteWork(WorkId workId, ReceiptId receiptId, String evidence) {
        @AssertLegal
        void assertSameEvidence(@Nullable Receipt current) throws Exception {
            if (current == null) INITIAL_READS.get().await(5, TimeUnit.SECONDS);
            if (current != null && !current.evidence().equals(evidence)) {
                throw new IllegalArgumentException("Different evidence");
            }
        }

        @Apply(automaticHandling = DISABLED, conflictPolicy = RETRY)
        Receipt record(@Nullable Receipt current) {
            return current == null ? new Receipt(receiptId, evidence) : current;
        }

        @Apply(automaticHandling = DISABLED, conflictPolicy = RETRY)
        Work complete(@Nullable Work current) { return new Work(workId, true); }
    }
}