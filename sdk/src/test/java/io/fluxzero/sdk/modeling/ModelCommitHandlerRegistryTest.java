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

import io.fluxzero.common.MessageType;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.ModelGraphProjectionStatus;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.handling.HandlerDecorator;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.Tracker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCommitHandlerRegistryTest {

    @Test
    void receiverApplyTracksTheCommandPayloadRatherThanTheModel() {
        ModelCommitHandlerRegistry subject =
                subject(AutomaticModelHandling.ENABLED);
        subject.registerHandler(
                ReceiverModel.class,
                HandlerFilter.ALWAYS_HANDLE);

        assertEquals(
                List.of(ReceiverCommand.class),
                subject.trackingTargets(
                        ReceiverModel.class,
                        HandlerFilter.ALWAYS_HANDLE));
        assertTrue(subject.createHandler(
                ReceiverCommand.class,
                HandlerFilter.ALWAYS_HANDLE,
                List.of()).isPresent());
        assertTrue(subject.createHandler(
                ReceiverModel.class,
                HandlerFilter.ALWAYS_HANDLE,
                List.of()).isEmpty());
    }

    @Test
    void staticModelApplyTracksTheCommandPayloadRatherThanTheModel() {
        ModelCommitHandlerRegistry subject =
                subject(AutomaticModelHandling.ENABLED);
        subject.registerHandler(
                StaticApplyModel.class,
                HandlerFilter.ALWAYS_HANDLE);

        assertEquals(
                List.of(StaticCreateCommand.class),
                subject.trackingTargets(
                        StaticApplyModel.class,
                        HandlerFilter.ALWAYS_HANDLE));
        assertTrue(subject.createHandler(
                StaticCreateCommand.class,
                HandlerFilter.ALWAYS_HANDLE,
                List.of()).isPresent());
        assertTrue(subject.canHandle(
                message(new StaticCreateCommand("created"))));
    }

    @Test
    void automaticHandlingUsesApplyThenModelThenApplicationPrecedence() {
        ModelCommitHandlerRegistry disabledApplication =
                subject(AutomaticModelHandling.DISABLED);
        disabledApplication.registerHandler(
                ReceiverModel.class,
                HandlerFilter.ALWAYS_HANDLE);
        disabledApplication.registerHandler(
                ExplicitlyEnabledModel.class,
                HandlerFilter.ALWAYS_HANDLE);
        disabledApplication.registerHandler(
                ApplyEnabledModel.class,
                HandlerFilter.ALWAYS_HANDLE);

        assertFalse(disabledApplication.canHandle(
                message(new ReceiverCommand("default"))));
        assertTrue(disabledApplication.canHandle(
                message(new ExplicitlyEnabledCommand("model"))));
        assertTrue(disabledApplication.canHandle(
                message(new ApplyEnabledCommand("apply"))));

        ModelCommitHandlerRegistry enabledApplication =
                subject(AutomaticModelHandling.ENABLED);
        enabledApplication.registerHandler(
                ApplyDisabledModel.class,
                HandlerFilter.ALWAYS_HANDLE);
        assertFalse(enabledApplication.canHandle(
                message(new ApplyDisabledCommand("disabled"))));
    }

    @Test
    void oneDisabledApplyDeclinesTheCompleteMultiModelCommand() {
        ModelCommitHandlerRegistry subject =
                subject(AutomaticModelHandling.ENABLED);
        subject.registerHandler(
                MixedEnabledModel.class,
                HandlerFilter.ALWAYS_HANDLE);
        subject.registerHandler(
                MixedDisabledModel.class,
                HandlerFilter.ALWAYS_HANDLE);

        assertFalse(subject.canHandle(
                message(new MixedCommand("enabled", "disabled"))));
    }

    @Test
    void defaultModelPolicyStartsAfterHandlerAndAwaitsAtBatchCompletion() {
        ModelCommitHandlerRegistry subject =
                subject(AutomaticModelHandling.ENABLED);
        subject.registerHandler(
                StaticApplyModel.class,
                HandlerFilter.ALWAYS_HANDLE);

        assertEquals(
                ModelCommitPolicy.ASYNC_AFTER_HANDLER_AWAIT_AFTER_BATCH,
                subject.commitPolicyFor(StaticCreateCommand.class));
    }

    @Test
    void atomicMultiModelCommitUsesTheEarliestStartAndStrongestHandlerGuarantee() {
        ModelCommitHandlerRegistry subject =
                subject(AutomaticModelHandling.ENABLED);
        subject.registerHandler(
                HandlerPolicyModel.class,
                HandlerFilter.ALWAYS_HANDLE);
        subject.registerHandler(
                BatchPolicyModel.class,
                HandlerFilter.ALWAYS_HANDLE);

        assertEquals(
                ModelCommitPolicy.SYNC_AFTER_HANDLER,
                subject.commitPolicyFor(MixedPolicyCommand.class));
    }

    @Test
    void directSingleTargetUsesProvenCurrentCacheWithoutGenericContextLoad() {
        DefaultModelRepository repository = mock(DefaultModelRepository.class);
        ImmutableModelRoot<ReceiverModel> cached = ImmutableModelRoot.<ReceiverModel>builder()
                .id("cached")
                .type(ReceiverModel.class)
                .idProperty("id")
                .value(new ReceiverModel("cached"))
                .sequenceNumber(3L)
                .stateIndex(7L)
                .build();
        when(repository.supplyCurrentModel(any(), any(), any()))
                .thenAnswer(invocation -> {
                    assertEquals("cached", invocation.getArgument(0));
                    assertEquals(ReceiverModel.class, invocation.getArgument(1));
                    DefaultModelRepository.CurrentModelSink sink = invocation.getArgument(2);
                    sink.accept(cached, 9L, 7L);
                    return true;
                });
        when(repository.beginLocalCommit(any())).thenReturn(() -> {
        });
        CompletableFuture<List<DefaultModelRepository.CommittedModel>> updatedModels =
                new CompletableFuture<>();
        doAnswer(invocation -> {
            updatedModels.complete(invocation.getArgument(0));
            return null;
        }).when(repository).updateAfterCommit(any());
        EventStoreClient eventStoreClient = mock(EventStoreClient.class);
        CompletableFuture<String> committedEventId = new CompletableFuture<>();
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation -> {
            CommitModels commit = invocation.getArgument(0);
            committedEventId.complete(commit.getSubsteps().getFirst().getEvent().getMessageId());
            return CompletableFuture.completedFuture(
                    CommitModelsResult.acceptedSingleTarget(
                            commit.getRequestId(), commit.getCommitId(),
                            10L, 4L, "cached", 1L, true));
        });
        ModelCommitHandlerRegistry subject = subject(repository, eventStoreClient);
        subject.registerHandler(ReceiverModel.class, HandlerFilter.ALWAYS_HANDLE);

        try {
            subject.handle(message(new ReceiverCommand("cached")))
                    .orElseThrow()
                    .join();

            verify(repository, times(0)).loadContext(
                    any(ModelTargetResolver.Resolution.class),
                    nullable(Long.class), anyMap());
            assertEquals(
                    committedEventId.join(),
                    updatedModels.join().getFirst().revisions().getFirst().lastEventId());
        } finally {
            subject.close();
        }
    }

    @Test
    void defaultPolicyStartsCommitBeforeBatchCompletionAndBatchAwaitsIt()
            throws Exception {
        DefaultModelRepository repository = mock(DefaultModelRepository.class);
        stubModelLoads(repository);
        EventStoreClient eventStoreClient = mock(EventStoreClient.class);
        CompletableFuture<CommitModels> commitStarted = new CompletableFuture<>();
        CompletableFuture<CommitModelsResult> commitResponse = new CompletableFuture<>();
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation -> {
            CommitModels commit = invocation.getArgument(0);
            commitStarted.complete(commit);
            return commitResponse;
        });
        ModelCommitHandlerRegistry subject = subject(repository, eventStoreClient);
        CompletableFuture<CompletableFuture<Object>> handlingStarted = new CompletableFuture<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            CompletableFuture<Void> batch = CompletableFuture.runAsync(() ->
                    DeserializingMessage.forEachInBatch(
                            List.of(message(new TimingCreateCommand("timed"))),
                            current -> handlingStarted.complete(
                                    subject.handle(current).orElseThrow())), executor);

            CompletableFuture.anyOf(commitStarted, batch).get(5, TimeUnit.SECONDS);
            if (!commitStarted.isDone()) {
                batch.join();
            }
            CommitModels commit = commitStarted.join();
            CompletableFuture<Object> handlingResult = handlingStarted.get(5, TimeUnit.SECONDS);
            assertFalse(batch.isDone());
            assertFalse(handlingResult.isDone());

            commitResponse.complete(CommitModelsResult.acceptedSingleTarget(
                    commit.getRequestId(), commit.getCommitId(), 1L, 1L,
                    "timed", 0L, true));
            batch.get(5, TimeUnit.SECONDS);
            handlingResult.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            subject.close();
        }
    }

    @Test
    void explicitAfterBatchPolicyDefersCommitUntilBatchCompletion()
            throws Exception {
        DefaultModelRepository repository = mock(DefaultModelRepository.class);
        stubModelLoads(repository);
        EventStoreClient eventStoreClient = mock(EventStoreClient.class);
        CompletableFuture<CommitModels> commitStarted = new CompletableFuture<>();
        CompletableFuture<CommitModelsResult> commitResponse = new CompletableFuture<>();
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation -> {
            CommitModels commit = invocation.getArgument(0);
            commitStarted.complete(commit);
            return commitResponse;
        });
        ModelCommitHandlerRegistry subject = subject(repository, eventStoreClient);
        CompletableFuture<CompletableFuture<Object>> handlingStarted = new CompletableFuture<>();
        CountDownLatch finishHandlerIteration = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            CompletableFuture<Void> batch = CompletableFuture.runAsync(() ->
                    DeserializingMessage.forEachInBatch(
                            List.of(message(new BatchTimingCreateCommand("deferred"))),
                            current -> {
                                handlingStarted.complete(subject.handle(current).orElseThrow());
                                try {
                                    finishHandlerIteration.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(e);
                                }
                            }), executor);

            CompletableFuture<Object> handlingResult = handlingStarted.get(5, TimeUnit.SECONDS);
            assertFalse(commitStarted.isDone());
            assertFalse(handlingResult.isDone());

            finishHandlerIteration.countDown();
            CommitModels commit = commitStarted.get(5, TimeUnit.SECONDS);
            assertFalse(batch.isDone());
            commitResponse.complete(CommitModelsResult.acceptedSingleTarget(
                    commit.getRequestId(), commit.getCommitId(), 1L, 1L,
                    "deferred", 0L, true));
            batch.get(5, TimeUnit.SECONDS);
            handlingResult.get(5, TimeUnit.SECONDS);
        } finally {
            finishHandlerIteration.countDown();
            executor.shutdownNow();
            subject.close();
        }
    }

    @Test
    void synchronousAfterBatchPolicyCommitsSequentially()
            throws Exception {
        DefaultModelRepository repository = mock(DefaultModelRepository.class);
        stubModelLoads(repository);
        EventStoreClient eventStoreClient = mock(EventStoreClient.class);
        LinkedBlockingQueue<PendingResponse> started = new LinkedBlockingQueue<>();
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation -> {
            PendingResponse response = new PendingResponse(invocation.getArgument(0));
            started.add(response);
            return response.result();
        });
        ModelCommitHandlerRegistry subject = subject(repository, eventStoreClient);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            CompletableFuture<Void> batch = CompletableFuture.runAsync(() ->
                    DeserializingMessage.forEachInBatch(
                            List.of(
                                    message(new SyncBatchTimingCreateCommand("first")),
                                    message(new SyncBatchTimingCreateCommand("second"))),
                            current -> subject.handle(current).orElseThrow()), executor);

            PendingResponse first = started.poll(5, TimeUnit.SECONDS);
            assertTrue(first != null);
            assertTrue(started.isEmpty());
            first.accept();

            PendingResponse second = started.poll(5, TimeUnit.SECONDS);
            assertTrue(second != null);
            second.accept();
            batch.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            subject.close();
        }
    }

    @Test
    void graphProjectionCompletionUsesApplyThenRootThenApplicationPrecedence()
            throws Exception {
        ModelCommitEngine.Transition inherited =
                transition(
                        ProjectionChild.class,
                        ProjectionApplies.class
                                .getDeclaredMethod(
                                        "inherit"));
        ModelCommitEngine.Transition asynchronous =
                transition(
                        ProjectionChild.class,
                        ProjectionApplies.class
                                .getDeclaredMethod(
                                        "asynchronous"));

        assertEquals(
                java.util.Set.of("awaited-graphs"),
                subject(
                        AutomaticModelHandling.ENABLED,
                        GraphProjectionCompletion.ASYNC)
                        .awaitedGraphProjections(
                                evaluation(inherited)));
        assertTrue(
                subject(
                        AutomaticModelHandling.ENABLED,
                        GraphProjectionCompletion.AWAIT)
                        .awaitedGraphProjections(
                                evaluation(asynchronous))
                        .isEmpty());
        assertEquals(
                java.util.Set.of("default-graphs"),
                subject(
                        AutomaticModelHandling.ENABLED,
                        GraphProjectionCompletion.AWAIT)
                        .awaitedGraphProjections(
                                evaluation(
                                        transition(
                                                DefaultProjectionRoot.class,
                                                ProjectionApplies.class
                                                        .getDeclaredMethod(
                                                                "inherit")))));
    }

    @Test
    void awaitDominatesAsyncForTheSameAffectedRoot()
            throws Exception {
        ModelCommitHandlerRegistry subject =
                subject(
                        AutomaticModelHandling.ENABLED,
                        GraphProjectionCompletion.ASYNC);
        ModelCommitEngine.Transition asynchronous =
                transition(
                        "shared-child",
                        ProjectionChild.class,
                        ProjectionApplies.class
                                .getDeclaredMethod(
                                        "asynchronous"));
        ModelCommitEngine.Transition awaiting =
                transition(
                        "shared-child",
                        ProjectionChild.class,
                        ProjectionApplies.class
                                .getDeclaredMethod(
                                        "awaiting"));

        assertEquals(
                Map.of(
                        "awaited-graphs",
                        java.util.Set.of(
                                "shared-child")),
                subject.awaitedGraphProjectionTargets(
                        evaluation(
                                asynchronous,
                                awaiting)));
    }

    @Test
    void activeConsumerPrecedesRootAndApplicationCompletion()
            throws Exception {
        Tracker.current.set(
                new Tracker(
                        "tracker",
                        MessageType.COMMAND,
                        null,
                        ConsumerConfiguration.builder()
                                .name("consumer")
                                .graphProjectionCompletion(
                                        GraphProjectionCompletion.AWAIT)
                                .build(),
                        null));
        try {
            ModelCommitHandlerRegistry subject =
                    subject(
                            AutomaticModelHandling.ENABLED,
                            GraphProjectionCompletion.ASYNC);
            assertEquals(
                    java.util.Set.of("async-graphs"),
                    subject.awaitedGraphProjections(
                            evaluation(
                                    transition(
                                            AsyncProjectionRoot.class,
                                            ProjectionApplies.class
                                                    .getDeclaredMethod(
                                                            "inherit")))));
            assertTrue(
                    subject.awaitedGraphProjections(
                            evaluation(
                                    transition(
                                            AsyncProjectionRoot.class,
                                            ProjectionApplies.class
                                                    .getDeclaredMethod(
                                                            "asynchronous"))))
                            .isEmpty());
        } finally {
            Tracker.current.remove();
        }
    }

    @Test
    void retriesAutomaticGraphProjectionRegistrationAfterTransientFailure() {
        EventStoreClient eventStoreClient =
                mock(EventStoreClient.class);
        when(eventStoreClient.registerModelGraphProjection(
                any())).thenReturn(
                CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "runtime temporarily unavailable")),
                CompletableFuture.completedFuture(
                        new ModelGraphProjectionStatus(
                                0L, "retryRoots",
                                -1L, -1L,
                                0L, 0L, false)));
        JacksonSerializer serializer =
                new JacksonSerializer();
        ModelCommitHandlerRegistry subject =
                new ModelCommitHandlerRegistry(
                        mock(DefaultModelRepository.class),
                        eventStoreClient,
                        serializer,
                        serializer,
                        mock(DocumentSerializer.class),
                        DispatchInterceptor.noOp,
                        "test",
                        List.of(),
                        HandlerDecorator.noOp,
                        io.fluxzero.common.api.modeling.ModelConflictPolicy.ACCEPT,
                        ModelConflictResolver.fail(),
                        0,
                        AutomaticModelHandling.ENABLED,
                        GraphProjectionCompletion.ASYNC);

        subject.registerHandler(
                RetryRoot.class,
                HandlerFilter.ALWAYS_HANDLE);
        subject.registerHandler(
                RetryRoot.class,
                HandlerFilter.ALWAYS_HANDLE);

        verify(eventStoreClient, times(2))
                .registerModelGraphProjection(
                        any());
    }

    private static ModelCommitHandlerRegistry subject(
            AutomaticModelHandling automaticHandling) {
        return subject(
                automaticHandling,
                GraphProjectionCompletion.ASYNC);
    }

    private static ModelCommitHandlerRegistry subject(
            AutomaticModelHandling automaticHandling,
            GraphProjectionCompletion graphProjectionCompletion) {
        JacksonSerializer serializer =
                new JacksonSerializer();
        return subject(
                mock(DefaultModelRepository.class),
                mock(EventStoreClient.class),
                serializer,
                automaticHandling,
                graphProjectionCompletion);
    }

    private static ModelCommitHandlerRegistry subject(
            DefaultModelRepository repository,
            EventStoreClient eventStoreClient) {
        return subject(
                repository, eventStoreClient,
                new JacksonSerializer(),
                AutomaticModelHandling.ENABLED,
                GraphProjectionCompletion.ASYNC);
    }

    private static ModelCommitHandlerRegistry subject(
            DefaultModelRepository repository,
            EventStoreClient eventStoreClient,
            JacksonSerializer serializer,
            AutomaticModelHandling automaticHandling,
            GraphProjectionCompletion graphProjectionCompletion) {
        return new ModelCommitHandlerRegistry(
                repository,
                eventStoreClient,
                serializer,
                serializer,
                mock(DocumentSerializer.class),
                DispatchInterceptor.noOp,
                "test",
                List.of(),
                HandlerDecorator.noOp,
                io.fluxzero.common.api.modeling.ModelConflictPolicy.ACCEPT,
                ModelConflictResolver.fail(),
                0,
                automaticHandling,
                graphProjectionCompletion);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void stubModelLoads(DefaultModelRepository repository) {
        when(repository.loadContext(
                any(ModelTargetResolver.Resolution.class),
                nullable(Long.class), anyMap()))
                .thenAnswer(invocation -> {
                    ModelTargetResolver.Resolution resolution = invocation.getArgument(0);
                    Map<String, Entity<?>> loaded = resolution.models().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    ModelTargetResolver.ResolvedModel::modelId,
                                    target -> ImmutableModelRoot.<Object>builder()
                                            .id(target.modelId())
                                            .type((Class<Object>) target.modelType())
                                            .idProperty(ModelMetadata.of(target.modelType())
                                                    .entityId().orElseThrow().name())
                                            .value(null)
                                            .sequenceNumber(-1L)
                                            .stateIndex(0L)
                                            .build()));
                    return ModelCommitContext.create(0L, resolution, loaded);
                });
        when(repository.beginLocalCommit(any())).thenReturn(() -> {
        });
    }

    private static DeserializingMessage message(Object payload) {
        return new DeserializingMessage(
                new Message(payload),
                MessageType.COMMAND,
                new JacksonSerializer());
    }

    private static ModelCommitEngine.CommitEvaluation evaluation(
            ModelCommitEngine.Transition... transitions) {
        return new ModelCommitEngine.CommitEvaluation(
                1L,
                java.util.Arrays.stream(transitions)
                        .map(ModelCommitEngine.Transition::modelId)
                        .toList(),
                java.util.Arrays.stream(transitions)
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        ModelCommitEngine.Transition::modelId,
                                        ModelCommitEngine.Transition::modelType,
                                        (first, second) ->
                                                first)),
                List.of(
                        new ModelCommitEngine.AppliedSubstep(
                                null,
                                List.of(transitions))),
                Map.of());
    }

    private static ModelCommitEngine.Transition transition(
            Class<?> modelType,
            java.lang.reflect.Executable handler) {
        return transition(
                modelType.getName()
                + "#"
                + handler.getName(),
                modelType, handler);
    }

    private static ModelCommitEngine.Transition transition(
            String modelId,
            Class<?> modelType,
            java.lang.reflect.Executable handler) {
        return new ModelCommitEngine.Transition(
                modelId,
                modelType, 0L, null,
                new Object(), handler);
    }

    @Model(
            searchable = true,
            graphProjection = @GraphProjection(
                    collection = "retryRoots"))
    private record RetryRoot(
            @EntityId String id) {
    }

    @Model
    private record ReceiverModel(
            @EntityId String id) {
        @Apply
        ReceiverModel apply(ReceiverCommand command) {
            return new ReceiverModel(command.id());
        }
    }

    private record ReceiverCommand(String id) {
    }

    @Model
    private record StaticApplyModel(
            @EntityId String id) {
        @Apply
        static StaticApplyModel create(
                StaticCreateCommand command) {
            return new StaticApplyModel(
                    command.id());
        }
    }

    private record StaticCreateCommand(
            String id) {
    }

    @Model
    private record TimingModel(
            @EntityId String id) {
    }

    private record TimingCreateCommand(String id) {
        @Apply
        TimingModel apply() {
            return new TimingModel(id);
        }
    }

    @Model(commitPolicy = ModelCommitPolicy.ASYNC_AFTER_BATCH)
    private record BatchTimingModel(
            @EntityId String id) {
    }

    private record BatchTimingCreateCommand(String id) {
        @Apply
        BatchTimingModel apply() {
            return new BatchTimingModel(id);
        }
    }

    @Model(commitPolicy = ModelCommitPolicy.SYNC_AFTER_BATCH)
    private record SyncBatchTimingModel(
            @EntityId String id) {
    }

    private record SyncBatchTimingCreateCommand(String id) {
        @Apply
        SyncBatchTimingModel apply() {
            return new SyncBatchTimingModel(id);
        }
    }

    private record PendingResponse(
            CommitModels commit,
            CompletableFuture<CommitModelsResult> result) {
        private PendingResponse(CommitModels commit) {
            this(commit, new CompletableFuture<>());
        }

        private void accept() {
            String modelId = commit.getSubsteps().getFirst()
                    .getTargets().getFirst().getModelId();
            result.complete(CommitModelsResult.acceptedSingleTarget(
                    commit.getRequestId(), commit.getCommitId(),
                    1L, 1L, modelId, 0L, true));
        }
    }

    @Model(automaticHandling = AutomaticModelHandling.ENABLED)
    private record ExplicitlyEnabledModel(
            @EntityId String id) {
        @Apply
        ExplicitlyEnabledModel apply(
                ExplicitlyEnabledCommand command) {
            return new ExplicitlyEnabledModel(command.id());
        }
    }

    private record ExplicitlyEnabledCommand(String id) {
    }

    @Model(automaticHandling = AutomaticModelHandling.DISABLED)
    private record ApplyEnabledModel(
            @EntityId String id) {
        @Apply(automaticHandling = AutomaticModelHandling.ENABLED)
        ApplyEnabledModel apply(
                ApplyEnabledCommand command) {
            return new ApplyEnabledModel(command.id());
        }
    }

    private record ApplyEnabledCommand(String id) {
    }

    @Model(automaticHandling = AutomaticModelHandling.ENABLED)
    private record ApplyDisabledModel(
            @EntityId String id) {
        @Apply(automaticHandling = AutomaticModelHandling.DISABLED)
        ApplyDisabledModel apply(
                ApplyDisabledCommand command) {
            return new ApplyDisabledModel(command.id());
        }
    }

    private record ApplyDisabledCommand(String id) {
    }

    @Model
    private record MixedEnabledModel(
            @EntityId String id) {
        @Apply
        MixedEnabledModel apply(MixedCommand command) {
            return new MixedEnabledModel(command.enabledId());
        }
    }

    @Model
    private record MixedDisabledModel(
            @EntityId String id) {
        @Apply(automaticHandling = AutomaticModelHandling.DISABLED)
        MixedDisabledModel apply(MixedCommand command) {
            return new MixedDisabledModel(command.disabledId());
        }
    }

    private record MixedCommand(
            String enabledId,
            String disabledId) {
    }

    @Model(commitPolicy = ModelCommitPolicy.SYNC_AFTER_HANDLER)
    private record HandlerPolicyModel(
            @EntityId String id) {
        @Apply
        HandlerPolicyModel apply(MixedPolicyCommand command) {
            return new HandlerPolicyModel(command.handlerId());
        }
    }

    @Model(commitPolicy = ModelCommitPolicy.ASYNC_AFTER_BATCH)
    private record BatchPolicyModel(
            @EntityId String id) {
        @Apply
        BatchPolicyModel apply(MixedPolicyCommand command) {
            return new BatchPolicyModel(command.batchId());
        }
    }

    private record MixedPolicyCommand(
            String handlerId,
            String batchId) {
    }

    @Model(
            searchable = true,
            graphProjection = @GraphProjection(
                    collection = "awaited-graphs",
                    completion = GraphProjectionCompletion.AWAIT))
    private record ProjectionRoot(
            @EntityId ProjectionRootId id) {
    }

    private static final class ProjectionRootId
            extends Id<ProjectionRoot> {
        private ProjectionRootId(String id) {
            super(id, "projection-root-");
        }
    }

    @Model
    private record ProjectionChild(
            @EntityId String id,
            @ParentId(path = "children")
            ProjectionRootId rootId) {
    }

    @Model(
            searchable = true,
            graphProjection = @GraphProjection(
                    collection = "default-graphs"))
    private record DefaultProjectionRoot(
            @EntityId String id) {
    }

    @Model(
            searchable = true,
            graphProjection = @GraphProjection(
                    collection = "async-graphs",
                    completion = GraphProjectionCompletion.ASYNC))
    private record AsyncProjectionRoot(
            @EntityId String id) {
    }

    private static class ProjectionApplies {
        @Apply
        Object inherit() {
            return null;
        }

        @Apply(
                graphProjectionCompletion =
                        GraphProjectionCompletion.ASYNC)
        Object asynchronous() {
            return null;
        }

        @Apply(
                graphProjectionCompletion =
                        GraphProjectionCompletion.AWAIT)
        Object awaiting() {
            return null;
        }
    }
}
