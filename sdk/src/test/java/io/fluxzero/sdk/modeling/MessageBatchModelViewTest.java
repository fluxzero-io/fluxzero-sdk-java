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
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.tracking.handling.Invocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageBatchModelViewTest {

    @Test
    void exposesPendingValuesAndAliasChangesOnlyInsideTheirMessageBatch() {
        AliasModel before = new AliasModel("model-1", "old", 1);
        AliasModel after = new AliasModel("model-1", "new", 2);
        Entity<AliasModel> durable = entity(before);
        MessageBatchModelView.Stage[] stage = new MessageBatchModelView.Stage[1];

        DeserializingMessage.forEachInBatch(
                List.of(message("first"), message("second")), current -> {
                    if (DeserializingMessage.getMessageBatchIndex() == 0) {
                        stage[0] = MessageBatchModelView.stage(
                                null, evaluation(current, before, after), null);
                        assertEquals(after,
                                     MessageBatchModelView.overlayCurrent(
                                             null, "model-1", AliasModel.class, durable).get());
                        assertEquals(after,
                                     MessageBatchModelView.overlayCurrent(
                                             null, "new", AliasModel.class, durable).get());
                        assertFalse(MessageBatchModelView.overlayCurrent(
                                null, "old", AliasModel.class, durable).isPresent());
                    } else {
                        assertEquals(after,
                                     MessageBatchModelView.overlayCurrent(
                                             null, "model-1", AliasModel.class, durable).get());
                        assertEquals(
                                Map.of("model-1",
                                       new MessageBatchModelView.StagedModel(
                                               "model-1", AliasModel.class,
                                               after, true)),
                                MessageBatchModelView.currentValues(null));
                    }
                });

        assertSame(durable, MessageBatchModelView.overlayCurrent(
                null, "model-1", AliasModel.class, durable));
    }

    @Test
    void completedAndFailedStagesStopShadowingTheDurableRepository() {
        AliasModel before = new AliasModel("model-1", "old", 1);
        AliasModel after = new AliasModel("model-1", "new", 2);
        Entity<AliasModel> durable = entity(before);

        DeserializingMessage.forEachInBatch(
                List.of(message("success")), current -> {
                    MessageBatchModelView.Stage stage = MessageBatchModelView.stage(
                            null, evaluation(current, before, after), null);
                    assertEquals(after,
                                 MessageBatchModelView.overlayCurrent(
                                         null, "model-1", AliasModel.class, durable).get());
                    stage.complete(null);
                    assertSame(durable, MessageBatchModelView.overlayCurrent(
                            null, "model-1", AliasModel.class, durable));
                });

        DeserializingMessage.forEachInBatch(
                List.of(message("failure")), current -> {
                    MessageBatchModelView.Stage stage = MessageBatchModelView.stage(
                            null, evaluation(current, before, after), null);
                    stage.complete(new IllegalStateException("boom"));
                    assertSame(durable, MessageBatchModelView.overlayCurrent(
                            null, "model-1", AliasModel.class, durable));
                });
    }

    @Test
    void pendingReadsRegisterTheirProducerAndNeverReadTheirOwnSpeculation() {
        AliasModel before = new AliasModel("model-1", "old", 1);
        AliasModel after = new AliasModel("model-1", "new", 2);
        Entity<AliasModel> durable = entity(before);
        Dependency producer = new Dependency();
        Dependency consumer = new Dependency();

        DeserializingMessage.forEachInBatch(
                List.of(message("producer"), message("consumer")), current -> {
                    if (DeserializingMessage.getMessageBatchIndex() == 0) {
                        MessageBatchModelView.stage(
                                null, evaluation(current, before, after), producer);
                        MessageBatchModelView.withMessageDependency(current, () -> {
                            assertSame(durable, MessageBatchModelView.overlayCurrent(
                                    null, "model-1", AliasModel.class, durable));
                            return null;
                        });
                    } else {
                        MessageBatchModelView.withDependency(consumer, () -> {
                            assertEquals(after, MessageBatchModelView.overlayCurrent(
                                    null, "model-1", AliasModel.class, durable).get());
                            return null;
                        });
                    }
                });

        assertEquals(List.of(producer), consumer.dependencies);
    }

    @Test
    void ordinaryPendingReadDelaysResultPublicationUntilItsProducerCompletes() {
        AliasModel before = new AliasModel("model-1", "old", 1);
        AliasModel after = new AliasModel("model-1", "new", 2);
        Entity<AliasModel> durable = entity(before);
        Dependency producer = new Dependency();
        AtomicReference<CompletableFuture<Void>> barrier =
                new AtomicReference<>();

        DeserializingMessage.forEachInBatch(
                List.of(message("producer"), message("consumer")), current -> {
                    if (DeserializingMessage.getMessageBatchIndex() == 0) {
                        MessageBatchModelView.stage(
                                null, evaluation(current, before, after), producer);
                    } else {
                        assertEquals(after, MessageBatchModelView.overlayCurrent(
                                null, "model-1", AliasModel.class, durable).get());
                        barrier.set(Invocation.resultPublicationBarrier(current));
                        assertFalse(barrier.get().isDone());
                    }
                });

        producer.completion.complete(null);
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
                        MessageBatchModelView.stage(
                                null, evaluation(current, before, after), null);
                    } else {
                        assertSame(primary, MessageBatchModelView.overlayCurrent(
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
                        MessageBatchModelView.stage(
                                null, evaluation(current, before, after), null);
                    } else {
                        assertSame(durable, MessageBatchModelView.overlayCurrent(
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
                        MessageBatchModelView.stage(
                                "customer-a",
                                evaluation(current, before, after), null);
                    } else {
                        assertEquals(
                                after,
                                MessageBatchModelView.overlayCurrent(
                                        "customer-a", "model-1",
                                        AliasModel.class, durable).get());
                        assertSame(
                                durable,
                                MessageBatchModelView.overlayCurrent(
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
                    MessageBatchModelView.stage(
                            null,
                            evaluation(current, first.id(), null, first),
                            null);
                    MessageBatchModelView.stage(
                            null,
                            evaluation(current, updated.id(), first, updated),
                            null);
                    MessageBatchModelView.stage(
                            null,
                            evaluation(current, second.id(), null, second),
                            null);

                    assertEquals(
                            Map.of(
                                    first.id(),
                                    new MessageBatchModelView.StagedModel(
                                            first.id(), AliasModel.class,
                                            updated, false),
                                    second.id(),
                                    new MessageBatchModelView.StagedModel(
                                            second.id(), AliasModel.class,
                                            second, false)),
                            MessageBatchModelView.currentValues(null));
                });
    }

    @Test
    void indexesConcreteModelMetadataWhenAnApplyTargetsAnInterface() {
        PolymorphicAliasModel before =
                new PolymorphicAliasModel("poly-1", "old");
        PolymorphicAliasModel after =
                new PolymorphicAliasModel("poly-1", "new");

        DeserializingMessage.forEachInBatch(
                List.of(message("producer"), message("consumer")), current -> {
                    if (DeserializingMessage.getMessageBatchIndex() == 0) {
                        MessageBatchModelView.stage(
                                null,
                                new ModelCommitEngine.CommitEvaluation(
                                        0L,
                                        List.of(before.id()),
                                        Map.of(before.id(), ModelContract.class),
                                        List.of(new ModelCommitEngine.AppliedSubstep(
                                                current,
                                                List.of(new ModelCommitEngine.Transition(
                                                        before.id(), ModelContract.class,
                                                        0L, before, after, null)))),
                                        Map.of(before.id(), after)),
                                null);
                    } else {
                        Entity<Object> empty = ImmutableModelRoot.builder()
                                .id("new")
                                .type(Object.class)
                                .idProperty("id")
                                .build();
                        assertEquals(
                                after,
                                MessageBatchModelView.overlayCurrent(
                                        null, "new", Object.class,
                                        empty).get());
                        assertEquals(
                                PolymorphicAliasModel.class,
                                MessageBatchModelView.currentValue(
                                        null, before.id()).modelType());
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
                        MessageBatchModelView.stage(
                                null,
                                evaluation(current, first.id(), null, first),
                                null);
                    } else if (index == 1) {
                        MessageBatchModelView.stage(
                                null,
                                evaluation(current, second.id(), null, second),
                                null);
                    } else {
                        assertEquals(
                                List.of(first.id(), second.id()),
                                new ArrayList<>(
                                        MessageBatchModelView.currentValues(null)
                                                .keySet()));
                    }
                });
    }

    private static ModelCommitEngine.CommitEvaluation evaluation(
            DeserializingMessage message,
            AliasModel before,
            AliasModel after) {
        return evaluation(message, before.id(), before, after);
    }

    private static ModelCommitEngine.CommitEvaluation evaluation(
            DeserializingMessage message,
            String modelId,
            AliasModel before,
            AliasModel after) {
        return new ModelCommitEngine.CommitEvaluation(
                0L,
                List.of(modelId),
                Map.of(modelId, AliasModel.class),
                List.of(new ModelCommitEngine.AppliedSubstep(
                        message,
                        List.of(new ModelCommitEngine.Transition(
                                modelId, AliasModel.class,
                                0L, before, after, null)))),
                Collections.singletonMap(modelId, after));
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

    private static final class Dependency
            implements MessageBatchModelView.Dependency {
        private final List<MessageBatchModelView.Dependency> dependencies =
                new java.util.ArrayList<>();
        private final CompletableFuture<Void> completion =
                new CompletableFuture<>();

        @Override
        public void dependsOn(MessageBatchModelView.Dependency producer) {
            dependencies.add(producer);
        }

        @Override
        public CompletableFuture<?> completion() {
            return completion;
        }
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
