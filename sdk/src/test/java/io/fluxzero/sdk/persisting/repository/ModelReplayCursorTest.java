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

package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.SerializedObject;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.ModelCommitStep;
import io.fluxzero.common.api.modeling.ModelCommitTarget;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.GetModelGraphResult;
import io.fluxzero.common.api.modeling.ModelEventMembership;
import io.fluxzero.common.api.modeling.ModelEventPayload;
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.common.api.modeling.TrackModelUpdatesResult;
import io.fluxzero.common.caching.NoOpCache;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.Message;
import io.fluxzero.sdk.common.ThreadLocalContext;
import io.fluxzero.sdk.configuration.DefaultFluxzero;
import io.fluxzero.sdk.configuration.client.LocalClient;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.modeling.CommitAttempt;
import io.fluxzero.sdk.modeling.DocumentProjection;
import io.fluxzero.sdk.modeling.EntityHelper;
import io.fluxzero.sdk.modeling.EntityId;
import io.fluxzero.sdk.modeling.Graph;
import io.fluxzero.sdk.modeling.ImmutableModelRoot;
import io.fluxzero.sdk.modeling.Model;
import io.fluxzero.sdk.modeling.ModelPersistence;
import io.fluxzero.sdk.modeling.MutationPlan;
import io.fluxzero.sdk.persisting.caching.DefaultCache;
import io.fluxzero.sdk.persisting.eventsourcing.Apply;
import io.fluxzero.sdk.persisting.eventsourcing.EventSourcingException;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.LocalEventStoreClient;
import io.fluxzero.sdk.persisting.search.Searchable;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelReplayCursorTest {

    private static final ThreadLocal<String> replayContextMarker = ThreadLocalContext.create();

    @Test
    void parallelReplayPreservesApplicationAndParticipatingContext() {
        verifyParallelReplayContext(false);
    }

    @Test
    void parallelReplayCorrectsContextChangedByAnEarlierModelInTheSameChunk() {
        verifyParallelReplayContext(true);
    }

    private void verifyParallelReplayContext(boolean changeContext) {
        AtomicReference<Fluxzero> expectedApplication = new AtomicReference<>();
        AtomicInteger inspectedPayloads = new AtomicInteger();
        JacksonSerializer serializer = new JacksonSerializer() {
            @Override
            public Class<?> serializedClassWithoutUpcasting(SerializedObject<?> serializedObject) {
                if (ReplayContextCreated.class.getName().equals(serializedObject.getType())) {
                    assertSame(expectedApplication.get(), Fluxzero.get());
                    assertEquals("replay-context", replayContextMarker.get());
                    inspectedPayloads.incrementAndGet();
                }
                return super.serializedClassWithoutUpcasting(serializedObject);
            }
        };
        Instant now = Instant.parse("2026-09-05T12:00:00Z");
        try (Fluxzero application = DefaultFluxzero.builder()
                .disableKeepalive().disableShutdownHook()
                .replaceSerializer(serializer).build(LocalClient.newInstance(null))) {
            expectedApplication.set(application);
            application.withClock(Clock.fixed(now, ZoneOffset.UTC));
            List<String> ids = IntStream.range(0, 64).mapToObj(index -> "context-" + index).toList();
            List<ModelCommitStep> steps = ids.stream().map(id -> ModelCommitStep.builder()
                    .event(new Message(new ReplayContextCreated(id, changeContext)).serialize(serializer))
                    .targets(List.of(ModelCommitTarget.builder()
                                             .modelId(id).modelType(ReplayContextModel.class.getSimpleName())
                                             .storeEvent(true).updateState(true).relationships(List.of()).build()))
                    .build()).toList();
            application.client().getEventStoreClient().commitModels(new CommitModels(
                    "replay-context", -1L, ids, steps, ModelConflictPolicy.ACCEPT,
                    Guarantee.STORED, true)).join();
            replayContextMarker.set("replay-context");
            Runnable replay = () -> application.execute(current -> {
                // The smaller load is a control for the existing sequential path.
                for (int size : changeContext ? List.of(64) : List.of(31, 64)) {
                    var loaded = current.modelRepository().loadAll(ids.subList(0, size), ReplayContextModel.class);
                    assertEquals(size, loaded.size());
                    for (var model : loaded) {
                        assertEquals(now, model.get().timestamp());
                        assertEquals("replay-context", model.get().context());
                    }
                    assertSame(application, Fluxzero.get());
                    assertEquals("replay-context", replayContextMarker.get());
                }
            });
            if (changeContext) {
                // Guarantee multiple models per chunk independently of the test machine's processor count.
                try (ForkJoinPool pool = new ForkJoinPool(2)) {
                    pool.submit(ThreadLocalContext.capture().wrap(replay)).join();
                }
            } else {
                replay.run();
            }
            assertTrue(inspectedPayloads.get() >= (changeContext ? 64 : 95));
        } finally {
            replayContextMarker.remove();
        }
    }

    @Test
    void directReplayWithHistoricalFallbackKeepsSessionViewsSequential() {
        JacksonSerializer serializer = new JacksonSerializer();
        List<String> ids = IntStream.range(0, 64).mapToObj(index -> "historical-" + index).toList();
        AtomicInteger historicalReads = new AtomicInteger();
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            if (Long.valueOf(-1L).equals(request.getBoundary().stateIndex())) {
                historicalReads.incrementAndGet();
                return emptyResponse(request, -1L);
            }
            List<ModelEventPayload> payloads = new ArrayList<>();
            List<ModelEventStream> streams = new ArrayList<>();
            for (var requested : request.getRequests()) {
                String id = requested.getModelId();
                int ordinal = ids.indexOf(id);
                long first = ordinal * 2L;
                List<ModelEventMembership> memberships = new ArrayList<>();
                for (int revision = 0; revision < 2; revision++) {
                    if (revision <= requested.getLastSequenceNumber()) {
                        continue;
                    }
                    long stateIndex = first + revision;
                    payloads.add(new ModelEventPayload(stateIndex,
                                                       new Message(new HistoricalReplace(id, revision))
                                                               .serialize(serializer)));
                    // Stored histories supplied by an EventStoreClient may require an earlier view even for
                    // direct applies. Different commits deliberately do not qualify for same-commit reuse.
                    memberships.add(new ModelEventMembership(
                            revision, stateIndex, -1L, "replace-" + stateIndex, 0));
                }
                streams.add(new ModelEventStream(id,
                                                 new ModelHeadState(id, HistoricalModel.class.getSimpleName(),
                                                                    1L, first + 1L, true, false), memberships));
            }
            return new GetModelEventsResult(request.getRequestId(), 127L, payloads, streams);
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client, serializer, mock(EntityHelper.class), new MutationPlan.Compiler(List.of()),
                NoOpCache.INSTANCE, null, null, mock(ModelRepository.class));
        List<MutationPlan.ResolvedModel> targets = ids.stream()
                .map(id -> new MutationPlan.ResolvedModel(
                        id, HistoricalModel.class, MutationPlan.Access.READ_ONLY, List.of("id"))).toList();

        var loaded = loader.session().reconstruct(targets, ModelReadBoundary.current());

        assertEquals(64, historicalReads.get());
        for (var entity : loaded.entities().values()) {
            HistoricalModel value = (HistoricalModel) entity.get();
            assertEquals(1, value.revision());
            assertEquals(Thread.currentThread().getName(), value.replayThread());
        }
    }

    @Test
    void eventRefreshStagesItsCacheWriteUntilTheTrackerCanCheckEntryIdentity() {
        JacksonSerializer serializer = new JacksonSerializer();
        LocalClient client = LocalClient.newInstance(null);
        try (DefaultCache cache = new DefaultCache()) {
            String id = "deferred-replay";
            client.getEventStoreClient().commitModels(new CommitModels(
                    "deferred", -1L, List.of(id),
                    List.of(ModelCommitStep.builder()
                                    .event(new Message(new CachedCreated(id)).serialize(serializer))
                                    .targets(List.of(ModelCommitTarget.builder().modelId(id)
                                                             .modelType(CachedReplayModel.class.getSimpleName())
                                                             .storeEvent(true).updateState(true)
                                                             .relationships(List.of()).build())).build()),
                    ModelConflictPolicy.ACCEPT, Guarantee.STORED, true)).join();
            ModelReplayCursor cursor = new ModelReplayCursor(
                    client.getEventStoreClient(), serializer, mock(EntityHelper.class),
                    new MutationPlan.Compiler(List.of()), cache, null, null, null);

            ModelCacheTracker.RefreshedBatch refreshed = cursor.refresh(Map.of(id, CachedReplayModel.class), 0L);

            assertNull(cache.get(id));
            assertEquals(new CachedReplayModel(id), refreshed.cacheUpdates().get(id).get());
            // Ordinary reconstruction keeps its existing immediate cache publication path.
            cursor.session().reconstruct(List.of(new MutationPlan.ResolvedModel(
                    id, CachedReplayModel.class, MutationPlan.Access.READ_ONLY, List.of("id"))),
                                         ModelReadBoundary.current());
            assertEquals(new CachedReplayModel(id), ((io.fluxzero.sdk.modeling.Entity<?>) cache.get(id)).get());
        } finally {
            client.shutDown();
        }
    }

    @Test
    void eventRefreshStagesMissingHeadRemovalUntilTheTrackerCanCheckEntryIdentity() {
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelEvents(any())).thenAnswer(invocation -> emptyResponse(invocation.getArgument(0), 1L));
        try (DefaultCache cache = new DefaultCache()) {
            String id = "deleted-replay";
            var current = ImmutableModelRoot.<CachedReplayModel>builder().id(id).type(CachedReplayModel.class)
                    .value(new CachedReplayModel(id)).stateIndex(0L).sequenceNumber(0L).build();
            cache.put(id, current);
            ModelReplayCursor cursor = new ModelReplayCursor(
                    client, new JacksonSerializer(), mock(EntityHelper.class),
                    new MutationPlan.Compiler(List.of()), cache, null, null, null);

            ModelCacheTracker.RefreshedBatch refreshed = cursor.refresh(Map.of(id, CachedReplayModel.class), 1L);

            assertSame(current, cache.get(id));
            assertTrue(refreshed.cacheUpdates().containsKey(id));
            assertNull(refreshed.cacheUpdates().get(id));
        }
    }

    @Model
    private record CachedReplayModel(@EntityId String id) {
        @Apply
        static CachedReplayModel create(CachedCreated event) {
            return new CachedReplayModel(event.id());
        }
    }

    private record CachedCreated(String id) {
    }

    @Model(cached = false)
    private record HistoricalModel(@EntityId String id, int revision, String replayThread) {
        @Apply
        static HistoricalModel replace(HistoricalReplace event) {
            return new HistoricalModel(event.id(), event.revision(), Thread.currentThread().getName());
        }
    }

    private record HistoricalReplace(String id, int revision) {
    }

    @Model(cached = false)
    private record ReplayContextModel(@EntityId String id, Instant timestamp, String context) {
        @Apply
        static ReplayContextModel create(ReplayContextCreated event) {
            ReplayContextModel model = new ReplayContextModel(
                    event.id(), Fluxzero.currentTime(), replayContextMarker.get());
            if (event.changeContext()) {
                replayContextMarker.set("changed-by-" + event.id());
            }
            return model;
        }
    }

    private record ReplayContextCreated(String id, boolean changeContext) {
    }

    @Test
    void exactContextUsesUnchangedAuthoritativeDocumentWhenHistoryIsIncomplete() {
        String modelId = "incomplete-document";
        long boundary = 42L;
        ModelHeadState head = new ModelHeadState(
                modelId, CurrentDocument.class.getSimpleName(),
                1L, 40L, false, false);
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            return new GetModelEventsResult(
                    request.getRequestId(), boundary, List.of(),
                    List.of(new ModelEventStream(modelId, head, List.of())));
        });
        ModelReplayCursor.DocumentReader documentReader = mock(
                ModelReplayCursor.DocumentReader.class);
        CurrentDocument value = new CurrentDocument(modelId, "current");
        when(documentReader.load(modelId, CurrentDocument.class, false))
                .thenReturn(new ModelReplayCursor.DocumentVersion(
                        ImmutableModelRoot.initial(
                                modelId, CurrentDocument.class, "id", value),
                        head));
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new JacksonSerializer(), null, null, null, null,
                documentReader, mock(ModelRepository.class),
                ModelReplayCursor.EventBoundaryBarrier.NONE, currentDocumentTypes());
        MutationPlan.Resolution resolution = new MutationPlan.Resolution(
                List.of(new MutationPlan.ResolvedModel(
                        modelId, CurrentDocument.class,
                        MutationPlan.Access.READ_WRITE, List.of("id"))),
                List.of());

        CommitAttempt context = loader.context(
                resolution, ModelReadBoundary.state(boundary, false),
                Map.of(), null, null, true, false);

        assertEquals(boundary, context.readStateIndex());
        assertEquals(value, context.entity(modelId).get());
    }

    @Test
    void exactContextRejectsAuthoritativeDocumentThatMovedPastIncompleteHistory() {
        String modelId = "moved-incomplete-document";
        long boundary = 42L;
        ModelHeadState historicalHead = new ModelHeadState(
                modelId, CurrentDocument.class.getSimpleName(),
                1L, 40L, false, false);
        ModelHeadState currentHead = new ModelHeadState(
                modelId, CurrentDocument.class.getSimpleName(),
                2L, 43L, false, false);
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            return new GetModelEventsResult(
                    request.getRequestId(), boundary, List.of(),
                    List.of(new ModelEventStream(
                            modelId, historicalHead, List.of())));
        });
        ModelReplayCursor.DocumentReader documentReader = mock(
                ModelReplayCursor.DocumentReader.class);
        when(documentReader.load(modelId, CurrentDocument.class, false))
                .thenReturn(new ModelReplayCursor.DocumentVersion(
                        ImmutableModelRoot.initial(
                                modelId, CurrentDocument.class, "id",
                                new CurrentDocument(modelId, "moved")),
                        currentHead));
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new JacksonSerializer(), null, null, null, null,
                documentReader, mock(ModelRepository.class),
                ModelReplayCursor.EventBoundaryBarrier.NONE, currentDocumentTypes());
        MutationPlan.Resolution resolution = new MutationPlan.Resolution(
                List.of(new MutationPlan.ResolvedModel(
                        modelId, CurrentDocument.class,
                        MutationPlan.Access.READ_WRITE, List.of("id"))),
                List.of());

        EventSourcingException failure = assertThrows(
                EventSourcingException.class,
                () -> loader.context(
                        resolution, ModelReadBoundary.state(boundary, false),
                        Map.of(), null, null, true, false));

        assertTrue(failure.getMessage().contains(
                "does not match incomplete historical head"));
    }

    @Test
    void acceptRebaseAdvancesToMovedAuthoritativeDocument() {
        String modelId = "advanced-incomplete-document";
        long requestedBoundary = 42L;
        long currentBoundary = 43L;
        ModelHeadState historicalHead = new ModelHeadState(
                modelId, CurrentDocument.class.getSimpleName(),
                1L, 40L, false, false);
        ModelHeadState currentHead = new ModelHeadState(
                modelId, CurrentDocument.class.getSimpleName(),
                2L, currentBoundary, false, false);
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            long boundary = request.getBoundary().stateIndex();
            ModelHeadState head = boundary == requestedBoundary
                    ? historicalHead : currentHead;
            return new GetModelEventsResult(
                    request.getRequestId(), boundary, List.of(),
                    List.of(new ModelEventStream(modelId, head, List.of())));
        });
        ModelReplayCursor.DocumentReader documentReader = mock(
                ModelReplayCursor.DocumentReader.class);
        CurrentDocument value = new CurrentDocument(modelId, "current");
        when(documentReader.load(modelId, CurrentDocument.class, false))
                .thenReturn(new ModelReplayCursor.DocumentVersion(
                        ImmutableModelRoot.initial(
                                modelId, CurrentDocument.class, "id", value),
                        currentHead));
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new JacksonSerializer(), null, null, null, null,
                documentReader, mock(ModelRepository.class),
                ModelReplayCursor.EventBoundaryBarrier.NONE, currentDocumentTypes());
        MutationPlan.Resolution resolution = new MutationPlan.Resolution(
                List.of(new MutationPlan.ResolvedModel(
                        modelId, CurrentDocument.class,
                        MutationPlan.Access.READ_WRITE, List.of("id"))),
                List.of());

        CommitAttempt context = loader.context(
                resolution, ModelReadBoundary.state(requestedBoundary, false),
                Map.of(), null, null, true, false, true);

        assertEquals(currentBoundary, context.readStateIndex());
        assertEquals(value, context.entity(modelId).get());
        verify(client, times(2)).getModelEvents(any());
    }

    @Test
    void acceptRebaseWaitsForLaggingAuthoritativeDocument() {
        String modelId = "lagging-incomplete-document";
        long boundary = 42L;
        ModelHeadState head = new ModelHeadState(
                modelId, CurrentDocument.class.getSimpleName(),
                1L, 40L, false, false);
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            return new GetModelEventsResult(
                    request.getRequestId(), boundary, List.of(),
                    List.of(new ModelEventStream(modelId, head, List.of())));
        });
        when(client.trackModelUpdates(any())).thenReturn(
                CompletableFuture.completedFuture(
                        new TrackModelUpdatesResult(
                                0L, boundary, boundary,
                                boundary, List.of())));
        ModelReplayCursor.DocumentReader documentReader = mock(
                ModelReplayCursor.DocumentReader.class);
        CurrentDocument value = new CurrentDocument(modelId, "current");
        when(documentReader.load(modelId, CurrentDocument.class, false))
                .thenReturn(new ModelReplayCursor.DocumentVersion(
                                ImmutableModelRoot.initial(
                                        modelId, CurrentDocument.class, "id", null),
                                null),
                            new ModelReplayCursor.DocumentVersion(
                                    ImmutableModelRoot.initial(
                                            modelId, CurrentDocument.class, "id", value),
                                    head));
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new JacksonSerializer(), null, null, null, null,
                documentReader, mock(ModelRepository.class),
                ModelReplayCursor.EventBoundaryBarrier.NONE, currentDocumentTypes());
        MutationPlan.Resolution resolution = new MutationPlan.Resolution(
                List.of(new MutationPlan.ResolvedModel(
                        modelId, CurrentDocument.class,
                        MutationPlan.Access.READ_WRITE, List.of("id"))),
                List.of());

        CommitAttempt context = loader.context(
                resolution, ModelReadBoundary.state(boundary, false),
                Map.of(), null, null, true, false, true);

        assertEquals(boundary, context.readStateIndex());
        assertEquals(value, context.entity(modelId).get());
        verify(client, times(2)).getModelEvents(any());
        verify(documentReader, times(2)).load(
                modelId, CurrentDocument.class, false);
        verify(client).trackModelUpdates(any());
    }

    @Test
    void retriesCurrentGraphWhenADocumentAdvancesDuringReconstruction() {
        String modelId = "current-document";
        ModelHeadState firstHead = documentHead(modelId, 0L, 1L);
        ModelHeadState currentHead = documentHead(modelId, 1L, 2L);
        ModelHeadState currentDocumentHead = directDocumentHead(modelId, 1L, 2L);
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelGraph(any())).thenReturn(
                graphResponse(modelId, firstHead, 1L),
                graphResponse(modelId, currentHead, 2L));
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            long stateIndex = request.getBoundary().stateIndex();
            ModelHeadState head = stateIndex == 1L
                    ? directDocumentHead(modelId, 0L, 1L)
                    : directDocumentHead(modelId, 1L, 2L);
            return new GetModelEventsResult(
                    request.getRequestId(), stateIndex, List.of(),
                    List.of(new ModelEventStream(modelId, head, List.of())));
        });
        ModelReplayCursor.DocumentReader documentReader = mock(
                ModelReplayCursor.DocumentReader.class);
        when(documentReader.load(modelId, CurrentDocument.class, false))
                .thenReturn(new ModelReplayCursor.DocumentVersion(
                        ImmutableModelRoot.initial(
                                modelId, CurrentDocument.class, "id",
                                new CurrentDocument(modelId, "current")),
                        currentDocumentHead));
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new JacksonSerializer(), null, null, null, null,
                documentReader, mock(ModelRepository.class),
                ModelReplayCursor.EventBoundaryBarrier.NONE, currentDocumentTypes());

        Graph<CurrentDocument> result = loader.graph(
                modelId, CurrentDocument.class, Graph.Options.DEFAULT,
                ModelReadBoundary.current(), "test", Map.of());

        assertEquals(new CurrentDocument(modelId, "current"), result.get());
        assertEquals(2L, result.stateIndex());
        verify(client, times(2)).getModelGraph(any());
    }

    @Test
    void exactGraphUsesUnchangedAuthoritativeDocumentWhenHistoryIsIncomplete() {
        String modelId = "exact-document";
        long boundary = 42L;
        ModelHeadState head = directDocumentHead(modelId, 2L, boundary);
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelGraph(any())).thenReturn(
                graphResponse(modelId, head, boundary));
        ModelReplayCursor.DocumentReader documentReader = mock(
                ModelReplayCursor.DocumentReader.class);
        CurrentDocument value = new CurrentDocument(modelId, "current");
        when(documentReader.load(modelId, CurrentDocument.class, false))
                .thenReturn(new ModelReplayCursor.DocumentVersion(
                        ImmutableModelRoot.initial(
                                modelId, CurrentDocument.class, "id", value),
                        head));
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new JacksonSerializer(), null, null, null, null,
                documentReader, mock(ModelRepository.class),
                ModelReplayCursor.EventBoundaryBarrier.NONE, currentDocumentTypes());

        Graph<CurrentDocument> result = loader.graph(
                modelId, CurrentDocument.class, Graph.Options.DEFAULT,
                ModelReadBoundary.at(boundary), "test", Map.of());

        assertEquals(value, result.get());
        assertEquals(boundary, result.stateIndex());
        verify(client, never()).getModelEvents(any());
    }

    @Test
    void exactGraphResolvesAMissingInlineHeadBeforeChoosingDocumentReconstruction() {
        String modelId = "exact-document-with-deferred-head";
        long boundary = 42L;
        ModelHeadState head = directDocumentHead(modelId, 2L, boundary);
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelGraph(any())).thenReturn(
                graphResponse(modelId, null, boundary));
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            return new GetModelEventsResult(
                    request.getRequestId(), boundary, List.of(),
                    List.of(new ModelEventStream(modelId, head, List.of())));
        });
        ModelReplayCursor.DocumentReader documentReader = mock(
                ModelReplayCursor.DocumentReader.class);
        CurrentDocument value = new CurrentDocument(modelId, "current");
        when(documentReader.load(modelId, CurrentDocument.class, false))
                .thenReturn(new ModelReplayCursor.DocumentVersion(
                        ImmutableModelRoot.initial(
                                modelId, CurrentDocument.class, "id", value),
                        head));
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new JacksonSerializer(), null, null, null, null,
                documentReader, mock(ModelRepository.class),
                ModelReplayCursor.EventBoundaryBarrier.NONE, currentDocumentTypes());

        Graph<CurrentDocument> result = loader.graph(
                modelId, CurrentDocument.class, Graph.Options.DEFAULT,
                ModelReadBoundary.at(boundary), "test", Map.of());

        assertEquals(value, result.get());
        assertEquals(boundary, result.stateIndex());
        verify(client).getModelEvents(any());
    }

    @Test
    void exactGraphUsesTheDurableDocumentHeadInsteadOfAReplayableInlineStreamHead() {
        String modelId = "exact-document-with-event-stream-head";
        long boundary = 42L;
        ModelHeadState streamHead = documentHead(modelId, -1L, boundary);
        ModelHeadState documentHead = directDocumentHead(modelId, 2L, boundary);
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelGraph(any())).thenReturn(
                graphResponse(modelId, streamHead, boundary));
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            return new GetModelEventsResult(
                    request.getRequestId(), boundary, List.of(),
                    List.of(new ModelEventStream(
                            modelId, documentHead, List.of())));
        });
        ModelReplayCursor.DocumentReader documentReader = mock(
                ModelReplayCursor.DocumentReader.class);
        CurrentDocument value = new CurrentDocument(modelId, "current");
        when(documentReader.load(modelId, CurrentDocument.class, false))
                .thenReturn(new ModelReplayCursor.DocumentVersion(
                        ImmutableModelRoot.initial(
                                modelId, CurrentDocument.class, "id", value),
                        documentHead));
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new JacksonSerializer(), null, null, null, null,
                documentReader, mock(ModelRepository.class),
                ModelReplayCursor.EventBoundaryBarrier.NONE, currentDocumentTypes());

        Graph<CurrentDocument> result = loader.graph(
                modelId, CurrentDocument.class, Graph.Options.DEFAULT,
                ModelReadBoundary.at(boundary), "test", Map.of());

        assertEquals(value, result.get());
        assertEquals(boundary, result.stateIndex());
        verify(client).getModelEvents(any());
    }

    @Test
    void exactGraphWaitsForLaggingAuthoritativeDocument() {
        String modelId = "lagging-exact-document";
        long boundary = 42L;
        ModelHeadState head = directDocumentHead(modelId, 2L, boundary);
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelGraph(any())).thenReturn(
                graphResponse(modelId, head, boundary));
        when(client.trackModelUpdates(any())).thenReturn(
                CompletableFuture.completedFuture(
                        new TrackModelUpdatesResult(
                                0L, boundary, boundary,
                                boundary, List.of())));
        ModelReplayCursor.DocumentReader documentReader = mock(
                ModelReplayCursor.DocumentReader.class);
        CurrentDocument value = new CurrentDocument(modelId, "current");
        when(documentReader.load(modelId, CurrentDocument.class, false))
                .thenReturn(
                        new ModelReplayCursor.DocumentVersion(
                                ImmutableModelRoot.initial(
                                        modelId, CurrentDocument.class, "id", null),
                                null),
                        new ModelReplayCursor.DocumentVersion(
                                ImmutableModelRoot.initial(
                                        modelId, CurrentDocument.class, "id", value),
                                head));
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new JacksonSerializer(), null, null, null, null,
                documentReader, mock(ModelRepository.class),
                ModelReplayCursor.EventBoundaryBarrier.NONE, currentDocumentTypes());

        Graph<CurrentDocument> result = loader.graph(
                modelId, CurrentDocument.class, Graph.Options.DEFAULT,
                ModelReadBoundary.at(boundary), "test", Map.of());

        assertEquals(value, result.get());
        verify(documentReader, times(2)).load(
                modelId, CurrentDocument.class, false);
        verify(client).trackModelUpdates(any());
        verify(client, never()).getModelEvents(any());
    }

    @Test
    void exactGraphRejectsAuthoritativeDocumentThatMovedPastIncompleteHistory() {
        String modelId = "moved-exact-document";
        long boundary = 42L;
        ModelHeadState historicalHead = directDocumentHead(modelId, 1L, boundary);
        ModelHeadState currentHead = directDocumentHead(modelId, 2L, boundary + 1L);
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelGraph(any())).thenReturn(
                graphResponse(modelId, historicalHead, boundary));
        ModelReplayCursor.DocumentReader documentReader = mock(
                ModelReplayCursor.DocumentReader.class);
        when(documentReader.load(modelId, CurrentDocument.class, false))
                .thenReturn(new ModelReplayCursor.DocumentVersion(
                        ImmutableModelRoot.initial(
                                modelId, CurrentDocument.class, "id",
                                new CurrentDocument(modelId, "moved")),
                        currentHead));
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new JacksonSerializer(), null, null, null, null,
                documentReader, mock(ModelRepository.class),
                ModelReplayCursor.EventBoundaryBarrier.NONE, currentDocumentTypes());

        EventSourcingException failure = assertThrows(
                EventSourcingException.class,
                () -> loader.graph(
                        modelId, CurrentDocument.class, Graph.Options.DEFAULT,
                        ModelReadBoundary.at(boundary), "test", Map.of()));

        assertTrue(failure.getMessage().contains(
                "moved while reconstructing graph boundary"));
        verify(client, never()).getModelEvents(any());
    }

    @Test
    void boundsCurrentGraphRetriesWhenADocumentKeepsAdvancing() {
        String modelId = "moving-document";
        ModelHeadState graphHead = documentHead(modelId, 0L, 1L);
        ModelHeadState currentHead = documentHead(modelId, 1L, 2L);
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelGraph(any())).thenReturn(
                graphResponse(modelId, graphHead, 1L));
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            return new GetModelEventsResult(
                    request.getRequestId(), 1L, List.of(),
                    List.of(new ModelEventStream(
                            modelId, directDocumentHead(modelId, 0L, 1L), List.of())));
        });
        ModelReplayCursor.DocumentReader documentReader = mock(
                ModelReplayCursor.DocumentReader.class);
        when(documentReader.load(modelId, CurrentDocument.class, false))
                .thenReturn(new ModelReplayCursor.DocumentVersion(
                        ImmutableModelRoot.initial(
                                modelId, CurrentDocument.class, "id",
                                new CurrentDocument(modelId, "current")),
                        currentHead));
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new JacksonSerializer(), null, null, null, null,
                documentReader, mock(ModelRepository.class),
                ModelReplayCursor.EventBoundaryBarrier.NONE, currentDocumentTypes());

        assertThrows(
                EventSourcingException.class,
                () -> loader.graph(
                        modelId, CurrentDocument.class, Graph.Options.DEFAULT,
                        ModelReadBoundary.current(), "test", Map.of()));
        verify(client, times(8)).getModelGraph(any());
    }

    private static GetModelGraphResult graphResponse(
            String modelId,
            ModelHeadState head,
            long stateIndex) {
        return new GetModelGraphResult(
                0L, List.of(),
                new GetModelEventsResult(
                        0L, stateIndex, List.of(),
                        List.of(new ModelEventStream(
                                modelId, head, List.of()))));
    }

    private static ModelHeadState documentHead(
            String modelId,
            long sequenceNumber,
            long stateIndex) {
        return new ModelHeadState(
                modelId, CurrentDocument.class.getSimpleName(),
                sequenceNumber, stateIndex, true, false);
    }

    private static ModelHeadState directDocumentHead(
            String modelId,
            long sequenceNumber,
            long stateIndex) {
        return new ModelHeadState(
                modelId, CurrentDocument.class.getSimpleName(),
                sequenceNumber, stateIndex, false, false);
    }

    private static ModelTypeResolver currentDocumentTypes() {
        return new ModelTypeResolver() {
            @Override
            public String modelName(Class<?> modelType) {
                return modelType.getSimpleName();
            }

            @Override
            public Class<?> modelType(String modelName, String modelId) {
                if (!CurrentDocument.class.getSimpleName().equals(modelName)) {
                    throw new IllegalStateException(
                            "Unknown Model type " + modelName + " for " + modelId);
                }
                return CurrentDocument.class;
            }
        };
    }

    @Model(persistence = ModelPersistence.DOCUMENT, document = @DocumentProjection(collection = "currentDocuments"))
    private record CurrentDocument(
            @EntityId String id,
            String value) {
    }

    @Test
    void chunksModelIdsAndPinsEveryLaterChunkToTheFirstResponse() {
        EventStoreClient client = mock(EventStoreClient.class);
        List<GetModelEvents> requests = new ArrayList<>();
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            requests.add(request);
            return emptyResponse(request, 42L);
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(2, 8, 4, 1_024L));
        List<GetModelEventsResult> pages = new ArrayList<>();

        long stateIndex = loader.load(
                List.of("a", "b", "c"), null,
                page -> pages.add(page.response()));

        assertEquals(42L, stateIndex);
        assertEquals(2, requests.size());
        assertNull(requests.getFirst().getBoundary().stateIndex());
        assertEquals(42L, requests.getLast().getBoundary().stateIndex());
        assertEquals(List.of("a", "b"), requests.getFirst().getRequests().stream()
                .map(request -> request.getModelId()).toList());
        assertEquals(List.of("c"), requests.getLast().getRequests().stream()
                .map(request -> request.getModelId()).toList());
        assertEquals(1_024L, requests.getFirst().getMaxBytes());
        assertEquals(2, pages.size());
    }

    @Test
    void eventBoundaryIsResolvedOnlyByTheFirstChunk() {
        EventStoreClient client = mock(EventStoreClient.class);
        List<GetModelEvents> requests = new ArrayList<>();
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            requests.add(request);
            return emptyResponse(request, 42L);
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client,
                new ModelReplayCursor.Settings(
                        2, 8, 4, 1_024L));
        LinkedHashMap<String, Long> cursors =
                new LinkedHashMap<>();
        cursors.put("a", -1L);
        cursors.put("b", -1L);
        cursors.put("c", -1L);

        var result = loader.load(
                cursors,
                ModelReadBoundary.commit("commit-991", 3),
                ignored -> {
                });

        assertEquals(42L, result.stateIndex());
        assertEquals(
                "commit-991",
                requests.getFirst().getBoundary().commitId());
        assertEquals(
                3,
                requests.getFirst().getBoundary().substep());
        assertNull(requests.getFirst().getBoundary().stateIndex());
        assertNull(requests.getLast().getBoundary().commitId());
        assertNull(requests.getLast().getBoundary().substep());
        assertEquals(42L, requests.getLast().getBoundary().stateIndex());
    }

    @Test
    void retriesAnUnmappedLegacyBoundaryAfterMigrationCatchesUp() {
        EventStoreClient client = mock(EventStoreClient.class);
        AtomicInteger reads = new AtomicInteger();
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            boolean exact = reads.getAndIncrement() > 0;
            return new GetModelEventsResult(
                    request.getRequestId(), exact ? 3L : 7L,
                    exact, List.of(), List.of());
        });
        List<Long> awaited = new ArrayList<>();
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(2, 8, 4, 1_024L),
                eventIndex -> {
                    awaited.add(eventIndex);
                    return true;
                });

        var result = loader.load(
                Map.of(), ModelReadBoundary.eventOrCurrent(42L), ignored -> {
                });

        assertEquals(3L, result.stateIndex());
        assertEquals(2, reads.get());
        assertEquals(List.of(42L), awaited);
    }

    @Test
    void mappedLegacyBoundaryDoesNotConsultMigrationProgress() {
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            return new GetModelEventsResult(
                    request.getRequestId(), 3L,
                    true, List.of(), List.of());
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(2, 8, 4, 1_024L),
                eventIndex -> {
                    throw new AssertionError("Mapped reads must not query migration progress");
                });

        var result = loader.load(
                Map.of(), ModelReadBoundary.eventOrCurrent(42L), ignored -> {
                });

        assertEquals(3L, result.stateIndex());
    }

    @Test
    void failsWhenProcessedLegacyEventStillHasNoModelMapping() {
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            return new GetModelEventsResult(
                    request.getRequestId(), 7L,
                    false, List.of(), List.of());
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(2, 8, 4, 1_024L),
                eventIndex -> true);

        EventSourcingException failure = assertThrows(
                EventSourcingException.class,
                () -> loader.load(
                        Map.of(), ModelReadBoundary.eventOrCurrent(42L), ignored -> {
                        }));

        assertTrue(failure.getMessage().contains("legacy event 42"));
    }

    @Test
    void headOnlyLoadTransfersNoMembershipsAndPinsEveryChunk() {
        EventStoreClient client = mock(EventStoreClient.class);
        List<GetModelEvents> requests = new ArrayList<>();
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            requests.add(request);
            return new GetModelEventsResult(
                    request.getRequestId(), 42L, List.of(),
                    request.getRequests().stream()
                            .map(stream -> new ModelEventStream(
                                    stream.getModelId(),
                                    new ModelHeadState(
                                            stream.getModelId(),
                                            "example.Model",
                                            9L, 41L,
                                            true, false),
                                    List.of()))
                            .toList());
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client,
                new ModelReplayCursor.Settings(
                        2, 8, 4, 1_024L));

        var result = loader.loadHeads(
                List.of("a", "b", "c"),
                ModelReadBoundary.commit("commit-991", 3));

        assertEquals(42L, result.stateIndex());
        assertEquals(List.of("a", "b", "c"),
                     List.copyOf(result.heads().keySet()));
        assertEquals(
                List.of(0, 0, 0),
                requests.stream()
                        .flatMap(request ->
                                         request.getRequests().stream())
                        .map(request -> request.getMaxSize())
                        .toList());
        assertEquals(
                "commit-991",
                requests.getFirst().getBoundary().commitId());
        assertEquals(
                42L,
                requests.getLast().getBoundary().stateIndex());
    }

    @Test
    void pagesAStreamWithBoundedMembershipsAndKeepsThePinnedBoundary() {
        EventStoreClient client = mock(EventStoreClient.class);
        List<GetModelEvents> requests = new ArrayList<>();
        ModelHeadState head = new ModelHeadState("a", "example.A", 2L, 2L, true, false);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            requests.add(request);
            long sequenceNumber = request.getRequests().getFirst().getLastSequenceNumber() + 1L;
            long stateIndex = sequenceNumber;
            return new GetModelEventsResult(
                    request.getRequestId(), 9L,
                    List.of(new ModelEventPayload(stateIndex, event("event-" + stateIndex))),
                    List.of(new ModelEventStream(
                            "a", head,
                            List.of(new ModelEventMembership(
                                    sequenceNumber, stateIndex,
                                    sequenceNumber == 0L
                                            ? -1L
                                            : sequenceNumber - 1L,
                                    "commit-" + stateIndex, 0)))));
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(4, 1, 1, 16L));

        long stateIndex = loader.load(List.of("a"), null, ignored -> {
        });

        assertEquals(9L, stateIndex);
        assertEquals(List.of(-1L, 0L, 1L), requests.stream()
                .map(request -> request.getRequests().getFirst().getLastSequenceNumber()).toList());
        assertNull(requests.getFirst().getBoundary().stateIndex());
        assertEquals(List.of(9L, 9L), requests.subList(1, 3).stream()
                .map(request -> request.getBoundary().stateIndex()).toList());
        assertEquals(List.of(1, 1, 1), requests.stream()
                .map(request -> request.getRequests().getFirst().getMaxSize()).toList());
    }

    @Test
    void prefetchesExactlyOneFollowingPageWhileApplyingTheCurrentPage() {
        EventStoreClient client = mock(EventStoreClient.class);
        BlockingQueue<GetModelEvents> requested = new LinkedBlockingQueue<>();
        ModelHeadState head = new ModelHeadState(
                "a", "example.A", 2L, 2L, true, false);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            requested.add(request);
            return pageResponse(request, head);
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(4, 1, 1, 16L));
        AtomicInteger applied = new AtomicInteger();

        loader.load(List.of("a"), null, ignored -> {
            switch (applied.getAndIncrement()) {
                case 0 -> {
                    assertEquals(-1L, requestCursor(awaitRequest(requested)));
                    assertEquals(0L, requestCursor(awaitRequest(requested)));
                    assertTrue(requested.isEmpty(), "More than one page was prefetched");
                }
                case 1 -> assertEquals(1L, requestCursor(awaitRequest(requested)));
                case 2 -> assertTrue(requested.isEmpty(), "A page was requested beyond the pinned head");
                default -> throw new AssertionError("Unexpected replay page");
            }
        });

        assertEquals(3, applied.get());
    }

    @Test
    void keepsLocalMultiPageReplayOnTheCallingThread() {
        LocalEventStoreClient client = mock(LocalEventStoreClient.class);
        Thread callingThread = Thread.currentThread();
        ModelHeadState head = new ModelHeadState(
                "a", "example.A", 1L, 1L, true, false);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            assertSame(callingThread, Thread.currentThread());
            return pageResponse(invocation.getArgument(0), head);
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(4, 1, 1, 16L));
        AtomicInteger applied = new AtomicInteger();

        loader.load(List.of("a"), null, ignored -> applied.incrementAndGet());

        assertEquals(2, applied.get());
    }

    @Test
    void cancelsPrefetchWhenApplyingTheCurrentPageFails() throws Exception {
        EventStoreClient client = mock(EventStoreClient.class);
        CountDownLatch prefetchStarted = new CountDownLatch(1);
        CountDownLatch prefetchInterrupted = new CountDownLatch(1);
        CountDownLatch blockPrefetch = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        ModelHeadState head = new ModelHeadState(
                "a", "example.A", 1L, 1L, true, false);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            if (requests.getAndIncrement() == 0) {
                return pageResponse(request, head);
            }
            prefetchStarted.countDown();
            try {
                blockPrefetch.await();
                throw new AssertionError("Cancelled prefetch unexpectedly resumed");
            } catch (InterruptedException expected) {
                prefetchInterrupted.countDown();
                throw new EventSourcingException("Prefetch interrupted", expected);
            }
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(4, 1, 1, 16L));
        IllegalStateException expected = new IllegalStateException("Apply failed");

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> loader.load(List.of("a"), null, ignored -> {
                    await(prefetchStarted);
                    throw expected;
                }));

        assertSame(expected, actual);
        assertTrue(prefetchInterrupted.await(2L, TimeUnit.SECONDS),
                   "The prefetched transport read was not interrupted");
        assertEquals(2, requests.get());
    }

    @Test
    void continuesWhenTheByteBoundAdvancesOnlyOneOfSeveralStreams() {
        EventStoreClient client = mock(EventStoreClient.class);
        AtomicInteger invocation = new AtomicInteger();
        ModelHeadState aHead = new ModelHeadState("a", "example.A", 0L, 0L, true, false);
        ModelHeadState bHead = new ModelHeadState("b", "example.B", 0L, 1L, true, false);
        when(client.getModelEvents(any())).thenAnswer(answer -> {
            GetModelEvents request = answer.getArgument(0);
            boolean first = invocation.getAndIncrement() == 0;
            ModelEventPayload payload = new ModelEventPayload(
                    first ? 0L : 1L, event(first ? "large-a" : "large-b"));
            return new GetModelEventsResult(
                    request.getRequestId(), 7L, List.of(payload),
                    first
                            ? List.of(
                                    new ModelEventStream(
                                            "a", aHead, List.of(new ModelEventMembership(
                                                    0L, 0L, -1L, "commit-a", 0))),
                                    new ModelEventStream("b", bHead, List.of()))
                            : List.of(new ModelEventStream(
                                    "b", bHead, List.of(new ModelEventMembership(
                                            0L, 1L, 0L, "commit-b", 0)))));
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(4, 8, 8, 1L));

        loader.load(List.of("a", "b"), null, ignored -> {
        });

        assertEquals(2, invocation.get());
    }

    @Test
    void rejectsMissingPayloadAndIncompleteHistory() {
        EventStoreClient missingPayloadClient = mock(EventStoreClient.class);
        when(missingPayloadClient.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            return new GetModelEventsResult(
                    request.getRequestId(), 0L, List.of(),
                    List.of(new ModelEventStream(
                            "a", new ModelHeadState("a", "example.A", 0L, 0L, true, false),
                            List.of(new ModelEventMembership(0L, 0L, -1L, "commit", 0)))));
        });
        EventStoreClient incompleteClient = mock(EventStoreClient.class);
        when(incompleteClient.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            return new GetModelEventsResult(
                    request.getRequestId(), 0L, List.of(),
                    List.of(new ModelEventStream(
                            "a", new ModelHeadState("a", "example.A", 0L, 0L, false, false), List.of())));
        });

        assertThrows(
                EventSourcingException.class,
                () -> new ModelReplayCursor(missingPayloadClient)
                        .load(List.of("a"), null, ignored -> {
                        }));
        assertThrows(
                EventSourcingException.class,
                () -> new ModelReplayCursor(incompleteClient)
                        .load(List.of("a"), null, ignored -> {
                        }));
    }

    @Test
    void membershipBudgetAlsoBoundsTheNumberOfStreamsPerChunk() {
        EventStoreClient client = mock(EventStoreClient.class);
        List<GetModelEvents> requests = new ArrayList<>();
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            requests.add(request);
            return emptyResponse(request, 42L);
        });
        ModelReplayCursor loader = new ModelReplayCursor(
                client, new ModelReplayCursor.Settings(4, 3, 2, 1_024L));

        loader.load(List.of("a", "b", "c", "d"), null, ignored -> {
        });

        assertEquals(List.of(3, 1), requests.stream()
                .map(request -> request.getRequests().size()).toList());
    }

    @Test
    void rejectsMembershipBeyondThePinnedHead() {
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            GetModelEvents request = invocation.getArgument(0);
            return new GetModelEventsResult(
                    request.getRequestId(), 1L,
                    List.of(new ModelEventPayload(1L, event("event"))),
                    List.of(new ModelEventStream(
                            "a", new ModelHeadState("a", "example.A", 0L, 0L, true, false),
                            List.of(new ModelEventMembership(0L, 1L, 0L, "commit", 0)))));
        });

        assertThrows(
                EventSourcingException.class,
                () -> new ModelReplayCursor(client)
                        .load(List.of("a"), null, ignored -> {
                        }));
    }

    private static GetModelEventsResult emptyResponse(GetModelEvents request, long stateIndex) {
        return new GetModelEventsResult(
                request.getRequestId(), stateIndex, List.of(),
                request.getRequests().stream()
                        .map(stream -> new ModelEventStream(stream.getModelId(), null, List.of()))
                        .toList());
    }

    private static GetModelEventsResult pageResponse(
            GetModelEvents request,
            ModelHeadState head) {
        long sequenceNumber = requestCursor(request) + 1L;
        return new GetModelEventsResult(
                request.getRequestId(), 9L,
                List.of(new ModelEventPayload(
                        sequenceNumber, event("event-" + sequenceNumber))),
                List.of(new ModelEventStream(
                        "a", head,
                        List.of(new ModelEventMembership(
                                sequenceNumber, sequenceNumber,
                                sequenceNumber == 0L ? -1L : sequenceNumber - 1L,
                                "commit-" + sequenceNumber, 0)))));
    }

    private static long requestCursor(GetModelEvents request) {
        return request.getRequests().getFirst().getLastSequenceNumber();
    }

    private static GetModelEvents awaitRequest(
            BlockingQueue<GetModelEvents> requests) {
        try {
            GetModelEvents result = requests.poll(2L, TimeUnit.SECONDS);
            assertTrue(result != null, "Expected replay page was not requested");
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting replay request", e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2L, TimeUnit.SECONDS), "Expected prefetch did not start");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting prefetch", e);
        }
    }

    private static SerializedMessage event(String value) {
        return new SerializedMessage(
                new Data<>(value.getBytes(), "event", 0),
                Metadata.empty(), value, 1L);
    }
}
