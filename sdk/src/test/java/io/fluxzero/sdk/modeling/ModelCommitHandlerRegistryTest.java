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
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.ModelGraphProjectionStatus;
import io.fluxzero.common.handling.HandlerFilter;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.serialization.DeserializingMessage;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.InterceptApply;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.ModelCommitBatchingClient;
import io.fluxzero.sdk.persisting.repository.DefaultModelRepository;
import io.fluxzero.sdk.persisting.search.DocumentSerializer;
import io.fluxzero.sdk.publishing.DispatchInterceptor;
import io.fluxzero.sdk.tracking.handling.HandlerDecorator;
import io.fluxzero.sdk.tracking.ConsumerConfiguration;
import io.fluxzero.sdk.tracking.Tracker;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class ModelCommitHandlerRegistryTest {
    private static final LinkedBlockingQueue<Integer> BATCH_PARENT_OBSERVATIONS =
            new LinkedBlockingQueue<>();
    private static final LinkedBlockingQueue<Integer> BATCH_INCREMENT_OBSERVATIONS =
            new LinkedBlockingQueue<>();

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
    void explicitAssertAndApplyWithoutLocalApplyAssertsThenWarns() {
        DefaultModelRepository repository =
                mock(DefaultModelRepository.class);
        EventStoreClient eventStoreClient =
                mock(EventStoreClient.class);
        stubModelLoads(repository);
        ModelCommitHandlerRegistry subject =
                subject(repository, eventStoreClient);
        Logger logger = (Logger) LoggerFactory.getLogger(
                ModelCommitHandlerRegistry.class);
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
                mock(ModelCommitBatchingClient.ModelCommitBatch.class);
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
        CountDownLatch finishHandlerIteration = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            CompletableFuture<Void> batch = CompletableFuture.runAsync(() ->
                    DeserializingMessage.forEachInBatch(
                            List.of(message(new TimingCreateCommand("timed"))),
                            current -> {
                                handlingStarted.complete(
                                        subject.handle(current).orElseThrow());
                                try {
                                    finishHandlerIteration.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(e);
                                }
                            }), executor);

            CommitModels commit = commitPrepared.get(5, TimeUnit.SECONDS);
            CompletableFuture<Object> handlingResult = handlingStarted.get(5, TimeUnit.SECONDS);
            assertFalse(transportFlushed.isDone());
            assertFalse(batch.isDone());
            assertFalse(handlingResult.isDone());
            verify(eventStoreClient, never()).commitModels(any());

            finishHandlerIteration.countDown();
            transportFlushed.get(5, TimeUnit.SECONDS);
            assertFalse(batch.isDone());

            commitResponse.complete(CommitModelsResult.acceptedSingleTarget(
                    commit.getRequestId(), commit.getCommitId(), 1L, 1L,
                    "timed", 0L, true));
            batch.get(5, TimeUnit.SECONDS);
            handlingResult.get(5, TimeUnit.SECONDS);
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
                mock(ModelCommitBatchingClient.ModelCommitBatch.class);
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
        assertTrue(ModelTargetResolver.resolve(
                new UpdateBatchChild(childId, 1),
                ModelMetadata.of(UpdateBatchChild.class).handlerMethods())
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void stubBatchModelLoads(
            DefaultModelRepository repository,
            Map<String, Object> durable) {
        when(repository.loadContext(
                any(ModelTargetResolver.Resolution.class),
                nullable(Long.class), anyMap()))
                .thenAnswer(invocation -> {
                    ModelTargetResolver.Resolution resolution = invocation.getArgument(0);
                    Map<String, Object> staged = invocation.getArgument(2);
                    LinkedHashMap<String, ModelTargetResolver.ResolvedModel> targets =
                            new LinkedHashMap<>();
                    resolution.models().forEach(target ->
                            ModelTargetResolver.merge(targets, target));
                    for (ModelTargetResolver.AncestorDependency dependency :
                            resolution.ancestorDependencies()) {
                        for (ModelTargetResolver.ResolvedModel target :
                                resolution.models()) {
                            Object child = staged.containsKey(target.modelId())
                                    ? staged.get(target.modelId())
                                    : durable.get(target.modelId());
                            if (child == null) {
                                continue;
                            }
                            for (ModelMetadata.ParentReference parent :
                                    ModelMetadata.validate(child.getClass()).parentReferences()) {
                                if (parent.parentModelType() == null
                                    || !dependency.modelType().isAssignableFrom(
                                            parent.parentModelType())) {
                                    continue;
                                }
                                Object parentId = parent.read(child);
                                if (parentId != null) {
                                    ModelTargetResolver.merge(
                                            targets,
                                            new ModelTargetResolver.ResolvedModel(
                                            parentId.toString(),
                                            dependency.modelType(),
                                            ModelTargetResolver.Access.READ_ONLY,
                                            dependency.association() == null
                                                    ? List.of()
                                                    : List.of(dependency.association())));
                                }
                            }
                        }
                    }
                    LinkedHashMap<String, Entity<?>> loaded = new LinkedHashMap<>();
                    for (ModelTargetResolver.ResolvedModel target : targets.values()) {
                        Object value = staged.containsKey(target.modelId())
                                ? staged.get(target.modelId())
                                : durable.get(target.modelId());
                        loaded.put(target.modelId(),
                                   ImmutableModelRoot.<Object>builder()
                                           .id(target.modelId())
                                           .type((Class<Object>) target.modelType())
                                           .idProperty(ModelMetadata.of(target.modelType())
                                                   .entityId().orElseThrow().name())
                                           .value(value)
                                           .sequenceNumber(value == null ? -1L : 0L)
                                           .stateIndex(0L)
                                           .build());
                    }
                    return ModelCommitContext.create(
                            0L,
                            resolution.withResolvedModels(
                                    List.copyOf(targets.values())),
                            loaded);
                });
        when(repository.beginLocalCommit(any())).thenReturn(() -> {
        });
        doAnswer(invocation -> {
            List<DefaultModelRepository.CommittedModel> committed = invocation.getArgument(0);
            committed.forEach(model -> durable.put(
                    model.modelId(),
                    model.revisions().getLast().value()));
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

    private record CrossApplicationCommand(String id) {
        @AssertLegal
        void validate() {
            if (id.isBlank()) {
                throw new IllegalArgumentException(
                        "ID must not be blank");
            }
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
            @ParentId(path = "children") BatchParentId parentId,
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
