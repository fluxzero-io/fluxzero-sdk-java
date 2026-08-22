/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.persisting.eventsourcing.client.ModelCommitBatchingClient;
import io.fluxzero.sdk.tracking.handling.Invocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class ModelBatchScopeTest {

    @Test
    void publicCommitIsANoOpWithoutCurrentChanges() {
        Fluxzero previous = Fluxzero.instance.get();
        try {
            Fluxzero.instance.set(mock(Fluxzero.class, CALLS_REAL_METHODS));

            CompletableFuture<Void> result = Fluxzero.commit();

            assertTrue(result.isDone());
            assertSame(result, ModelBatchScope.commitCurrent());
        } finally {
            Fluxzero.instance.set(previous);
        }
    }

    @Test
    void explicitCommitReleasesCurrentEntryAndRemovesItFromDeferredTransport() {
        AtomicInteger deferredCapacity = new AtomicInteger(-1);
        AtomicInteger skipped = new AtomicInteger();
        List<String> committed = new ArrayList<>();
        ModelCommitBatchingClient.ModelCommitBatch deferred = batch(
                skipped, new AtomicInteger());
        ModelBatchScope.BatchLifecycle lifecycle = new ModelBatchScope.BatchLifecycle(
                () -> null,
                capacity -> {
                    deferredCapacity.set(capacity);
                    return deferred;
                });
        AtomicReference<CompletableFuture<Void>> explicit = new AtomicReference<>();

        DeserializingMessage.forEachInBatch(
                List.of(message("first"), message("second")), current -> {
                    String id = current.getPayload().toString();
                    ModelBatchScope.CommitCoordination entry = ModelBatchScope.register(
                            this, current, ModelCommitPolicy.ASYNC_AFTER_BATCH, lifecycle);
                    entry.attempt().evaluated(
                            0L, List.of(id), Map.of(id, AliasModel.class),
                            List.of(new CommitAttempt.Step(
                                    current, List.of(Change.applied(
                                            id, AliasModel.class, -1L, null,
                                            null, new AliasModel(id, id, 1),
                                            null, null, false)))));
                    ModelBatchScope.stage(null, entry);
                    entry.initialize(List.of(id));
                    entry.submit(ignored -> {
                        committed.add(id);
                        return CompletableFuture.completedFuture(null);
                    });
                    if ("first".equals(id)) {
                        explicit.set(ModelBatchScope.commitCurrent());
                        assertSame(explicit.get(), ModelBatchScope.commitCurrent());
                        assertEquals(List.of("first"), committed);
                    } else {
                        assertEquals(List.of("first"), committed);
                    }
                });

        assertTrue(explicit.get().isDone());
        assertEquals(List.of("first", "second"), committed);
        assertEquals(1, deferredCapacity.get());
        assertEquals(1, skipped.get());
    }

    @Test
    void explicitCommitFlushesTheExistingReadyTransportAndReturnsItsCompletion() {
        AtomicInteger flushed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        ModelCommitBatchingClient.ModelCommitBatch ready = batch(skipped, flushed);
        ModelBatchScope.BatchLifecycle lifecycle = new ModelBatchScope.BatchLifecycle(
                () -> ready,
                ignored -> null);
        CompletableFuture<Object> durable = new CompletableFuture<>();
        AtomicReference<CompletableFuture<Void>> explicit = new AtomicReference<>();

        DeserializingMessage.forEachInBatch(List.of(message("ready")), current -> {
            ModelBatchScope.CommitCoordination entry = ModelBatchScope.register(
                    this, current,
                    ModelCommitPolicy.ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH,
                    lifecycle);
            entry.attempt().evaluated(
                    0L, List.of("ready"), Map.of("ready", AliasModel.class),
                    List.of(new CommitAttempt.Step(
                            current, List.of(Change.applied(
                                    "ready", AliasModel.class, -1L, null,
                                    null, new AliasModel("ready", "ready", 1),
                                    null, null, false)))));
            ModelBatchScope.stage(null, entry);
            entry.initialize(List.of("ready"));
            entry.submit(ignored -> durable);

            explicit.set(ModelBatchScope.commitCurrent());
            assertSame(entry.attempt().completion(), explicit.get());
            assertSame(explicit.get(), ModelBatchScope.commitCurrent());
            assertFalse(explicit.get().isDone());
            assertEquals(1, flushed.get());
            durable.complete(null);
            explicit.get().join();
        });

        assertEquals(1, flushed.get());
        assertEquals(1, skipped.get());
    }

    @Test
    void exposesPendingValuesAndAliasChangesOnlyInsideTheirMessageBatch() {
        AliasModel before = new AliasModel("model-1", "old", 1);
        AliasModel after = new AliasModel("model-1", "new", 2);
        Entity<AliasModel> durable = entity(before);

        DeserializingMessage.forEachInBatch(
                List.of(message("first"), message("second")), current -> {
                    if (DeserializingMessage.getMessageBatchIndex() == 0) {
                        stage(null, evaluation(current, before, after));
                        assertEquals(after,
                                     ModelBatchScope.overlayCurrent(
                                             null, "model-1", AliasModel.class, durable).get());
                        assertEquals(after,
                                     ModelBatchScope.overlayCurrent(
                                             null, "new", AliasModel.class, durable).get());
                        assertFalse(ModelBatchScope.overlayCurrent(
                                null, "old", AliasModel.class, durable).isPresent());
                    } else {
                        assertEquals(after,
                                     ModelBatchScope.overlayCurrent(
                                             null, "model-1", AliasModel.class, durable).get());
                        Entity<?> staged = ModelBatchScope.currentValues(null).get("model-1");
                        assertEquals(after, staged.get());
                        assertTrue(ModelBatchScope.existedBefore(staged));
                    }
                });

        assertSame(durable, ModelBatchScope.overlayCurrent(
                null, "model-1", AliasModel.class, durable));
    }

    @Test
    void completedAndFailedStagesStopShadowingTheDurableRepository() {
        AliasModel before = new AliasModel("model-1", "old", 1);
        AliasModel after = new AliasModel("model-1", "new", 2);
        Entity<AliasModel> durable = entity(before);

        DeserializingMessage.forEachInBatch(
                List.of(message("success")), current -> {
                    CompletableFuture<Object> stage = stagePending(
                            null, evaluation(current, before, after));
                    assertEquals(after,
                                 ModelBatchScope.overlayCurrent(
                                         null, "model-1", AliasModel.class, durable).get());
                    stage.complete(null);
                    assertSame(durable, ModelBatchScope.overlayCurrent(
                            null, "model-1", AliasModel.class, durable));
                });

        DeserializingMessage.forEachInBatch(
                List.of(message("failure")), current -> {
                    CompletableFuture<Object> stage = stagePending(
                            null, evaluation(current, before, after));
                    stage.completeExceptionally(
                            new IllegalStateException("boom"));
                    assertSame(durable, ModelBatchScope.overlayCurrent(
                            null, "model-1", AliasModel.class, durable));
                });
    }

    @Test
    void pendingReadsNeverReadTheirOwnSpeculation() {
        AliasModel before = new AliasModel("model-1", "old", 1);
        AliasModel after = new AliasModel("model-1", "new", 2);
        Entity<AliasModel> durable = entity(before);

        DeserializingMessage.forEachInBatch(
                List.of(message("producer"), message("consumer")), current -> {
                    if (DeserializingMessage.getMessageBatchIndex() == 0) {
                        stagePending(null, evaluation(current, before, after));
                        ModelBatchScope.withMessageDependency(current, () -> {
                            assertSame(durable, ModelBatchScope.overlayCurrent(
                                    null, "model-1", AliasModel.class, durable));
                            return null;
                        });
                    } else {
                        assertEquals(after, ModelBatchScope.overlayCurrent(
                                null, "model-1", AliasModel.class, durable).get());
                    }
                });
    }

    @Test
    void ordinaryPendingReadDelaysResultPublicationUntilItsProducerCompletes() {
        AliasModel before = new AliasModel("model-1", "old", 1);
        AliasModel after = new AliasModel("model-1", "new", 2);
        Entity<AliasModel> durable = entity(before);
        AtomicReference<CompletableFuture<Object>> producer = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> barrier =
                new AtomicReference<>();

        DeserializingMessage.forEachInBatch(
                List.of(message("producer"), message("consumer")), current -> {
                    if (DeserializingMessage.getMessageBatchIndex() == 0) {
                        producer.set(stagePending(
                                null, evaluation(current, before, after)));
                    } else {
                        assertEquals(after, ModelBatchScope.overlayCurrent(
                                null, "model-1", AliasModel.class, durable).get());
                        barrier.set(Invocation.resultPublicationBarrier(current));
                        assertFalse(barrier.get().isDone());
                    }
                });

        producer.get().complete(null);
        barrier.get().join();
        assertTrue(barrier.get().isDone());
    }

    @Test
    void pendingAliasNeverShadowsAnExistingPrimaryId() {
        AliasModel before = new AliasModel("model-1", "old", 1);
        AliasModel after = new AliasModel("model-1", "shared", 2);
        Entity<AliasModel> primary = entity(
                new AliasModel("shared", "primary-alias", 7));

        DeserializingMessage.forEachInBatch(
                List.of(message("producer"), message("consumer")), current -> {
                    if (DeserializingMessage.getMessageBatchIndex() == 0) {
                        stage(null, evaluation(current, before, after));
                    } else {
                        assertSame(primary, ModelBatchScope.overlayCurrent(
                                null, "shared", AliasModel.class, primary));
                    }
                });
    }

    @Test
    void pendingValuesDoNotCrossOrderedRoutingSegments() {
        AliasModel before = new AliasModel("model-1", "old", 1);
        AliasModel after = new AliasModel("model-1", "new", 2);
        Entity<AliasModel> durable = entity(before);

        DeserializingMessage.forEachInBatch(
                List.of(message("producer", 1), message("consumer", 2)), current -> {
                    if (DeserializingMessage.getMessageBatchIndex() == 0) {
                        stage(null, evaluation(current, before, after));
                    } else {
                        assertSame(durable, ModelBatchScope.overlayCurrent(
                                null, "model-1", AliasModel.class, durable));
                    }
                });
    }

    @Test
    void pendingValuesDoNotCrossNamespaces() {
        AliasModel before = new AliasModel("model-1", "old", 1);
        AliasModel after = new AliasModel("model-1", "new", 2);
        Entity<AliasModel> durable = entity(before);

        DeserializingMessage.forEachInBatch(
                List.of(message("producer"), message("consumer")), current -> {
                    if (DeserializingMessage.getMessageBatchIndex() == 0) {
                        stage("customer-a", evaluation(current, before, after));
                    } else {
                        assertEquals(
                                after,
                                ModelBatchScope.overlayCurrent(
                                        "customer-a", "model-1",
                                        AliasModel.class, durable).get());
                        assertSame(
                                durable,
                                ModelBatchScope.overlayCurrent(
                                        "customer-b", "model-1",
                                        AliasModel.class, durable));
                    }
                });
    }

    @Test
    void retainsMultipleExplicitOperationsInOneMessageAndOriginalCreationState() {
        AliasModel first = new AliasModel("model-1", "first", 1);
        AliasModel updated = new AliasModel("model-1", "updated", 2);
        AliasModel second = new AliasModel("model-2", "second", 1);

        DeserializingMessage.forEachInBatch(
                List.of(message("handler")), current -> {
                    stage(null, evaluation(current, first.id(), null, first));
                    stage(null, evaluation(current, updated.id(), first, updated));
                    stage(null, evaluation(current, second.id(), null, second));

                    Map<String, Entity<?>> staged = ModelBatchScope.currentValues(null);
                    assertEquals(updated, staged.get(first.id()).get());
                    assertEquals(second, staged.get(second.id()).get());
                    assertFalse(ModelBatchScope.existedBefore(staged.get(first.id())));
                    assertFalse(ModelBatchScope.existedBefore(staged.get(second.id())));
                });
    }

    @Test
    void indexesConcreteEntityMetadataWhenAnApplyTargetsAnInterface() {
        PolymorphicAliasModel before =
                new PolymorphicAliasModel("poly-1", "old");
        PolymorphicAliasModel after =
                new PolymorphicAliasModel("poly-1", "new");

        DeserializingMessage.forEachInBatch(
                List.of(message("producer"), message("consumer")), current -> {
                    if (DeserializingMessage.getMessageBatchIndex() == 0) {
                        stage(
                                null,
                                CommitAttempt.fromSteps(
                                        0L,
                                        List.of(before.id()),
                                        Map.of(before.id(), ModelContract.class),
                                        List.of(new CommitAttempt.Step(
                                                current, List.of(Change.applied(
                                                        before.id(), ModelContract.class,
                                                        0L, null, before, after, null,
                                                        null, false))))));
                    } else {
                        Entity<Object> empty = ImmutableModelRoot.builder()
                                .id("new")
                                .type(Object.class)
                                .idProperty("id")
                                .build();
                        assertEquals(
                                after,
                                ModelBatchScope.overlayCurrent(
                                        null, "new", Object.class,
                                        empty).get());
                        assertEquals(
                                PolymorphicAliasModel.class,
                                ModelBatchScope.currentValue(
                                        null, before.id()).type());
                    }
                });
    }

    @Test
    void exposesPendingValuesInMessageOrder() {
        AliasModel first = new AliasModel("model-z", "first", 1);
        AliasModel second = new AliasModel("model-a", "second", 1);

        DeserializingMessage.forEachInBatch(
                List.of(message("first"), message("second"), message("read")), current -> {
                    int index = DeserializingMessage.getMessageBatchIndex();
                    if (index == 0) {
                        stage(null, evaluation(current, first.id(), null, first));
                    } else if (index == 1) {
                        stage(null, evaluation(current, second.id(), null, second));
                    } else {
                        assertEquals(
                                List.of(first.id(), second.id()),
                                new ArrayList<>(
                                        ModelBatchScope.currentValues(null)
                                                .keySet()));
                    }
                });
    }

    @Test
    void keepsTheHandlerBeginStateWhenRegisteringCompletionDependencies() {
        AliasModel value = new AliasModel("model-1", "alias", 1);
        DeserializingMessage message = message("producer");
        CommitAttempt beginState = CommitAttempt.createSingle(
                0L, value.id(), AliasModel.class,
                MutationPlan.Access.READ_WRITE, List.of("id"), entity(value));
        beginState.attachTo(message);

        DeserializingMessage.forEachInBatch(List.of(message), current -> {
            stagePending(null, evaluation(current, value.id(), null, value));
            assertSame(beginState, current.getContext(CommitAttempt.class).orElseThrow());
        });
    }

    @Test
    void resolvesAnAliasToTheLastChangedModelWithinOneAttempt() {
        AliasModel first = new AliasModel("model-1", "shared", 1);
        AliasModel second = new AliasModel("model-2", "shared", 2);

        DeserializingMessage.forEachInBatch(
                List.of(message("producer"), message("consumer")), current -> {
                    if (DeserializingMessage.getMessageBatchIndex() == 0) {
                        stage(null, CommitAttempt.fromChanges(
                                0L, List.of(first.id(), second.id()),
                                Map.of(first.id(), AliasModel.class,
                                       second.id(), AliasModel.class),
                                current, List.of(
                                        Change.applied(
                                                first.id(), AliasModel.class, -1L, null,
                                                null, first, null, null, false),
                                        Change.applied(
                                                second.id(), AliasModel.class, -1L, null,
                                                null, second, null, null, false))));
                        return;
                    }
                    Entity<Object> empty = ImmutableModelRoot.builder()
                            .id("shared").type(Object.class).idProperty("id").build();
                    assertEquals(second, ModelBatchScope.overlayCurrent(
                            null, "shared", Object.class, empty).get());
                });
    }

    private static CommitAttempt evaluation(
            DeserializingMessage message,
            AliasModel before,
            AliasModel after) {
        return evaluation(message, before.id(), before, after);
    }

    private static void stage(
            String namespace,
            CommitAttempt evaluation) {
        ModelBatchScope.stage(namespace, evaluation);
    }

    private static CompletableFuture<Object> stagePending(
            String namespace,
            CommitAttempt evaluation) {
        ModelBatchScope.stage(namespace, ModelBatchScope.CommitCoordination.direct(evaluation));
        return evaluation.completion();
    }

    private static CommitAttempt evaluation(
            DeserializingMessage message,
            String modelId,
            AliasModel before,
            AliasModel after) {
        return CommitAttempt.fromSteps(
                0L,
                List.of(modelId),
                Map.of(modelId, AliasModel.class),
                List.of(new CommitAttempt.Step(
                        message, List.of(Change.applied(
                                modelId, AliasModel.class,
                                0L, null, before, after, null,
                                null, false)))));
    }

    private static Entity<AliasModel> entity(AliasModel value) {
        return ImmutableModelRoot.<AliasModel>builder()
                .id(value.id())
                .type(AliasModel.class)
                .idProperty("id")
                .value(value)
                .build();
    }

    private static DeserializingMessage message(String payload) {
        return new DeserializingMessage(
                new Message(payload), MessageType.COMMAND,
                new JacksonSerializer());
    }

    private static DeserializingMessage message(
            String payload,
            int segment) {
        JacksonSerializer serializer = new JacksonSerializer();
        return new DeserializingMessage(
                new Message(payload).serialize(serializer)
                        .withSegment(segment),
                ignored -> payload,
                MessageType.COMMAND, null, serializer);
    }

    private static ModelCommitBatchingClient.ModelCommitBatch batch(
            AtomicInteger skipped,
            AtomicInteger flushed) {
        return new ModelCommitBatchingClient.ModelCommitBatch() {
            @Override
            public CompletableFuture<CommitModelsResult> add(
                    int slot,
                    CommitModels commit) {
                throw new AssertionError("The coordination test does not add transport payloads");
            }

            @Override
            public void skip(int slot) {
                skipped.incrementAndGet();
            }

            @Override
            public void flush() {
                flushed.incrementAndGet();
            }

            @Override
            public void fail(Throwable failure) {
                throw new AssertionError(failure);
            }
        };
    }

    @Model
    private record AliasModel(
            @EntityId String id,
            @Alias String alias,
            int value) {
    }

    private interface ModelContract {
    }

    @Model
    private record PolymorphicAliasModel(
            @EntityId String id,
            @Alias String alias) implements ModelContract {
    }
}
