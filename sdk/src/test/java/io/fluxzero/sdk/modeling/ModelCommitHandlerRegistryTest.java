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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.fluxzero.common.MessageType;
import io.fluxzero.common.Registration;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.ModelCommitConflict;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelUpdate;
import io.fluxzero.common.api.modeling.ModelUpdateKind;
import io.fluxzero.common.handling.Handler;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.common.handling.HandlerInvoker;
import io.fluxzero.common.serialization.RegisterType;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.ModelCommitBatchingClient;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository.Commit;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.handling.HandlerDecorator;
import io.fluxzero.sdk.tracking.handling.Invocation;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.Tracker;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class ModelCommitHandlerRegistryTest {
    private static final LinkedBlockingQueue<Integer> BATCH_PARENT_OBSERVATIONS =
            new LinkedBlockingQueue<>();
    private static final LinkedBlockingQueue<Integer> BATCH_INCREMENT_OBSERVATIONS =
            new LinkedBlockingQueue<>();

    @Test
    void registeredModelsContributeStructuralGraphMetadataWithoutBecomingHandlers() {
        ModelCommitHandlerRegistry subject =
                subject(AutomaticModelHandling.ENABLED);

        assertTrue(subject.knownModelTypes().contains(RegistryKnownModel.class));
        assertFalse(subject.registeredModelTypes().contains(RegistryKnownModel.class));
    }

    @Test
    void localApplyDiscoveryDoesNotTurnSenderOnlyApplicationIntoAHandler() {
        ModelCommitHandlerRegistry senderOnly =
                subject(AutomaticModelHandling.ENABLED);
        ModelCommitHandlerRegistry receiver =
                subject(AutomaticModelHandling.ENABLED);
        senderOnly.setSelfHandlerFilter((type, method) -> false);
        receiver.setSelfHandlerFilter((type, method) -> false);
        receiver.registerHandler(
                CrossApplicationModel.class,
                HandlerFilter.ALWAYS_HANDLE);
        CrossApplicationCommand command =
                new CrossApplicationCommand("shared");

        assertFalse(senderOnly.canHandle(message(command)));
        assertTrue(senderOnly.createHandler(
                CrossApplicationCommand.class,
                HandlerFilter.ALWAYS_HANDLE,
                List.of()).isEmpty());
        assertFalse(receiver.canHandle(message(command)));
        assertTrue(receiver.createHandler(
                CrossApplicationCommand.class,
                HandlerFilter.ALWAYS_HANDLE,
                List.of()).isPresent());
    }

    @Test
    void registrationInvalidatesOnlyThisApplicationsDefinitions() {
        ModelCommitHandlerRegistry subject = subject(AutomaticModelHandling.ENABLED);
        ModelCommitHandlerRegistry other = subject(AutomaticModelHandling.ENABLED);
        subject.setSelfHandlerFilter(HandlerFilter.ALWAYS_HANDLE);
        other.setSelfHandlerFilter(HandlerFilter.ALWAYS_HANDLE);
        DeserializingMessage message = message(new CrossApplicationCommand("one"));

        assertFalse(subject.canHandle(message));
        assertFalse(other.canHandle(message));
        Registration registration = subject.registerHandler(
                CrossApplicationModel.class, HandlerFilter.ALWAYS_HANDLE);
        assertTrue(subject.canHandle(message));
        assertFalse(other.canHandle(message));

        registration.cancel();
        assertFalse(subject.canHandle(message));
        assertFalse(other.canHandle(message));
    }

    @Test
    void explicitAssertAndApplyWithoutLocalApplyAssertsThenWarns() {
        DefaultModelRepository repository =
                mock(DefaultModelRepository.class);
        EventStoreClient eventStoreClient =
                mock(EventStoreClient.class);
        stubModelLoads(repository);
        ModelCommitHandlerRegistry subject =
                subject(repository, eventStoreClient);
        Logger logger = (Logger) LoggerFactory.getLogger(ModelPipeline.class);
        ListAppender<ILoggingEvent> appender =
                new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertTrue(subject.assertAndApply(new Message(
                    new CrossApplicationCommand(""))).isCompletedExceptionally());

            subject.assertAndApply(new Message(
                    new CrossApplicationCommand("valid"))).join();

            assertEquals(1, appender.list.stream()
                    .filter(event -> event.getFormattedMessage()
                            .contains(CrossApplicationCommand.class.getName()))
                    .filter(event -> event.getFormattedMessage()
                            .contains("no locally reachable model @Apply handler"))
                    .count());
            verify(eventStoreClient, never()).commitModels(any());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            subject.close();
        }
    }

    @Test
    void explicitlySuppressedUpdateDoesNotWarnAboutAMissingApply() {
        DefaultModelRepository repository = mock(DefaultModelRepository.class);
        EventStoreClient eventStoreClient = mock(EventStoreClient.class);
        stubModelLoads(repository);
        ModelCommitHandlerRegistry subject = subject(repository, eventStoreClient);
        Logger logger = (Logger) LoggerFactory.getLogger(
                ModelCommitHandlerRegistry.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            subject.assertAndApply(new Message(
                    new SuppressedCrossApplicationCommand())).join();

            assertEquals(0, appender.list.stream()
                    .filter(event -> event.getFormattedMessage()
                            .contains("no locally reachable model @Apply handler"))
                    .count());
            verify(eventStoreClient, never()).commitModels(any());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            subject.close();
        }
    }

    @Test
    void retryReevaluatesAllHandlersAtTheConflictBoundary() {
        DefaultModelRepository repository =
                mock(DefaultModelRepository.class);
        when(repository.beginLocalCommit(any()))
                .thenReturn(() -> {
                });
        when(repository.loadContext(
                any(MutationPlan.Resolution.class),
                nullable(Long.class), anyMap(), anyBoolean()))
                .thenAnswer(invocation -> {
                    MutationPlan.Resolution resolution =
                            invocation.getArgument(0);
                    Long boundary = invocation.getArgument(1);
                    RetryBoundaryModel value = boundary == null
                            ? null
                            : new RetryBoundaryModel(
                                    "retry-boundary",
                                    "winner");
                    Entity<?> entity =
                            ImmutableModelRoot
                                    .<RetryBoundaryModel>builder()
                                    .id("retry-boundary")
                                    .type(RetryBoundaryModel.class)
                                    .idProperty("id")
                                    .value(value)
                                    .sequenceNumber(-1L)
                                    .stateIndex(
                                            boundary == null
                                                    ? 5L
                                                    : boundary)
                                    .build();
                    return CommitAttempt.create(
                            boundary == null ? 5L : boundary,
                            resolution,
                            Map.of(
                                    "retry-boundary",
                                    entity));
                });
        EventStoreClient eventStoreClient =
                mock(EventStoreClient.class);
        AtomicInteger attempts =
                new AtomicInteger();
        when(eventStoreClient.commitModels(any()))
                .thenAnswer(invocation -> {
                    CommitModels request =
                            invocation.getArgument(0);
                    if (attempts.getAndIncrement() == 0) {
                        return CompletableFuture.completedFuture(
                                CommitModelsResult.conflict(
                                        request.getRequestId(),
                                        request.getCommitId(),
                                        List.of(
                                                new ModelCommitConflict(
                                                        "retry-boundary",
                                                        7L, -1L)),
                                        true));
                    }
                    return CompletableFuture.completedFuture(
                            acceptedResult(request));
                });
        JacksonSerializer serializer =
                new JacksonSerializer();
        ModelCommitHandlerRegistry subject =
                new ModelCommitHandlerRegistry(
                        repository,
                        eventStoreClient,
                        serializer,
                        serializer,
                        mock(DocumentSerializer.class),
                        DispatchInterceptor.noOp,
                        "test",
                        List.of(),
                        HandlerDecorator.noOp,
                        ModelConflictPolicy.ACCEPT,
                        ModelConflictResolver.retryIfAllowed(),
                        1,
                        AutomaticModelHandling.ENABLED,
                        GraphProjectionCompletion.ASYNC);
        RetryBoundaryCommand.observations.clear();
        try {
            subject.assertAndApply(
                    new Message(
                            new RetryBoundaryCommand(
                                    "retry-boundary")))
                    .join();

            assertEquals(
                    List.of("missing", "winner"),
                    List.copyOf(
                            RetryBoundaryCommand.observations));
            assertEquals(2, attempts.get());
            verify(repository).invalidateModels(
                    List.of("retry-boundary"));
        } finally {
            subject.close();
        }
    }

    @Test
    void explicitBulkAssertAndApplyBatchesTransportButCompletesEachDurableCommit() throws Exception {
        DefaultModelRepository repository = mock(DefaultModelRepository.class);
        stubModelLoads(repository);
        EventStoreClient eventStoreClient = mock(
                EventStoreClient.class,
                withSettings().extraInterfaces(ModelCommitBatchingClient.class));
        ModelCommitBatchingClient batchingClient =
                (ModelCommitBatchingClient) eventStoreClient;
        ModelCommitBatchingClient.ModelCommitBatch transportBatch =
                mock(ModelCommitBatchingClient.ModelCommitBatch.class, CALLS_REAL_METHODS);
        Map<CommitModels, CompletableFuture<CommitModelsResult>> responses =
                new ConcurrentHashMap<>();
        CountDownLatch prepared = new CountDownLatch(2);
        when(batchingClient.beginReadyModelCommitBatch()).thenReturn(transportBatch);
        when(transportBatch.add(anyInt(), any())).thenAnswer(invocation -> {
            CommitModels commit = invocation.getArgument(1);
            CompletableFuture<CommitModelsResult> response = new CompletableFuture<>();
            responses.put(commit, response);
            prepared.countDown();
            return response;
        });
        ModelCommitHandlerRegistry subject = subject(repository, eventStoreClient);

        try {
            CompletableFuture<Void> result = subject.assertAndApplyAll(List.of(
                    new Message(new TimingCreateCommand("bulk-a")),
                    new Message(new TimingCreateCommand("bulk-b"))));

            assertTrue(prepared.await(5, TimeUnit.SECONDS));
            verify(transportBatch, timeout(5_000).times(1)).flush();
            verify(eventStoreClient, never()).commitModels(any());
            assertFalse(result.isDone());

            responses.forEach((commit, response) -> response.complete(
                    acceptedResult(commit)));
            result.get(5, TimeUnit.SECONDS);
            verify(transportBatch, times(2)).add(anyInt(), any());
        } finally {
            subject.close();
        }
    }

    @Test
    void explicitAssertAndApplyParticipatesInTheCurrentMessageBatchView()
            throws Exception {
        DefaultModelRepository repository =
                mock(DefaultModelRepository.class);
        stubModelLoads(repository);
        EventStoreClient eventStoreClient =
                mock(EventStoreClient.class);
        CompletableFuture<CommitModels> committed =
                new CompletableFuture<>();
        CompletableFuture<CommitModelsResult> response =
                new CompletableFuture<>();
        when(eventStoreClient.commitModels(any()))
                .thenAnswer(invocation -> {
                    committed.complete(invocation.getArgument(0));
                    return response;
                });
        ModelCommitHandlerRegistry subject =
                subject(repository, eventStoreClient);
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] update =
                new CompletableFuture[1];
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] publicationBarrier =
                new CompletableFuture[1];

        try {
            DeserializingMessage.forEachInBatch(
                    List.of(
                            message("manual-producer", 7),
                            message("manual-consumer", 7)),
                    current -> {
                        if (DeserializingMessage.getMessageBatchIndex() == 0) {
                            update[0] = subject.assertAndApply(
                                    new Message(
                                            new TimingCreateCommand(
                                                    "manual-batch")));
                            return;
                        }
                        Entity<TimingModel> durable =
                                ImmutableModelRoot.<TimingModel>builder()
                                        .id("manual-batch")
                                        .type(TimingModel.class)
                                        .idProperty("id")
                                        .stateIndex(0L)
                                        .sequenceNumber(-1L)
                                        .build();
                        assertEquals(
                                new TimingModel("manual-batch"),
                                ModelBatchScope.overlayCurrent(
                                        null, "manual-batch",
                                        TimingModel.class,
                                        durable).get());
                        publicationBarrier[0] =
                                Invocation.resultPublicationBarrier(
                                        current);
                        assertFalse(
                                publicationBarrier[0].isDone());
                    });

            CommitModels commit =
                    committed.get(5, TimeUnit.SECONDS);
            assertFalse(update[0].isDone());
            response.complete(acceptedResult(commit));
            update[0].get(5, TimeUnit.SECONDS);
            publicationBarrier[0].get(5, TimeUnit.SECONDS);
        } finally {
            subject.close();
        }
    }

    @Test
    void heterogeneousCollectionApplyCommitsAllCreatedModelsAtomically() {
        DefaultModelRepository repository =
                mock(DefaultModelRepository.class);
        stubModelLoads(repository);
        EventStoreClient eventStoreClient =
                mock(EventStoreClient.class);
        CompletableFuture<CommitModels> captured =
                new CompletableFuture<>();
        when(eventStoreClient.commitModels(any()))
                .thenAnswer(invocation -> {
                    CommitModels commit = invocation.getArgument(0);
                    captured.complete(commit);
                    List<ModelUpdate> updates =
                            new java.util.ArrayList<>();
                    long stateIndex = 1L;
                    for (int i = 0; i < commit.getSubsteps().size(); i++) {
                        var step = commit.getSubsteps().get(i);
                        updates.add(new ModelUpdate(
                                ModelUpdateKind.COMMIT, commit.getCommitId(), i,
                                stateIndex++, step.getEvent() == null ? null : stateIndex,
                                step.getTargets().stream()
                                        .map(target -> new io.fluxzero.common.api.modeling.ModelCommitTargetResult(
                                                target.getModelId(), 0L, true))
                                        .toList()));
                    }
                    return CompletableFuture.completedFuture(
                            CommitModelsResult.accepted(
                                    commit.getRequestId(),
                                    commit.getCommitId(), updates));
                });
        ModelCommitHandlerRegistry subject =
                subject(repository, eventStoreClient);

        try {
            subject.assertAndApply(new Message(
                    new CreateCollectionModels("first", "second")))
                    .join();

            CommitModels commit = captured.join();
            assertEquals(1, commit.getSubsteps().size());
            assertEquals(
                    List.of("first", "second"),
                    commit.getSubsteps().getFirst().getTargets().stream()
                            .map(io.fluxzero.common.api.modeling.ModelCommitTarget::getModelId)
                            .toList());
            assertEquals(List.of("first", "second"),
                         commit.getReadModelIds());
            verify(eventStoreClient, times(1)).commitModels(any());
        } finally {
            subject.close();
        }
    }

    @Test
    void storedEventApplyParticipatesInTheCurrentMessageBatchView()
            throws Exception {
        DefaultModelRepository repository =
                mock(DefaultModelRepository.class);
        stubModelLoads(repository);
        EventStoreClient eventStoreClient =
                mock(EventStoreClient.class);
        CompletableFuture<CommitModels> committed =
                new CompletableFuture<>();
        CompletableFuture<CommitModelsResult> response =
                new CompletableFuture<>();
        when(eventStoreClient.commitModels(any()))
                .thenAnswer(invocation -> {
                    committed.complete(invocation.getArgument(0));
                    return response;
                });
        ModelCommitHandlerRegistry subject =
                subject(repository, eventStoreClient);
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] update =
                new CompletableFuture[1];

        try {
            DeserializingMessage.forEachInBatch(
                    List.of(
                            message("event-producer", 9),
                            message("event-consumer", 9)),
                    current -> {
                        if (DeserializingMessage.getMessageBatchIndex() == 0) {
                            update[0] = subject.applyStoredEvent(
                                    new Message(
                                            new TimingCreateCommand(
                                                    "stored-event-batch")));
                            return;
                        }
                        Entity<TimingModel> durable =
                                ImmutableModelRoot.<TimingModel>builder()
                                        .id("stored-event-batch")
                                        .type(TimingModel.class)
                                        .idProperty("id")
                                        .stateIndex(0L)
                                        .sequenceNumber(-1L)
                                        .build();
                        assertEquals(
                                new TimingModel(
                                        "stored-event-batch"),
                                ModelBatchScope.overlayCurrent(
                                        null, "stored-event-batch",
                                        TimingModel.class,
                                        durable).get());
                    });

            CommitModels commit =
                    committed.get(5, TimeUnit.SECONDS);
            assertFalse(update[0].isDone());
            response.complete(acceptedResult(commit));
            update[0].get(5, TimeUnit.SECONDS);
        } finally {
            subject.close();
        }
    }

    @Test
    void publishedEventMigrationRetainsItsIdentityWithoutRepublishing() {
        DefaultModelRepository repository = mock(DefaultModelRepository.class);
        stubModelLoads(repository);
        EventStoreClient eventStoreClient = mock(EventStoreClient.class);
        CompletableFuture<CommitModels> captured = new CompletableFuture<>();
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation -> {
            CommitModels commit = invocation.getArgument(0);
            captured.complete(commit);
            String modelId = commit.getSubsteps().getFirst()
                    .getTargets().getFirst().getModelId();
            return CompletableFuture.completedFuture(
                    CommitModelsResult.acceptedSingleTarget(
                            commit.getRequestId(), commit.getCommitId(),
                            91L, 42L, modelId, 0L, true));
        });
        ModelCommitHandlerRegistry subject = subject(repository, eventStoreClient);
        Message event = new Message(new TimingCreateCommand("migrated"));

        try {
            subject.migratePublishedEvent(event, 42L).join();

            CommitModels commit = captured.join();
            assertEquals(event.getMessageId(), commit.getCommitId());
            assertEquals(1, commit.getSubsteps().size());
            assertFalse(commit.getSubsteps().getFirst().isPublishEvent());
            assertEquals(42L, commit.getSubsteps().getFirst().getEvent().getIndex());
            assertEquals(event.getMessageId(),
                         commit.getSubsteps().getFirst().getEvent().getMessageId());
            assertTrue(commit.getSubsteps().getFirst().getTargets().getFirst().isStoreEvent());
            assertEquals(null,
                         commit.getSubsteps().getFirst().getTargets().getFirst().getDocument());
            verify(eventStoreClient, times(1)).commitModels(any());
        } finally {
            subject.close();
        }
    }

    @Test
    void publishedEventMigrationStagesDocumentBackedModelsThroughTheSameCommit() {
        DefaultModelRepository repository = mock(DefaultModelRepository.class);
        stubModelLoads(repository);
        EventStoreClient eventStoreClient = mock(EventStoreClient.class);
        CompletableFuture<CommitModels> captured = new CompletableFuture<>();
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation -> {
            CommitModels commit = invocation.getArgument(0);
            captured.complete(commit);
            return CompletableFuture.completedFuture(
                    acceptedResult(commit));
        });
        ModelCommitHandlerRegistry subject = subject(repository, eventStoreClient);
        RetryBoundaryCommand.observations.clear();

        try {
            subject.migratePublishedEvent(
                    new Message(new RetryBoundaryCommand("document-backed")), 42L).join();

            CommitModels commit = captured.join();
            assertTrue(commit.isMigration());
            assertFalse(commit.getSubsteps().getFirst().isPublishEvent());
            assertEquals(
                    42L,
                    commit.getSubsteps().getFirst().getEvent().getIndex());
            assertTrue(commit.getSubsteps().getFirst()
                               .getTargets().getFirst()
                               .getDocument() != null);
            assertTrue(RetryBoundaryCommand.observations.isEmpty());
            verify(eventStoreClient).commitModels(any());
        } finally {
            subject.close();
        }
    }

    @Test
    void publishedEventMigrationDoesNotStartMaterializedGraphProjection() {
        DefaultModelRepository repository = mock(DefaultModelRepository.class);
        stubModelLoads(repository);
        EventStoreClient eventStoreClient = mock(EventStoreClient.class);
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation ->
                CompletableFuture.completedFuture(
                        acceptedResult(invocation.getArgument(0))));
        ModelCommitHandlerRegistry subject = subject(repository, eventStoreClient);

        try {
            subject.migratePublishedEvent(
                    new Message(new RetryRootMigration("migrated-graph")),
                    42L).join();

            verify(repository, never()).registerGraphProjection(
                    any(), anyBoolean());
            verify(eventStoreClient).commitModels(any());
        } finally {
            subject.close();
        }
    }

    @Test
    void explicitBulkAssertAndApplyFlushesValidCommitsWhenAnotherUpdateFails() throws Exception {
        DefaultModelRepository repository = mock(DefaultModelRepository.class);
        stubModelLoads(repository);
        EventStoreClient eventStoreClient = mock(
                EventStoreClient.class,
                withSettings().extraInterfaces(ModelCommitBatchingClient.class));
        ModelCommitBatchingClient batchingClient =
                (ModelCommitBatchingClient) eventStoreClient;
        ModelCommitBatchingClient.ModelCommitBatch transportBatch =
                mock(ModelCommitBatchingClient.ModelCommitBatch.class, CALLS_REAL_METHODS);
        CompletableFuture<CommitModels> prepared = new CompletableFuture<>();
        CompletableFuture<CommitModelsResult> response = new CompletableFuture<>();
        when(batchingClient.beginReadyModelCommitBatch()).thenReturn(transportBatch);
        when(transportBatch.add(anyInt(), any())).thenAnswer(invocation -> {
            prepared.complete(invocation.getArgument(1));
            return response;
        });
        ModelCommitHandlerRegistry subject = subject(repository, eventStoreClient);

        try {
            CompletableFuture<Void> result = subject.assertAndApplyAll(List.of(
                    new Message(new CrossApplicationCommand("")),
                    new Message(new TimingCreateCommand("bulk-valid"))));

            CommitModels commit = prepared.get(5, TimeUnit.SECONDS);
            verify(transportBatch, timeout(5_000).times(1)).flush();
            assertFalse(result.isDone());

            response.complete(acceptedResult(commit));
            assertThrows(CompletionException.class, result::join);
            verify(transportBatch, times(1)).add(anyInt(), any());
        } finally {
            subject.close();
        }
    }

    @Test
    void automaticModelHandlerIsTrackedRatherThanHandledLocally() {
        ModelCommitHandlerRegistry subject =
                subject(AutomaticModelHandling.ENABLED);
        try {
            subject.setSelfHandlerFilter((type, method) -> false);
            assertTrue(subject.createHandler(
                    TimingCreateCommand.class,
                    HandlerFilter.ALWAYS_HANDLE,
                    List.of()).isPresent());
            assertFalse(subject.canHandle(
                    message(new TimingCreateCommand("tracked"))));
            assertTrue(subject.handle(
                    message(new TimingCreateCommand("tracked"))).isEmpty());

            subject.setSelfHandlerFilter(HandlerFilter.ALWAYS_HANDLE);

            assertTrue(subject.canHandle(
                    message(new TimingCreateCommand("fixture"))));
        } finally {
            subject.close();
        }
    }

    @Test
    void automaticModelHandlerDoesNotDeserializeNonMatchingPayload() {
        ModelCommitHandlerRegistry subject =
                subject(AutomaticModelHandling.ENABLED);
        try {
            Handler<DeserializingMessage> handler = subject.createHandler(
                    TimingCreateCommand.class,
                    HandlerFilter.ALWAYS_HANDLE,
                    List.of()).orElseThrow();
            AtomicInteger deserializationAttempts = new AtomicInteger();

            assertNull(handler.getInvokerOrNull(malformedMessage(
                    CrossApplicationCommand.class,
                    deserializationAttempts)));
            assertEquals(0, deserializationAttempts.get());
        } finally {
            subject.close();
        }
    }

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
    void receiverInterceptorTracksCommandWhenItsOutputReachesAnotherModelApply() {
        ModelCommitHandlerRegistry subject =
                subject(AutomaticModelHandling.ENABLED);
        subject.registerHandler(
                InterceptingModel.class,
                HandlerFilter.ALWAYS_HANDLE);
        subject.registerHandler(
                StaticApplyModel.class,
                HandlerFilter.ALWAYS_HANDLE);

        assertEquals(
                List.of(ReceiverInterceptCommand.class),
                subject.trackingTargets(
                        InterceptingModel.class,
                        HandlerFilter.ALWAYS_HANDLE));
        assertTrue(subject.createHandler(
                ReceiverInterceptCommand.class,
                HandlerFilter.ALWAYS_HANDLE,
                List.of()).isPresent());
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
    }

    @Test
    void automaticHandlingUsesApplyThenModelThenApplicationPrecedence() {
        ModelCommitHandlerRegistry disabledApplication =
                subject(AutomaticModelHandling.DISABLED);
        assertTrue(disabledApplication.createHandler(
                TimingCreateCommand.class,
                HandlerFilter.ALWAYS_HANDLE,
                List.of()).isEmpty());
        disabledApplication.registerHandler(
                ReceiverModel.class,
                HandlerFilter.ALWAYS_HANDLE);
        disabledApplication.registerHandler(
                ExplicitlyEnabledModel.class,
                HandlerFilter.ALWAYS_HANDLE);
        disabledApplication.registerHandler(
                ApplyEnabledModel.class,
                HandlerFilter.ALWAYS_HANDLE);
        disabledApplication.setSelfHandlerFilter(
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
        enabledApplication.setSelfHandlerFilter(
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
        subject.setSelfHandlerFilter(
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
    void runtimeTypedApplyUsesTheStrongestCompletionGuarantee() {
        ModelCommitHandlerRegistry subject =
                subject(AutomaticModelHandling.ENABLED);

        assertEquals(
                ModelCommitPolicy.SYNC_AFTER_HANDLER,
                subject.commitPolicyFor(
                        CreateCollectionModels.class));
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
        CompletableFuture<List<Commit.Outcome>> updatedModels =
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
                    any(MutationPlan.Resolution.class),
                    nullable(Long.class), anyMap(), anyBoolean());
            assertEquals(
                    committedEventId.join(),
                    updatedModels.join().getFirst().commit().getSubsteps()
                            .getFirst().getEvent().getMessageId());
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
        CompletableFuture<CompletableFuture<Void>> publicationBarrierStarted = new CompletableFuture<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            CompletableFuture<Void> batch = CompletableFuture.runAsync(() ->
                    DeserializingMessage.forEachInBatch(
                            List.of(message(new TimingCreateCommand("timed"))),
                            current -> {
                                handlingStarted.complete(subject.handle(current).orElseThrow());
                                publicationBarrierStarted.complete(
                                        Invocation.resultPublicationBarrier(current));
                            }), executor);

            CompletableFuture.anyOf(commitStarted, batch).get(5, TimeUnit.SECONDS);
            if (!commitStarted.isDone()) {
                batch.join();
            }
            CommitModels commit = commitStarted.join();
            CompletableFuture<Object> handlingResult = handlingStarted.get(5, TimeUnit.SECONDS);
            CompletableFuture<Void> publicationBarrier = publicationBarrierStarted.get(5, TimeUnit.SECONDS);
            assertFalse(batch.isDone());
            assertTrue(handlingResult.isDone());
            assertFalse(publicationBarrier.isDone());

            commitResponse.complete(CommitModelsResult.acceptedSingleTarget(
                    commit.getRequestId(), commit.getCommitId(), 1L, 1L,
                    "timed", 0L, true));
            batch.get(5, TimeUnit.SECONDS);
            handlingResult.get(5, TimeUnit.SECONDS);
            publicationBarrier.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            subject.close();
        }
    }

    @Test
    void preparedAsyncModelInvocationMayStartAfterBatchClose() throws Exception {
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
        Handler<DeserializingMessage> handler = subject.createHandler(
                TimingCreateCommand.class, HandlerFilter.ALWAYS_HANDLE, List.of()).orElseThrow();
        DeserializingMessage command = message(new TimingCreateCommand("async-close"));
        CompletableFuture<HandlerInvoker> prepared = new CompletableFuture<>();
        CompletableFuture<Void> closeCallbackRan = new CompletableFuture<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            CompletableFuture<Void> batch = CompletableFuture.runAsync(() ->
                    DeserializingMessage.forEachInBatch(
                            List.of(command),
                            current -> {
                                HandlerInvoker invoker = handler.getInvokerOrNull(current);
                                invoker.prepareAsyncInvocation();
                                prepared.complete(invoker);
                                DeserializingMessage.whenBatchCompletes(
                                        error -> closeCallbackRan.complete(null));
                            }), executor);

            HandlerInvoker invoker = prepared.get(5, TimeUnit.SECONDS);
            closeCallbackRan.get(5, TimeUnit.SECONDS);
            assertFalse(batch.isDone());
            assertTrue(Invocation.resultPublicationBarrier(command).isDone());

            CompletableFuture<Object> invocation = CompletableFuture.supplyAsync(invoker::invoke);
            CommitModels commit = commitStarted.get(5, TimeUnit.SECONDS);
            assertEquals(null, invocation.get(5, TimeUnit.SECONDS));
            CompletableFuture<Void> publicationBarrier = Invocation.resultPublicationBarrier(command);
            assertFalse(batch.isDone());
            assertFalse(publicationBarrier.isDone());

            commitResponse.complete(CommitModelsResult.acceptedSingleTarget(
                    commit.getRequestId(), commit.getCommitId(), 1L, 1L,
                    "async-close", 0L, true));
            batch.get(5, TimeUnit.SECONDS);
            publicationBarrier.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            subject.close();
        }
    }

    @Test
    void abandonedPreparedAsyncModelInvocationDoesNotFailBatch() {
        DefaultModelRepository repository = mock(DefaultModelRepository.class);
        stubModelLoads(repository);
        EventStoreClient eventStoreClient = mock(EventStoreClient.class);
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation ->
                CompletableFuture.completedFuture(acceptedResult(invocation.getArgument(0))));
        ModelCommitHandlerRegistry subject = subject(repository, eventStoreClient);
        Handler<DeserializingMessage> abandonedHandler = subject.createHandler(
                TimingCreateCommand.class, HandlerFilter.ALWAYS_HANDLE, List.of()).orElseThrow();
        Handler<DeserializingMessage> activeHandler = subject.createHandler(
                BatchTimingCreateCommand.class, HandlerFilter.ALWAYS_HANDLE, List.of()).orElseThrow();
        DeserializingMessage abandoned = message(new TimingCreateCommand("abandoned"));
        DeserializingMessage active = message(new BatchTimingCreateCommand("active"));
        AtomicInteger index = new AtomicInteger();

        try {
            DeserializingMessage.forEachInBatch(List.of(abandoned, active), current -> {
                boolean first = index.getAndIncrement() == 0;
                HandlerInvoker invoker = (first ? abandonedHandler : activeHandler).getInvokerOrNull(current);
                Registration preparation = invoker.prepareAsyncInvocation();
                if (!first) {
                    invoker.invoke();
                }
                preparation.cancel();
            });

            verify(eventStoreClient, times(1)).commitModels(any());
            assertTrue(Invocation.resultPublicationBarrier(abandoned).isDone());
            assertTrue(Invocation.resultPublicationBarrier(active).isDone());
        } finally {
            subject.close();
        }
    }

    @Test
    void defaultPolicyBuffersReadyTransportBeforeBatchCloseAndFlushesTailAtClose()
            throws Exception {
        DefaultModelRepository repository = mock(DefaultModelRepository.class);
        stubModelLoads(repository);
        EventStoreClient eventStoreClient = mock(
                EventStoreClient.class,
                withSettings().extraInterfaces(ModelCommitBatchingClient.class));
        ModelCommitBatchingClient batchingClient =
                (ModelCommitBatchingClient) eventStoreClient;
        ModelCommitBatchingClient.ModelCommitBatch transportBatch =
                mock(ModelCommitBatchingClient.ModelCommitBatch.class, CALLS_REAL_METHODS);
        CompletableFuture<CommitModels> commitPrepared = new CompletableFuture<>();
        CompletableFuture<Void> transportFlushed = new CompletableFuture<>();
        CompletableFuture<CommitModelsResult> commitResponse = new CompletableFuture<>();
        when(batchingClient.beginReadyModelCommitBatch()).thenReturn(transportBatch);
        when(transportBatch.add(anyInt(), any())).thenAnswer(invocation -> {
            commitPrepared.complete(invocation.getArgument(1));
            return commitResponse;
        });
        doAnswer(invocation -> {
            transportFlushed.complete(null);
            return null;
        }).when(transportBatch).flush();
        ModelCommitHandlerRegistry subject = subject(repository, eventStoreClient);
        CompletableFuture<CompletableFuture<Object>> handlingStarted = new CompletableFuture<>();
        CompletableFuture<CompletableFuture<Void>> publicationBarrierStarted = new CompletableFuture<>();
        CountDownLatch finishHandlerIteration = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            CompletableFuture<Void> batch = CompletableFuture.runAsync(() ->
                    DeserializingMessage.forEachInBatch(
                            List.of(message(new TimingCreateCommand("timed"))),
                            current -> {
                                handlingStarted.complete(
                                        subject.handle(current).orElseThrow());
                                publicationBarrierStarted.complete(
                                        Invocation.resultPublicationBarrier(current));
                                try {
                                    finishHandlerIteration.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(e);
                                }
                            }), executor);

            CommitModels commit = commitPrepared.get(5, TimeUnit.SECONDS);
            CompletableFuture<Object> handlingResult = handlingStarted.get(5, TimeUnit.SECONDS);
            CompletableFuture<Void> publicationBarrier = publicationBarrierStarted.get(5, TimeUnit.SECONDS);
            assertFalse(transportFlushed.isDone());
            assertFalse(batch.isDone());
            assertTrue(handlingResult.isDone());
            assertFalse(publicationBarrier.isDone());
            verify(eventStoreClient, never()).commitModels(any());

            finishHandlerIteration.countDown();
            transportFlushed.get(5, TimeUnit.SECONDS);
            assertFalse(batch.isDone());

            commitResponse.complete(CommitModelsResult.acceptedSingleTarget(
                    commit.getRequestId(), commit.getCommitId(), 1L, 1L,
                    "timed", 0L, true));
            batch.get(5, TimeUnit.SECONDS);
            handlingResult.get(5, TimeUnit.SECONDS);
            publicationBarrier.get(5, TimeUnit.SECONDS);
        } finally {
            finishHandlerIteration.countDown();
            executor.shutdownNow();
            subject.close();
        }
    }

    @Test
    void dependentHandlerFlushesReadyTransportBeforeAwaitingPredecessor()
            throws Exception {
        Map<String, Object> durable = new ConcurrentHashMap<>();
        BatchParentId parentId = new BatchParentId("handler-dependent");
        durable.put(parentId.toString(), new BatchParent(parentId, 0));
        DefaultModelRepository repository = mock(DefaultModelRepository.class);
        stubBatchModelLoads(repository, durable);
        EventStoreClient eventStoreClient = mock(
                EventStoreClient.class,
                withSettings().extraInterfaces(ModelCommitBatchingClient.class));
        ModelCommitBatchingClient batchingClient =
                (ModelCommitBatchingClient) eventStoreClient;
        ModelCommitBatchingClient.ModelCommitBatch transportBatch =
                mock(ModelCommitBatchingClient.ModelCommitBatch.class, CALLS_REAL_METHODS);
        CompletableFuture<CommitModels> firstPrepared = new CompletableFuture<>();
        CompletableFuture<CommitModelsResult> firstResponse = new CompletableFuture<>();
        CompletableFuture<Void> transportFlushed = new CompletableFuture<>();
        when(batchingClient.beginReadyModelCommitBatch()).thenReturn(transportBatch);
        when(transportBatch.add(anyInt(), any())).thenAnswer(invocation -> {
            CommitModels commit = invocation.getArgument(1);
            firstPrepared.complete(commit);
            return firstResponse;
        });
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation ->
                CompletableFuture.completedFuture(acceptedResult(invocation.getArgument(0))));
        doAnswer(invocation -> {
            transportFlushed.complete(null);
            firstResponse.complete(acceptedResult(firstPrepared.join()));
            return null;
        }).when(transportBatch).flush();
        ModelCommitHandlerRegistry subject = subject(repository, eventStoreClient);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<?>[] handling = new CompletableFuture<?>[2];
        int[] handlingIndex = {0};

        try {
            CompletableFuture<Void> batch = CompletableFuture.runAsync(() ->
                    DeserializingMessage.forEachInBatch(
                            List.of(
                                    message(new IncrementBatchParent(parentId, 0), 42),
                                    message(new IncrementBatchParent(parentId, 1), 42)),
                            current -> {
                                int index = handlingIndex[0]++;
                                handling[index] = subject.handle(current).orElseThrow();
                                if (index == 1) {
                                    handling[index].join();
                                }
                            }), executor);

            batch.get(5, TimeUnit.SECONDS);
            transportFlushed.get(5, TimeUnit.SECONDS);
            assertTrue(handling[0].isDone());
            assertEquals(new BatchParent(parentId, 2), durable.get(parentId.toString()));
            verify(transportBatch, times(1)).flush();
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
        CompletableFuture<CompletableFuture<Void>> publicationBarrierStarted = new CompletableFuture<>();
        CountDownLatch finishHandlerIteration = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            CompletableFuture<Void> batch = CompletableFuture.runAsync(() ->
                    DeserializingMessage.forEachInBatch(
                            List.of(message(new BatchTimingCreateCommand("deferred"))),
                            current -> {
                                handlingStarted.complete(subject.handle(current).orElseThrow());
                                publicationBarrierStarted.complete(
                                        Invocation.resultPublicationBarrier(current));
                                try {
                                    finishHandlerIteration.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(e);
                                }
                            }), executor);

            CompletableFuture<Object> handlingResult = handlingStarted.get(5, TimeUnit.SECONDS);
            CompletableFuture<Void> publicationBarrier = publicationBarrierStarted.get(5, TimeUnit.SECONDS);
            assertFalse(commitStarted.isDone());
            assertTrue(handlingResult.isDone());
            assertFalse(publicationBarrier.isDone());

            finishHandlerIteration.countDown();
            CommitModels commit = commitStarted.get(5, TimeUnit.SECONDS);
            assertFalse(batch.isDone());
            commitResponse.complete(CommitModelsResult.acceptedSingleTarget(
                    commit.getRequestId(), commit.getCommitId(), 1L, 1L,
                    "deferred", 0L, true));
            batch.get(5, TimeUnit.SECONDS);
            handlingResult.get(5, TimeUnit.SECONDS);
            publicationBarrier.get(5, TimeUnit.SECONDS);
        } finally {
            finishHandlerIteration.countDown();
            executor.shutdownNow();
            subject.close();
        }
    }

    @Test
    void explicitCommitReleasesTheExistingAfterBatchCommitExactlyOnce()
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
        Fluxzero fluxzero = mock(Fluxzero.class, CALLS_REAL_METHODS);
        AtomicReference<CompletableFuture<Void>> explicit = new AtomicReference<>();
        CompletableFuture<CompletableFuture<Void>> explicitIssued = new CompletableFuture<>();
        CountDownLatch finishHandlerIteration = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            CompletableFuture<Void> batch = CompletableFuture.runAsync(() -> {
                Fluxzero previous = Fluxzero.instance.get();
                Fluxzero.instance.set(fluxzero);
                try {
                    DeserializingMessage.forEachInBatch(
                            List.of(message(new BatchTimingCreateCommand("explicit"))),
                            current -> {
                                subject.handle(current).orElseThrow();
                                explicit.set(Fluxzero.commit());
                                assertSame(explicit.get(), Fluxzero.commit());
                                explicitIssued.complete(explicit.get());
                                try {
                                    finishHandlerIteration.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(e);
                                }
                            });
                } finally {
                    Fluxzero.instance.set(previous);
                }
            }, executor);

            CommitModels commit = commitStarted.get(5, TimeUnit.SECONDS);
            CompletableFuture<Void> explicitCompletion = explicitIssued.get(5, TimeUnit.SECONDS);
            assertFalse(explicitCompletion.isDone());
            assertFalse(batch.isDone());

            commitResponse.complete(CommitModelsResult.acceptedSingleTarget(
                    commit.getRequestId(), commit.getCommitId(), 1L, 1L,
                    "explicit", 0L, true));
            explicitCompletion.get(5, TimeUnit.SECONDS);
            assertFalse(batch.isDone());

            finishHandlerIteration.countDown();
            batch.get(5, TimeUnit.SECONDS);
            verify(eventStoreClient, times(1)).commitModels(any());
        } finally {
            finishHandlerIteration.countDown();
            executor.shutdownNow();
            subject.close();
        }
    }

    @Test
    void explicitCommitDoesNotOpenTransportForAnEmptyModelChange() {
        DefaultModelRepository repository = mock(DefaultModelRepository.class);
        stubModelLoads(repository);
        EventStoreClient eventStoreClient = mock(EventStoreClient.class);
        ModelCommitHandlerRegistry subject = subject(repository, eventStoreClient);
        Fluxzero fluxzero = mock(Fluxzero.class, CALLS_REAL_METHODS);

        try {
            Fluxzero previous = Fluxzero.instance.get();
            Fluxzero.instance.set(fluxzero);
            try {
                DeserializingMessage.forEachInBatch(
                        List.of(message(new EmptyModelChangeCommand())), current -> {
                            subject.handle(current).orElseThrow();
                            Fluxzero.commit().join();
                        });
            } finally {
                Fluxzero.instance.set(previous);
            }
            verify(eventStoreClient, never()).commitModels(any());
        } finally {
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
    void batchCommandsObserveEarlierStagedAncestorsBeforeCommitAndReevaluateAfterCommit()
            throws Exception {
        Map<String, Object> durable = new ConcurrentHashMap<>();
        BatchParentId parentId = new BatchParentId("shared");
        BatchChildId childId = new BatchChildId("shared");
        String unrelatedId = "unrelated";
        durable.put(parentId.toString(), new BatchParent(parentId, 0));
        durable.put(childId.toString(), new BatchChild(childId, parentId, 0));
        DefaultModelRepository repository = mock(DefaultModelRepository.class);
        stubBatchModelLoads(repository, durable);
        EventStoreClient eventStoreClient = mock(EventStoreClient.class);
        LinkedBlockingQueue<PendingResponse> started = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<CompletableFuture<Object>> handled = new LinkedBlockingQueue<>();
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation -> {
            PendingResponse response = new PendingResponse(invocation.getArgument(0));
            started.add(response);
            return response.result();
        });
        ModelCommitHandlerRegistry subject = subject(repository, eventStoreClient);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        BATCH_PARENT_OBSERVATIONS.clear();
        BATCH_INCREMENT_OBSERVATIONS.clear();
        UpdateBatchChild update = new UpdateBatchChild(childId, 1);
        assertTrue(MutationPlan.compile(
                UpdateBatchChild.class,
                EntityMetadata.of(UpdateBatchChild.class).handlerMethods())
                           .resolve(update)
                           .hasAncestorDependencies());

        try {
            CompletableFuture<Void> batch = CompletableFuture.runAsync(() ->
                    DeserializingMessage.forEachInBatch(
                            List.of(
                                    message(new IncrementBatchParent(parentId, 0), 42),
                                    message(new CreateUnrelatedBatchModel(unrelatedId), 42),
                                    message(new UpdateBatchChild(childId, 1), 42)),
                            current -> handled.add(
                                    subject.handle(current).orElseThrow())), executor);

            PendingResponse firstCommit = started.poll(5, TimeUnit.SECONDS);
            PendingResponse secondCommit = started.poll(5, TimeUnit.SECONDS);
            assertTrue(firstCommit != null);
            assertTrue(secondCommit != null);
            Map<String, PendingResponse> initialCommits = Map.of(
                    committedModelId(firstCommit), firstCommit,
                    committedModelId(secondCommit), secondCommit);
            PendingResponse parentCommit = initialCommits.get(parentId.toString());
            PendingResponse unrelatedCommit = initialCommits.get(unrelatedId);
            assertTrue(parentCommit != null);
            assertTrue(unrelatedCommit != null);
            assertTrue(handled.poll(5, TimeUnit.SECONDS) != null);
            assertTrue(handled.poll(5, TimeUnit.SECONDS) != null);
            CompletableFuture<Object> childHandling = handled.poll(5, TimeUnit.SECONDS);
            assertTrue(childHandling != null);
            if (childHandling.isCompletedExceptionally()) {
                childHandling.join();
            }
            Integer firstObservation =
                    BATCH_PARENT_OBSERVATIONS.poll(5, TimeUnit.SECONDS);
            if (firstObservation == null
                && childHandling.isDone()) {
                childHandling.join();
            }
            assertEquals(1, firstObservation);
            assertTrue(started.isEmpty());

            parentCommit.accept();
            unrelatedCommit.accept();

            PendingResponse childCommit = started.poll(5, TimeUnit.SECONDS);
            assertTrue(childCommit != null);
            assertEquals(childId.toString(), committedModelId(childCommit));
            assertEquals(1, BATCH_PARENT_OBSERVATIONS.poll(5, TimeUnit.SECONDS));
            childCommit.accept();
            batch.get(5, TimeUnit.SECONDS);

            assertEquals(new BatchParent(parentId, 1), durable.get(parentId.toString()));
            assertEquals(new BatchChild(childId, parentId, 1), durable.get(childId.toString()));
            assertEquals(
                    new UnrelatedBatchModel(unrelatedId),
                    durable.get(unrelatedId));
            assertTrue(BATCH_PARENT_OBSERVATIONS.isEmpty());
        } finally {
            executor.shutdownNow();
            subject.close();
            BATCH_PARENT_OBSERVATIONS.clear();
            BATCH_INCREMENT_OBSERVATIONS.clear();
        }
    }

    @Test
    void repeatedModelUpdatesUseTheEarlierStagedValueWithinTheBatch()
            throws Exception {
        Map<String, Object> durable = new ConcurrentHashMap<>();
        BatchParentId parentId = new BatchParentId("repeated");
        durable.put(parentId.toString(), new BatchParent(parentId, 0));
        DefaultModelRepository repository = mock(DefaultModelRepository.class);
        stubBatchModelLoads(repository, durable);
        EventStoreClient eventStoreClient = mock(EventStoreClient.class);
        LinkedBlockingQueue<PendingResponse> started = new LinkedBlockingQueue<>();
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation -> {
            PendingResponse response = new PendingResponse(invocation.getArgument(0));
            started.add(response);
            return response.result();
        });
        ModelCommitHandlerRegistry subject = subject(repository, eventStoreClient);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        BATCH_INCREMENT_OBSERVATIONS.clear();

        try {
            CompletableFuture<Void> batch = CompletableFuture.runAsync(() ->
                    DeserializingMessage.forEachInBatch(
                            List.of(
                                    message(new IncrementBatchParent(parentId, 0), 42),
                                    message(new IncrementBatchParent(parentId, 1), 42)),
                            current -> subject.handle(current).orElseThrow()), executor);

            PendingResponse first = started.poll(5, TimeUnit.SECONDS);
            assertTrue(first != null);
            assertEquals(0, BATCH_INCREMENT_OBSERVATIONS.poll(5, TimeUnit.SECONDS));
            assertEquals(1, BATCH_INCREMENT_OBSERVATIONS.poll(5, TimeUnit.SECONDS));
            assertTrue(started.isEmpty());
            first.accept();

            PendingResponse second = started.poll(5, TimeUnit.SECONDS);
            assertTrue(second != null);
            assertEquals(1, BATCH_INCREMENT_OBSERVATIONS.poll(5, TimeUnit.SECONDS));
            second.accept();
            batch.get(5, TimeUnit.SECONDS);

            assertEquals(new BatchParent(parentId, 2), durable.get(parentId.toString()));
            assertTrue(BATCH_INCREMENT_OBSERVATIONS.isEmpty());
        } finally {
            executor.shutdownNow();
            subject.close();
            BATCH_INCREMENT_OBSERVATIONS.clear();
        }
    }

    @Test
    void afterBatchPolicyUsesEarlierStagedValuesAndCommitsInOrder()
            throws Exception {
        Map<String, Object> durable = new ConcurrentHashMap<>();
        String modelId = "after-batch";
        durable.put(modelId, new StagedAfterBatchModel(modelId, 0));
        DefaultModelRepository repository = mock(DefaultModelRepository.class);
        stubBatchModelLoads(repository, durable);
        EventStoreClient eventStoreClient = mock(EventStoreClient.class);
        LinkedBlockingQueue<PendingResponse> started = new LinkedBlockingQueue<>();
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation -> {
            PendingResponse response = new PendingResponse(invocation.getArgument(0));
            started.add(response);
            return response.result();
        });
        ModelCommitHandlerRegistry subject = subject(repository, eventStoreClient);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        BATCH_INCREMENT_OBSERVATIONS.clear();

        try {
            CompletableFuture<Void> batch = CompletableFuture.runAsync(() ->
                    DeserializingMessage.forEachInBatch(
                            List.of(
                                    message(new IncrementStagedAfterBatch(modelId, 0), 42),
                                    message(new IncrementStagedAfterBatch(modelId, 1), 42)),
                            current -> subject.handle(current).orElseThrow()), executor);

            PendingResponse first = started.poll(5, TimeUnit.SECONDS);
            assertTrue(first != null);
            assertEquals(0, BATCH_INCREMENT_OBSERVATIONS.poll(5, TimeUnit.SECONDS));
            assertEquals(1, BATCH_INCREMENT_OBSERVATIONS.poll(5, TimeUnit.SECONDS));
            assertTrue(started.isEmpty());
            first.accept();

            PendingResponse second = started.poll(5, TimeUnit.SECONDS);
            assertTrue(second != null);
            assertEquals(1, BATCH_INCREMENT_OBSERVATIONS.poll(5, TimeUnit.SECONDS));
            second.accept();
            batch.get(5, TimeUnit.SECONDS);

            assertEquals(
                    new StagedAfterBatchModel(modelId, 2),
                    durable.get(modelId));
            assertTrue(BATCH_INCREMENT_OBSERVATIONS.isEmpty());
        } finally {
            executor.shutdownNow();
            subject.close();
            BATCH_INCREMENT_OBSERVATIONS.clear();
        }
    }

    @Test
    void batchDependencyFailsWithoutCommittingWhenItsPredecessorFails()
            throws Exception {
        Map<String, Object> durable = new ConcurrentHashMap<>();
        BatchParentId parentId = new BatchParentId("failed");
        BatchChildId childId = new BatchChildId("failed");
        durable.put(parentId.toString(), new BatchParent(parentId, 0));
        durable.put(childId.toString(), new BatchChild(childId, parentId, 0));
        DefaultModelRepository repository = mock(DefaultModelRepository.class);
        stubBatchModelLoads(repository, durable);
        EventStoreClient eventStoreClient = mock(EventStoreClient.class);
        LinkedBlockingQueue<PendingResponse> started = new LinkedBlockingQueue<>();
        when(eventStoreClient.commitModels(any())).thenAnswer(invocation -> {
            PendingResponse response = new PendingResponse(invocation.getArgument(0));
            started.add(response);
            return response.result();
        });
        ModelCommitHandlerRegistry subject = subject(repository, eventStoreClient);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        BATCH_PARENT_OBSERVATIONS.clear();
        BATCH_INCREMENT_OBSERVATIONS.clear();

        try {
            CompletableFuture<Void> batch = CompletableFuture.runAsync(() ->
                    DeserializingMessage.forEachInBatch(
                            List.of(
                                    message(new IncrementBatchParent(parentId, 0), 42),
                                    message(new UpdateBatchChild(childId, 1), 42)),
                            current -> subject.handle(current).orElseThrow()), executor);

            PendingResponse parentCommit = started.poll(5, TimeUnit.SECONDS);
            assertTrue(parentCommit != null);
            assertEquals(1, BATCH_PARENT_OBSERVATIONS.poll(5, TimeUnit.SECONDS));
            parentCommit.result().completeExceptionally(
                    new IllegalStateException("predecessor failed"));

            assertTrue(batch.handle((ignored, failure) -> failure != null)
                               .get(5, TimeUnit.SECONDS));
            assertTrue(started.isEmpty());
            assertEquals(new BatchParent(parentId, 0), durable.get(parentId.toString()));
            assertEquals(new BatchChild(childId, parentId, 0), durable.get(childId.toString()));
        } finally {
            executor.shutdownNow();
            subject.close();
            BATCH_PARENT_OBSERVATIONS.clear();
            BATCH_INCREMENT_OBSERVATIONS.clear();
        }
    }

    @Test
    void graphProjectionCompletionUsesApplyThenRootThenApplicationPrecedence()
            throws Exception {
        Change inherited =
                transition(
                        ProjectionChild.class,
                        ProjectionApplies.class
                                .getDeclaredMethod(
                                        "inherit"));
        Change asynchronous =
                transition(
                        ProjectionChild.class,
                        ProjectionApplies.class
                                .getDeclaredMethod(
                                        "asynchronous"));

        assertEquals(
                GraphProjectionCompletion.AWAIT,
                graphProjectionCompletion(
                        inherited,
                        GraphProjectionCompletion.DEFAULT,
                        GraphProjectionCompletion.AWAIT,
                        GraphProjectionCompletion.ASYNC));
        assertEquals(
                GraphProjectionCompletion.ASYNC,
                graphProjectionCompletion(
                        asynchronous,
                        GraphProjectionCompletion.DEFAULT,
                        GraphProjectionCompletion.AWAIT,
                        GraphProjectionCompletion.AWAIT));
        assertEquals(
                GraphProjectionCompletion.AWAIT,
                graphProjectionCompletion(
                        inherited,
                        GraphProjectionCompletion.DEFAULT,
                        GraphProjectionCompletion.DEFAULT,
                        GraphProjectionCompletion.AWAIT));
    }

    @Test
    void cascadedDeletionWithoutApplyHandlerUsesProjectionDefaults() {
        Change cascadedDeletion =
                Change.applied(
                        "cascade-child",
                        ProjectionChild.class,
                        0L,
                        null,
                        new Object(),
                        null,
                        null,
                        null,
                        true);

        assertEquals(
                GraphProjectionCompletion.AWAIT,
                graphProjectionCompletion(
                        cascadedDeletion,
                        GraphProjectionCompletion.DEFAULT,
                        GraphProjectionCompletion.AWAIT,
                        GraphProjectionCompletion.ASYNC));
    }

    @Test
    void compilesGraphCompletionIntoEachChange()
            throws Exception {
        Change asynchronous =
                transition(
                        "shared-child",
                        ProjectionChild.class,
                        ProjectionApplies.class
                                .getDeclaredMethod(
                                        "asynchronous"));
        Change awaiting =
                transition(
                        "shared-child",
                        ProjectionChild.class,
                        ProjectionApplies.class
                                .getDeclaredMethod(
                                        "awaiting"));

        assertEquals(GraphProjectionCompletion.ASYNC,
                     asynchronous.graphProjectionCompletion());
        assertEquals(GraphProjectionCompletion.AWAIT,
                     awaiting.graphProjectionCompletion());
    }

    @Test
    void activeConsumerPrecedesRootAndApplicationCompletion()
            throws Exception {
        Change inherited = transition(
                AsyncProjectionRoot.class,
                ProjectionApplies.class.getDeclaredMethod("inherit"));
        Change asynchronous = transition(
                AsyncProjectionRoot.class,
                ProjectionApplies.class.getDeclaredMethod("asynchronous"));
        assertEquals(
                GraphProjectionCompletion.AWAIT,
                graphProjectionCompletion(
                        inherited,
                        GraphProjectionCompletion.AWAIT,
                        GraphProjectionCompletion.ASYNC,
                        GraphProjectionCompletion.ASYNC));
        assertEquals(
                GraphProjectionCompletion.ASYNC,
                graphProjectionCompletion(
                        asynchronous,
                        GraphProjectionCompletion.AWAIT,
                        GraphProjectionCompletion.ASYNC,
                        GraphProjectionCompletion.ASYNC));
    }

    private static GraphProjectionCompletion graphProjectionCompletion(
            Change change,
            GraphProjectionCompletion consumer,
            GraphProjectionCompletion projection,
            GraphProjectionCompletion application) {
        return change.graphProjectionCompletion()
                .orElse(consumer).orElse(projection).orElse(application);
    }

    @Test
    void delegatesAutomaticGraphProjectionRegistrationToRepository() {
        DefaultModelRepository repository =
                mock(DefaultModelRepository.class);
        EventStoreClient eventStoreClient =
                mock(EventStoreClient.class);
        JacksonSerializer serializer =
                new JacksonSerializer();
        ModelCommitHandlerRegistry subject =
                new ModelCommitHandlerRegistry(
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
                        AutomaticModelHandling.ENABLED,
                        GraphProjectionCompletion.ASYNC);

        subject.registerHandler(
                RetryRoot.class,
                HandlerFilter.ALWAYS_HANDLE);
        subject.registerHandler(
                RetryRoot.class,
                HandlerFilter.ALWAYS_HANDLE);

        verify(repository, times(2))
                .registerGraphProjection(RetryRoot.class, false);
        verify(eventStoreClient, never())
                .registerModelGraphProjection(any());
    }

    @Test
    void migrationRegistrationAddsOnlyModelDefinitions() {
        DefaultModelRepository repository =
                mock(DefaultModelRepository.class);
        ModelCommitHandlerRegistry subject = subject(
                repository, mock(EventStoreClient.class));

        Registration registration = subject.registerMigrationTypes(
                List.of(RetryRoot.class));

        assertTrue(subject.registeredModelTypes().contains(
                RetryRoot.class));
        verify(repository, never()).registerGraphProjection(
                any(), anyBoolean());

        registration.cancel();
        assertFalse(subject.registeredModelTypes().contains(
                RetryRoot.class));
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
        when(repository.registerGraphProjection(any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.awaitGraphProjections(
                anyMap(), anyLong(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(null));
        ModelCommitHandlerRegistry result = new ModelCommitHandlerRegistry(
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
        result.setSelfHandlerFilter(HandlerFilter.ALWAYS_HANDLE);
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void stubModelLoads(DefaultModelRepository repository) {
        org.mockito.stubbing.Answer<CommitAttempt> answer = invocation -> {
            MutationPlan.Resolution resolution = invocation.getArgument(0);
            Map<String, Entity<?>> loaded = resolution.models().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            MutationPlan.ResolvedModel::modelId,
                            target -> ImmutableModelRoot.<Object>builder()
                                    .id(target.modelId())
                                    .type((Class<Object>) target.modelType())
                                    .idProperty(EntityMetadata.of(target.modelType())
                                            .entityId().orElseThrow().name())
                                    .value(null)
                                    .sequenceNumber(-1L)
                                    .stateIndex(0L)
                                    .build()));
            return CommitAttempt.create(0L, resolution, loaded);
        };
        when(repository.loadContext(
                any(MutationPlan.Resolution.class),
                nullable(Long.class), anyMap(), anyBoolean()))
                .thenAnswer(answer);
        when(repository.loadContext(
                any(MutationPlan.Resolution.class),
                nullable(Long.class), anyMap(), anyBoolean(), anyBoolean()))
                .thenAnswer(answer);
        when(repository.beginLocalCommit(any())).thenReturn(() -> {
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void stubBatchModelLoads(
            DefaultModelRepository repository,
            Map<String, Object> durable) {
        org.mockito.stubbing.Answer<CommitAttempt> answer = invocation -> {
            MutationPlan.Resolution resolution = invocation.getArgument(0);
            Map<String, Object> staged = invocation.getArgument(2);
            if (invocation.<Boolean>getArgument(3)) {
                Map<String, Object> batch = ModelBatchScope.currentValues(
                        null, resolution);
                if (!batch.isEmpty()) {
                    LinkedHashMap<String, Object> combined = new LinkedHashMap<>(batch);
                    combined.putAll(staged);
                    staged = combined;
                }
            }
            LinkedHashMap<String, MutationPlan.ResolvedModel> targets =
                    new LinkedHashMap<>();
            resolution.models().forEach(target ->
                    MutationPlan.merge(targets, target));
            for (MutationPlan.AncestorDependency dependency :
                    resolution.ancestorDependencies()) {
                for (MutationPlan.ResolvedModel target :
                        resolution.models()) {
                    Object child = staged.containsKey(target.modelId())
                            ? staged.get(target.modelId())
                            : durable.get(target.modelId());
                    if (child == null) {
                        continue;
                    }
                    for (EntityMetadata.ParentReference parent :
                            EntityMetadata.validate(child.getClass()).parentReferences()) {
                        Object parentId = parent.read(child);
                        Class<?> parentModelType = parentId == null ? null : parent.parentModelType(parentId);
                        if (parentModelType == null
                            || !dependency.modelType().isAssignableFrom(
                                    parentModelType)) {
                            continue;
                        }
                        if (parentId != null) {
                            MutationPlan.merge(
                                    targets,
                                    new MutationPlan.ResolvedModel(
                                    parentId.toString(),
                                    dependency.modelType(),
                                    MutationPlan.Access.READ_ONLY,
                                    dependency.association() == null
                                            ? List.of()
                                            : List.of(dependency.association())));
                        }
                    }
                }
            }
            LinkedHashMap<String, Entity<?>> loaded = new LinkedHashMap<>();
            for (MutationPlan.ResolvedModel target : targets.values()) {
                Object value = staged.containsKey(target.modelId())
                        ? staged.get(target.modelId())
                        : durable.get(target.modelId());
                loaded.put(target.modelId(),
                           ImmutableModelRoot.<Object>builder()
                                   .id(target.modelId())
                                   .type((Class<Object>) target.modelType())
                                   .idProperty(EntityMetadata.of(target.modelType())
                                           .entityId().orElseThrow().name())
                                   .value(value)
                                   .sequenceNumber(value == null ? -1L : 0L)
                                   .stateIndex(0L)
                                   .build());
            }
            return CommitAttempt.create(
                    0L,
                    resolution.withResolvedModels(
                            List.copyOf(targets.values())),
                    loaded);
        };
        when(repository.loadContext(
                any(MutationPlan.Resolution.class),
                nullable(Long.class), anyMap(), anyBoolean()))
                .thenAnswer(answer);
        when(repository.beginLocalCommit(any())).thenReturn(() -> {
        });
        doAnswer(invocation -> {
            List<Commit.Outcome> committed = invocation.getArgument(0);
            committed.stream()
                    .flatMap(outcome -> outcome.changes().stream())
                    .filter(Change::updateState)
                    .forEach(change -> durable.put(change.modelId(), change.after()));
            return null;
        }).when(repository).updateAfterCommit(any());
    }

    private static String committedModelId(PendingResponse response) {
        return response.commit().getSubsteps().getFirst()
                .getTargets().getFirst().getModelId();
    }

    private static CommitModelsResult acceptedResult(CommitModels commit) {
        String modelId = commit.getSubsteps().getFirst()
                .getTargets().getFirst().getModelId();
        return CommitModelsResult.acceptedSingleTarget(
                commit.getRequestId(), commit.getCommitId(),
                1L, 1L, modelId, 0L, true);
    }

    private static DeserializingMessage message(Object payload) {
        return new DeserializingMessage(
                new Message(payload),
                MessageType.COMMAND,
                new JacksonSerializer());
    }

    private static DeserializingMessage message(
            Object payload, int segment) {
        DeserializingMessage result = message(payload);
        result.getSerializedObject().setSegment(segment);
        return result;
    }

    private static DeserializingMessage malformedMessage(
            Class<?> payloadClass,
            AtomicInteger deserializationAttempts) {
        SerializedMessage serializedMessage = new SerializedMessage(
                new Data<>(new byte[0], payloadClass.getName(), 0),
                Metadata.empty(), "message-id", 0L);
        return new DeserializingMessage(
                serializedMessage,
                ignored -> {
                    deserializationAttempts.incrementAndGet();
                    throw new IllegalStateException("malformed payload");
                },
                MessageType.COMMAND, null, null);
    }

    private static CommitAttempt evaluation(
            Change... transitions) {
        return CommitAttempt.fromChanges(
                1L,
                java.util.Arrays.stream(transitions)
                        .map(Change::modelId)
                        .toList(),
                java.util.Arrays.stream(transitions)
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Change::modelId,
                                        Change::modelType,
                                        (first, second) ->
                                                first)),
                null, List.of(transitions));
    }

    private static Change transition(
            Class<?> modelType,
            java.lang.reflect.Executable handler) {
        return transition(
                modelType.getName()
                + "#"
                + handler.getName(),
                modelType, handler);
    }

    private static Change transition(
            String modelId,
            Class<?> modelType,
            java.lang.reflect.Executable handler) {
        return Change.applied(
                modelId, modelType, 0L, null,
                null, new Object(), handler,
                null, false);
    }

    @Model(persistence = ModelPersistence.EVENT_SOURCED_WITH_DOCUMENT, materializeGraph = true,
            graphProjection = @GraphProjection(
                    collection = "retryRoots"))
    private record RetryRoot(
            @EntityId String id) {
    }

    private record RetryRootMigration(String id) {
        @Apply
        RetryRoot apply() {
            return new RetryRoot(id);
        }
    }

    @Model
    private record ReceiverModel(
            @EntityId String id) {
        @Apply(eventPublication = EventPublication.ALWAYS)
        ReceiverModel apply(ReceiverCommand command) {
            return new ReceiverModel(command.id());
        }
    }

    @Model(persistence = ModelPersistence.DOCUMENT)
    private record RetryBoundaryModel(
            @EntityId String id,
            String value) {
    }

    private record RetryBoundaryCommand(
            String id) {
        private static final ConcurrentLinkedQueue<String> observations =
                new ConcurrentLinkedQueue<>();

        @InterceptApply
        RetryBoundaryCommand intercept(
                @jakarta.annotation.Nullable RetryBoundaryModel model) {
            observations.add(
                    model == null
                            ? "missing"
                            : model.value());
            return this;
        }

        @Apply(conflictPolicy = ModelConflictPolicy.RETRY)
        RetryBoundaryModel apply(
                @jakarta.annotation.Nullable RetryBoundaryModel model) {
            return new RetryBoundaryModel(
                    id,
                    model == null
                            ? "created"
                            : model.value() + "-retried");
        }
    }

    private record ReceiverCommand(String id) {
    }

    private record CrossApplicationCommand(String id) {
        @AssertLegal
        void validate() {
            if (id.isBlank()) {
                throw new IllegalArgumentException(
                        "ID must not be blank");
            }
        }
    }

    private record SuppressedCrossApplicationCommand() {
        @InterceptApply
        Object intercept() {
            return null;
        }
    }

    @Model
    private record CrossApplicationModel(
            @EntityId String id) {
        @Apply
        CrossApplicationModel apply(
                CrossApplicationCommand command) {
            return new CrossApplicationModel(
                    command.id());
        }
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
    private record InterceptingModel(
            @EntityId String id) {
        @InterceptApply
        StaticCreateCommand intercept(
                ReceiverInterceptCommand command) {
            return new StaticCreateCommand(command.id());
        }
    }

    private record ReceiverInterceptCommand(
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

    @Model
    private record CollectionModelA(
            @EntityId String id) {
    }

    @Model
    private record CollectionModelB(
            @EntityId String id) {
    }

    private record CreateCollectionModels(
            String first,
            String second) {
        @Apply
        List<Object> apply() {
            return List.of(
                    new CollectionModelA(first),
                    new CollectionModelB(second));
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

    private record EmptyModelChangeCommand() {
        @Apply
        List<Object> apply() {
            return List.of();
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

    private static final class BatchParentId extends Id<BatchParent> {
        private BatchParentId(String id) {
            super(id, "batch-parent-");
        }
    }

    private static final class BatchChildId extends Id<BatchChild> {
        private BatchChildId(String id) {
            super(id, "batch-child-");
        }
    }

    @Model
    private record BatchParent(
            @EntityId BatchParentId id,
            int version) {
    }

    @Model
    private record BatchChild(
            @EntityId BatchChildId id,
            @Parent(pathInParent = "children") BatchParentId parentId,
            int observedParentVersion) {
    }

    private record IncrementBatchParent(
            BatchParentId id,
            int expectedVersion) {
        @AssertLegal
        void assertVersion(BatchParent parent) {
            BATCH_INCREMENT_OBSERVATIONS.add(parent.version());
            if (parent.version() != expectedVersion) {
                throw new IllegalStateException(
                        "Expected parent version " + expectedVersion
                        + " but found " + parent.version());
            }
        }

        @Apply
        BatchParent apply(BatchParent parent) {
            return new BatchParent(id, parent.version() + 1);
        }
    }

    private record UpdateBatchChild(
            BatchChildId id,
            int expectedParentVersion) {
        @AssertLegal
        void assertParent(
                @io.fluxzero.sdk.tracking.handling.Association("children")
                BatchParent parent) {
            BATCH_PARENT_OBSERVATIONS.add(parent.version());
            if (parent.version() != expectedParentVersion) {
                throw new IllegalStateException(
                        "Expected parent version " + expectedParentVersion
                        + " but found " + parent.version());
            }
        }

        @Apply
        BatchChild apply(
                BatchChild child,
                @io.fluxzero.sdk.tracking.handling.Association("children")
                BatchParent parent) {
            return new BatchChild(
                    child.id(), child.parentId(), parent.version());
        }
    }

    @Model
    private record UnrelatedBatchModel(
            @EntityId String id) {
    }

    private record CreateUnrelatedBatchModel(
            String id) {
        @Apply
        UnrelatedBatchModel apply() {
            return new UnrelatedBatchModel(id);
        }
    }

    @Model(commitPolicy = ModelCommitPolicy.ASYNC_AFTER_BATCH)
    private record StagedAfterBatchModel(
            @EntityId String id,
            int version) {
    }

    private record IncrementStagedAfterBatch(
            String id,
            int expectedVersion) {
        @AssertLegal
        void assertVersion(StagedAfterBatchModel model) {
            BATCH_INCREMENT_OBSERVATIONS.add(model.version());
            if (model.version() != expectedVersion) {
                throw new IllegalStateException(
                        "Expected model version " + expectedVersion
                        + " but found " + model.version());
            }
        }

        @Apply
        StagedAfterBatchModel apply(StagedAfterBatchModel model) {
            return new StagedAfterBatchModel(
                    id, model.version() + 1);
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

    @Model(persistence = ModelPersistence.EVENT_SOURCED_WITH_DOCUMENT, materializeGraph = true,
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
            @Parent(pathInParent = "children")
            ProjectionRootId rootId) {
    }

    @Model(persistence = ModelPersistence.EVENT_SOURCED_WITH_DOCUMENT, materializeGraph = true,
            graphProjection = @GraphProjection(
                    collection = "default-graphs"))
    private record DefaultProjectionRoot(
            @EntityId String id) {
    }

    @Model(persistence = ModelPersistence.EVENT_SOURCED_WITH_DOCUMENT, materializeGraph = true,
            graphProjection = @GraphProjection(
                    collection = "async-graphs",
                    completion = GraphProjectionCompletion.ASYNC))
    private record AsyncProjectionRoot(
            @EntityId String id) {
    }

    @Model
    @RegisterType
    private record RegistryKnownModel(
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
