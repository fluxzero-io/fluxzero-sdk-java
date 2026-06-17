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
 *
 */

package io.fluxzero.sdk.modeling;

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.Invocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static io.fluxzero.sdk.modeling.AggregateCommitPolicy.AWAIT_AFTER_HANDLER_COMMITS_BEFORE_RESULTS_PROPERTY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregateCommitPolicyTest {

    private static final JacksonSerializer serializer = new JacksonSerializer();
    private static final EntityHelper entityHelper = new DefaultEntityHelper(List.of(), true);

    @Test
    void asyncAfterHandlerAwaitAfterBatchStartsAfterHandlerAndAwaitsAfterBatch() {
        AggregateCommitPolicy policy = AggregateCommitPolicy.ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH;

        assertFalse(policy.commitAfterBatch());
        assertFalse(policy.afterBatch());
        assertTrue(policy.awaitAfterBatch());
        assertTrue(policy.async());
    }

    @Test
    void typedLoadDoesNotReuseActiveAggregateWithDifferentType() {
        CommitProbe commits = new CommitProbe(true);
        AtomicBoolean integerAggregateLoaded = new AtomicBoolean();

        DeserializingMessage.forEachInBatch(List.of(message("type-mismatch")),
                                            message -> Invocation.performInvocation(
                                                    () -> loadDifferentTypedAggregateWithActiveSameId(
                                                            commits, integerAggregateLoaded)));
    }

    @Test
    void afterHandlerPolicyStartsCommitBeforeBatchCompletes() {
        CommitProbe commits = new CommitProbe(true);
        Entity<String> aggregate = aggregate("handler", AggregateCommitPolicy.ASYNC_AFTER_HANDLER, commits);

        DeserializingMessage.forEachInBatch(List.of(message("one"), message("two")), message -> {
            if ("one".equals(message.getMessageId())) {
                Invocation.performInvocation(() -> aggregate.update(value -> "after-handler"));
                assertTrue(commits.started(1));
            } else {
                assertTrue(commits.started(1));
            }
        });

        commits.completeAll();
    }

    @Test
    void afterBatchPolicyDefersCommitUntilBatchCompletes() {
        CommitProbe commits = new CommitProbe(true);
        Entity<String> aggregate = aggregate("batch", AggregateCommitPolicy.ASYNC_AFTER_BATCH, commits);

        DeserializingMessage.forEachInBatch(List.of(message("one"), message("two")), message -> {
            if ("one".equals(message.getMessageId())) {
                Invocation.performInvocation(() -> aggregate.update(value -> "after-batch"));
                assertFalse(commits.started(1));
            } else {
                assertFalse(commits.started(1));
            }
        });

        assertTrue(commits.started(1));
        commits.completeAll();
    }

    @Test
    void syncAfterHandlerWaitsForEachCommitBeforeStartingTheNext() throws Exception {
        CommitProbe commits = new CommitProbe();
        Entity<String> first = aggregate("sync-handler-1", AggregateCommitPolicy.SYNC_AFTER_HANDLER, commits);
        Entity<String> second = aggregate("sync-handler-2", AggregateCommitPolicy.SYNC_AFTER_HANDLER, commits);

        CompletableFuture<Void> handler = CompletableFuture.runAsync(() -> Invocation.performInvocation(() -> {
            first.update(value -> "first");
            second.update(value -> "second");
            return null;
        }));

        assertTrue(commits.awaitStarted(1));
        assertFalse(commits.started(2));
        assertFalse(handler.isDone());

        commits.complete(0);
        assertTrue(commits.awaitStarted(2));
        assertFalse(handler.isDone());

        commits.complete(1);
        assertDoesNotThrow(() -> handler.get(1, TimeUnit.SECONDS));
    }

    @Test
    void asyncAfterHandlerStartsAllCommitsBeforeWaiting() throws Exception {
        CommitProbe commits = new CommitProbe();
        Entity<String> first = aggregate("async-handler-1", AggregateCommitPolicy.ASYNC_AFTER_HANDLER, commits);
        Entity<String> second = aggregate("async-handler-2", AggregateCommitPolicy.ASYNC_AFTER_HANDLER, commits);

        CompletableFuture<Void> handler = CompletableFuture.runAsync(() -> Invocation.performInvocation(() -> {
            first.update(value -> "first");
            second.update(value -> "second");
            return null;
        }));

        assertTrue(commits.awaitStarted(2));
        assertFalse(handler.isDone());

        commits.completeAll();
        assertDoesNotThrow(() -> handler.get(1, TimeUnit.SECONDS));
    }

    @Test
    void asyncAfterHandlerAwaitAfterBatchStartsCommitAfterHandlerAndWaitsForBatchCompletion() throws Exception {
        CommitProbe commits = new CommitProbe();
        Entity<String> aggregate = aggregate(
                "async-handler-await-batch", AggregateCommitPolicy.ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH, commits);
        AtomicBoolean reachedNextMessage = new AtomicBoolean();

        CompletableFuture<Void> batch = CompletableFuture.runAsync(() -> DeserializingMessage.forEachInBatch(
                List.of(message("one"), message("two")), message -> {
                    if ("one".equals(message.getMessageId())) {
                        Invocation.performInvocation(() -> aggregate.update(value -> "first"));
                    } else {
                        reachedNextMessage.set(true);
                    }
                }));

        assertTrue(commits.awaitStarted(1));
        assertTrue(await(reachedNextMessage::get));
        assertFalse(batch.isDone());

        commits.completeAll();
        assertDoesNotThrow(() -> batch.get(1, TimeUnit.SECONDS));
    }

    @Test
    void asyncAfterHandlerAwaitAfterBatchRegistersPostHandlerCompletion() throws Exception {
        CommitProbe commits = new CommitProbe();
        Entity<String> aggregate = aggregate(
                "async-handler-await-batch-result",
                AggregateCommitPolicy.ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH, commits);
        AtomicReference<CompletableFuture<Void>> postHandlerCompletion = new AtomicReference<>();

        CompletableFuture<Void> batch = CompletableFuture.runAsync(() -> DeserializingMessage.forEachInBatch(
                List.of(message("one")), message -> {
                    Invocation.performInvocation(() -> aggregate.update(value -> "first"));
                    postHandlerCompletion.set(Invocation.resultPublicationBarrier(message));
                }));

        assertTrue(commits.awaitStarted(1));
        assertTrue(await(() -> postHandlerCompletion.get() != null));
        assertFalse(postHandlerCompletion.get().isDone());
        assertFalse(batch.isDone());

        commits.completeAll();

        assertDoesNotThrow(() -> batch.get(1, TimeUnit.SECONDS));
        assertTrue(postHandlerCompletion.get().isDone());
    }

    @Test
    void asyncAfterHandlerAwaitAfterBatchSkipsResultBarrierWhenDisabled() throws Exception {
        CommitProbe commits = new CommitProbe();
        AtomicReference<CompletableFuture<Void>> postHandlerCompletion = new AtomicReference<>();

        TestFixture.create()
                .withProperty(AWAIT_AFTER_HANDLER_COMMITS_BEFORE_RESULTS_PROPERTY, "false")
                .whenApplying(fc -> {
                    Entity<String> aggregate = aggregate(
                            "async-handler-await-batch-result-disabled",
                            AggregateCommitPolicy.ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH, commits);
                    CompletableFuture<Void> batch = CompletableFuture.runAsync(() -> DeserializingMessage.forEachInBatch(
                            List.of(message("one")), message -> {
                                Invocation.performInvocation(() -> aggregate.update(value -> "first"));
                                postHandlerCompletion.set(Invocation.resultPublicationBarrier(message));
                            }));

                    assertTrue(commits.awaitStarted(1));
                    assertTrue(await(() -> postHandlerCompletion.get() != null));
                    assertTrue(postHandlerCompletion.get().isDone());
                    assertFalse(batch.isDone());

                    commits.completeAll();

                    assertDoesNotThrow(() -> batch.get(1, TimeUnit.SECONDS));
                    return null;
                })
                .expectNoResult();
    }

    @Test
    void asyncAfterHandlerAwaitAfterBatchKeepsAggregateActiveUntilBatchCompletion() throws Exception {
        CommitProbe commits = new CommitProbe();
        AtomicInteger loads = new AtomicInteger();
        AtomicBoolean activeAfterBatch = new AtomicBoolean(true);
        Supplier<Entity<String>> loader = () -> {
            loads.incrementAndGet();
            return immutableAggregate("active-for-batch", "before");
        };

        CompletableFuture<Void> batch = CompletableFuture.runAsync(() -> {
            DeserializingMessage.forEachInBatch(List.of(message("one"), message("two")), message -> {
                Invocation.performInvocation(() -> {
                    Entity<String> aggregate = ModifiableAggregateRoot.load(
                            "active-for-batch", loader,
                            AggregateCommitPolicy.ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH,
                            EventPublication.DEFAULT, EventPublicationStrategy.DEFAULT,
                            AggregateEventRouting.MESSAGE_ROUTING_KEY, true, entityHelper, serializer,
                            DispatchInterceptor.noOp, commits::commit);
                    if ("one".equals(message.getMessageId())) {
                        aggregate.update(value -> "first");
                    } else {
                        assertEquals("first", aggregate.get());
                        aggregate.update(value -> "second");
                    }
                    return null;
                });
            });
            activeAfterBatch.set(ModifiableAggregateRoot.getIfActive("active-for-batch").isPresent());
        });

        assertTrue(commits.awaitStarted(2));
        assertEquals(1, loads.get());
        assertFalse(batch.isDone());

        commits.completeAll();
        assertDoesNotThrow(() -> batch.get(1, TimeUnit.SECONDS));
        assertFalse(activeAfterBatch.get());
    }

    @Test
    void asyncAfterHandlerAwaitAfterBatchPropagatesCommitFailureAtBatchCompletion() throws Exception {
        CommitProbe commits = new CommitProbe();
        Entity<String> aggregate = aggregate(
                "async-handler-await-batch-failure",
                AggregateCommitPolicy.ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH, commits);
        AtomicBoolean handlerCompleted = new AtomicBoolean();
        RuntimeException failure = new RuntimeException("commit failed");

        CompletableFuture<Void> batch = CompletableFuture.runAsync(() -> DeserializingMessage.forEachInBatch(
                List.of(message("one")), message -> {
                    Invocation.performInvocation(() -> aggregate.update(value -> "first"));
                    handlerCompleted.set(true);
                }));

        assertTrue(commits.awaitStarted(1));
        assertTrue(await(handlerCompleted::get));
        assertFalse(batch.isDone());

        commits.fail(0, failure);
        ExecutionException error = assertThrows(ExecutionException.class, () -> batch.get(1, TimeUnit.SECONDS));
        assertTrue(hasCause(error, failure));
    }

    @Test
    void syncAfterBatchWaitsForEachCommitBeforeStartingTheNext() throws Exception {
        CommitProbe commits = new CommitProbe();
        Entity<String> first = aggregate("sync-batch-1", AggregateCommitPolicy.SYNC_AFTER_BATCH, commits);
        Entity<String> second = aggregate("sync-batch-2", AggregateCommitPolicy.SYNC_AFTER_BATCH, commits);

        CompletableFuture<Void> batch = CompletableFuture.runAsync(() -> DeserializingMessage.forEachInBatch(
                List.of(message("one")), message -> Invocation.performInvocation(() -> {
                    first.update(value -> "first");
                    second.update(value -> "second");
                    return null;
                })));

        assertTrue(commits.awaitStarted(1));
        assertFalse(commits.started(2));
        assertFalse(batch.isDone());

        commits.complete(0);
        assertTrue(commits.awaitStarted(2));
        assertFalse(batch.isDone());

        commits.complete(1);
        assertDoesNotThrow(() -> batch.get(1, TimeUnit.SECONDS));
    }

    @Test
    void asyncAfterBatchStartsAllCommitsBeforeWaiting() throws Exception {
        CommitProbe commits = new CommitProbe();
        Entity<String> first = aggregate("async-batch-1", AggregateCommitPolicy.ASYNC_AFTER_BATCH, commits);
        Entity<String> second = aggregate("async-batch-2", AggregateCommitPolicy.ASYNC_AFTER_BATCH, commits);

        CompletableFuture<Void> batch = CompletableFuture.runAsync(() -> DeserializingMessage.forEachInBatch(
                List.of(message("one")), message -> Invocation.performInvocation(() -> {
                    first.update(value -> "first");
                    second.update(value -> "second");
                    return null;
                })));

        assertTrue(commits.awaitStarted(2));
        assertFalse(batch.isDone());

        commits.completeAll();
        assertDoesNotThrow(() -> batch.get(1, TimeUnit.SECONDS));
    }

    private static Entity<String> aggregate(String id, AggregateCommitPolicy policy, CommitProbe commits) {
        Entity<String> delegate = immutableAggregate(id, "before");
        return new ModifiableAggregateRoot<>(
                delegate, policy, EventPublication.DEFAULT, EventPublicationStrategy.DEFAULT,
                AggregateEventRouting.MESSAGE_ROUTING_KEY, true, entityHelper, serializer,
                DispatchInterceptor.noOp, commits::commit);
    }

    private static Void loadDifferentTypedAggregateWithActiveSameId(
            CommitProbe commits, AtomicBoolean integerAggregateLoaded) {
        Entity<String> stringAggregate = ModifiableAggregateRoot.load(
                "same-id", String.class, () -> immutableAggregate("same-id", "before"),
                AggregateCommitPolicy.SYNC_AFTER_HANDLER, EventPublication.DEFAULT, EventPublicationStrategy.DEFAULT,
                AggregateEventRouting.MESSAGE_ROUTING_KEY, true, entityHelper, serializer, DispatchInterceptor.noOp,
                commits::commit);
        stringAggregate.update(value -> "after");

        Entity<Integer> integerAggregate = ModifiableAggregateRoot.load(
                "same-id", Integer.class, () -> {
                    integerAggregateLoaded.set(true);
                    return ImmutableAggregateRoot.<Integer>builder()
                            .id("same-id")
                            .type(Integer.class)
                            .value(1)
                            .entityHelper(entityHelper)
                            .serializer(serializer)
                            .build();
                }, AggregateCommitPolicy.SYNC_AFTER_HANDLER, EventPublication.DEFAULT,
                EventPublicationStrategy.DEFAULT, AggregateEventRouting.MESSAGE_ROUTING_KEY, true,
                entityHelper, serializer, DispatchInterceptor.noOp, commits::commit);

        assertTrue(integerAggregateLoaded.get());
        assertEquals(Integer.class, integerAggregate.type());
        assertEquals(1, integerAggregate.get());
        return null;
    }

    private static Entity<String> immutableAggregate(String id, String value) {
        return ImmutableAggregateRoot.<String>builder()
                .id(id)
                .type(String.class)
                .value(value)
                .entityHelper(entityHelper)
                .serializer(serializer)
                .build();
    }

    private static DeserializingMessage message(String messageId) {
        SerializedMessage message = new SerializedMessage(
                serializer.serialize("payload"), Metadata.empty(), messageId, System.currentTimeMillis());
        return new DeserializingMessage(message, type -> "payload", MessageType.COMMAND, null, serializer);
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5L);
        }
        return condition.getAsBoolean();
    }

    private static boolean hasCause(Throwable error, Throwable cause) {
        Throwable current = error;
        while (current != null) {
            if (current == cause) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static class CommitProbe {
        private final boolean completeImmediately;
        private final AtomicInteger started = new AtomicInteger();
        private final List<CompletableFuture<Void>> completions =
                List.of(new CompletableFuture<>(), new CompletableFuture<>());

        CommitProbe() {
            this(false);
        }

        CommitProbe(boolean completeImmediately) {
            this.completeImmediately = completeImmediately;
        }

        CompletableFuture<Void> commit(Entity<?> after, List<AppliedEvent> unpublished, Entity<?> before) {
            int index = started.getAndIncrement();
            if (completeImmediately) {
                return CompletableFuture.completedFuture(null);
            }
            return completions.get(index);
        }

        boolean started(int count) {
            return started.get() >= count;
        }

        boolean awaitStarted(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (started.get() < count && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(5L);
            }
            return started.get() >= count;
        }

        void complete(int index) {
            completions.get(index).complete(null);
        }

        void completeAll() {
            completions.forEach(completion -> completion.complete(null));
        }

        void fail(int index, Throwable error) {
            completions.get(index).completeExceptionally(error);
        }
    }
}
