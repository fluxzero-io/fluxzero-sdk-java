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

package io.fluxzero.sdk.persisting.eventsourcing.client;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.api.ResultBatch;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.eventsourcing.EventBatch;
import io.fluxzero.common.api.eventsourcing.GetEvents;
import io.fluxzero.common.api.eventsourcing.GetEventsResult;
import io.fluxzero.common.api.modeling.CommitModels;
import io.fluxzero.common.api.modeling.CommitModelsResult;
import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.ModelEventDataBlock;
import io.fluxzero.common.api.modeling.ModelEventPageDecoder;
import io.fluxzero.common.api.modeling.ModelEventPayload;
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.common.api.modeling.ModelReadBoundary;
import io.fluxzero.common.api.modeling.ModelConflictPolicy;
import io.fluxzero.common.api.modeling.ModelCommitWireCodec;
import io.fluxzero.common.api.modeling.TrackModelUpdatesResult;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.persisting.eventsourcing.AggregateEventStream;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.fluxzero.common.Guarantee.STORED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebSocketEventStoreClientTest {

    @Test
    void expandsPackedMembershipsForEveryAliasOfTheCanonicalModel() throws Exception {
        GetModelEvents request = new GetModelEvents(
                List.of(
                        new ModelEventStreamRequest("first-code", -1L, 1),
                        new ModelEventStreamRequest("second-code", -1L, 1)),
                ModelReadBoundary.current(), 1_024L);
        SerializedMessage event = new SerializedMessage(
                new Data<>(new byte[]{1}, "event", 0), Metadata.empty(), "event-1", 1L);
        GetModelEventsResult packed = new GetModelEventsResult(
                request.getRequestId(), 11L,
                true,
                List.of(new ModelEventPayload(11L, event)),
                List.of(
                        stream("first-code", "model-1"),
                        stream("second-code", "model-1")),
                new long[0], List.of(), new long[0],
                List.of(new ModelEventDataBlock(membershipBlock("model-1"))));

        GetModelEventsResult expanded =
                ModelEventPageDecoder.expand(request, packed);

        assertEquals(List.of(1, 1), expanded.getStreams().stream()
                .map(stream -> stream.getMemberships().size()).toList());
        assertEquals(List.of("first-code", "second-code"), expanded.getStreams().stream()
                .map(ModelEventStream::getModelId).toList());
    }

    @Test
    void preservesModelResultJfrClassificationInConcreteClient() {
        RecordingEventStoreClient subject = new RecordingEventStoreClient();
        try {
            assertEquals("MODEL_COMMIT", subject.resultType(List.of(
                    mock(CommitModelsResult.class), mock(CommitModelsResult.class))));
            assertEquals("MODEL_UPDATE", subject.resultType(List.of(mock(TrackModelUpdatesResult.class))));
            assertEquals("RESULT", subject.resultType(List.of(
                    mock(CommitModelsResult.class), mock(TrackModelUpdatesResult.class))));
            assertEquals("RESULT", subject.resultType(List.of()));
        } finally {
            subject.close();
        }
    }

    @Test
    void classifiesCompactModelResultsBeforeRequestCorrelation() throws Exception {
        CommitModelsResult result = CommitModelsResult.acceptedSingleTarget(
                1L, "commit", 2L, 3L, "model", 4L, true);
        ResultBatch decoded = (ResultBatch) ModelCommitWireCodec.tryDecode(
                ModelCommitWireCodec.tryEncode(new ResultBatch(List.of(result))));
        RecordingEventStoreClient subject = new RecordingEventStoreClient();
        try {
            assertEquals("MODEL_COMMIT", subject.resultType(decoded.getResults()));
        } finally {
            subject.close();
        }
    }

    @Test
    void completesAlignedModelResultsOncePerRequestOwnedProcessor() {
        RecordingEventStoreClient subject = new RecordingEventStoreClient();
        AtomicInteger invocations = new AtomicInteger();
        AtomicReference<List<CommitModelsResult>> processedResults = new AtomicReference<>();
        AtomicReference<List<Object>> processedContexts = new AtomicReference<>();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        ModelCommitBatchingClient.ModelCommitResultProcessor processor =
                (results, contexts) -> {
                    invocations.incrementAndGet();
                    processedResults.set(results);
                    processedContexts.set(contexts);
                    return gate;
                };
        CommitModelsResult first = mock(CommitModelsResult.class);
        CommitModelsResult second = mock(CommitModelsResult.class);

        try {
            CompletableFuture<Void> completion = subject.prepareResultsForTest(
                    List.of(first, second),
                    List.of(
                            new ModelCommitBatchingClient.ModelCommitCompletion("first", processor),
                            new ModelCommitBatchingClient.ModelCommitCompletion("second", processor)));

            assertEquals(1, invocations.get());
            assertEquals(List.of(first, second), processedResults.get());
            assertEquals(List.of("first", "second"), processedContexts.get());
            assertFalse(completion.isDone());

            gate.complete(null);
            completion.join();
        } finally {
            subject.close();
        }
    }

    @Test
    void readyModelCommitBatchSendsFullChunksAndFlushesItsTail() {
        RecordingEventStoreClient subject = new RecordingEventStoreClient();
        ModelCommitBatchingClient.ModelCommitBatch batch =
                subject.beginReadyModelCommitBatch();

        try {
            for (int index = 0; index < 256; index++) {
                assertFalse(batch.add(index, commit("commit-" + index)).isDone());
            }
            assertEquals(List.of(256), subject.sentBatchSizes);

            assertFalse(batch.add(256, commit("tail")).isDone());
            assertEquals(List.of(256), subject.sentBatchSizes);

            batch.flush();
            assertEquals(List.of(256, 1), subject.sentBatchSizes);
            batch.flush();
            assertEquals(List.of(256, 1), subject.sentBatchSizes);
        } finally {
            subject.close();
        }
    }

    @Test
    void fixedModelCommitBatchReleasesWhenCommitsAndSkippedSlotsSettle() {
        RecordingEventStoreClient subject = new RecordingEventStoreClient();
        ModelCommitBatchingClient.ModelCommitBatch batch =
                subject.beginModelCommitBatch(3);

        try {
            assertFalse(batch.add(0, commit("first")).isDone());
            batch.skip(1);
            assertEquals(List.of(), subject.sentBatchSizes);

            assertFalse(batch.add(2, commit("last")).isDone());
            assertEquals(List.of(2), subject.sentBatchSizes);
            batch.skip(2);
            batch.flush();
            assertEquals(List.of(2), subject.sentBatchSizes);
        } finally {
            subject.close();
        }
    }

    @Test
    void fixedModelCommitBatchRejectsDuplicateCommits() {
        RecordingEventStoreClient subject = new RecordingEventStoreClient();
        ModelCommitBatchingClient.ModelCommitBatch batch =
                subject.beginModelCommitBatch(2);

        try {
            batch.add(0, commit("first"));
            assertThrows(IllegalStateException.class,
                         () -> batch.add(0, commit("duplicate")));
        } finally {
            batch.fail(new IllegalStateException("test complete"));
            subject.close();
        }
    }

    @Test
    void explicitMaxSizeIsAppliedAcrossCountBoundedPages() {
        List<GetEvents> requests = new ArrayList<>();
        List<SerializedMessage> history = events(8, 1);

        AggregateEventStream<SerializedMessage> result = WebSocketEventStoreClient.getEvents(
                "aggregate", -1L, 5, 2, 0L, recordingStore(history, requests, false));

        assertEquals(5, result.count());
        assertEquals(Optional.of(4L), result.getLastSequenceNumber());
        assertEquals(List.of(2, 2, 1), requests.stream().map(GetEvents::getBatchSize).toList());
    }

    @Test
    void byteBoundedShortPagesContinueUntilTheRuntimeReturnsEmpty() {
        List<GetEvents> requests = new ArrayList<>();
        List<SerializedMessage> history = events(3, 3);

        AggregateEventStream<SerializedMessage> result = WebSocketEventStoreClient.getEvents(
                "aggregate", -1L, -1, 4, 5L, recordingStore(history, requests, true));

        assertEquals(3, result.count());
        assertEquals(Optional.of(2L), result.getLastSequenceNumber());
        assertEquals(List.of(-1L, 0L, 1L, 2L),
                     requests.stream().map(GetEvents::getLastSequenceNumber).toList());
        assertTrue(requests.stream().allMatch(request -> request.getMaxBytes() == 5L));
    }

    @Test
    void byteBoundedClientRemainsCompatibleWithCountOnlyRuntime() {
        List<GetEvents> requests = new ArrayList<>();
        List<SerializedMessage> history = events(3, 1);

        AggregateEventStream<SerializedMessage> result = WebSocketEventStoreClient.getEvents(
                "aggregate", -1L, -1, 2, 5L, recordingStore(history, requests, false));

        assertEquals(3, result.count());
        assertEquals(3, requests.size(), "An old Runtime needs one final empty request when byte paging is active");
        assertEquals(List.of(-1L, 1L, 2L), requests.stream().map(GetEvents::getLastSequenceNumber).toList());
    }

    @Test
    void countOnlyClientRetainsLegacyShortPageTermination() {
        List<GetEvents> requests = new ArrayList<>();
        List<SerializedMessage> history = events(3, 1);

        AggregateEventStream<SerializedMessage> result = WebSocketEventStoreClient.getEvents(
                "aggregate", -1L, -1, 2, 0L, recordingStore(history, requests, false));

        assertEquals(3, result.count());
        assertEquals(2, requests.size());
    }

    @Test
    void individuallyOversizedEventDoesNotStopPagination() {
        List<GetEvents> requests = new ArrayList<>();
        List<SerializedMessage> history = List.of(event(8), event(2));

        AggregateEventStream<SerializedMessage> result = WebSocketEventStoreClient.getEvents(
                "aggregate", -1L, -1, 4, 5L, recordingStore(history, requests, true));

        assertEquals(history, result.toList());
        assertEquals(Optional.of(1L), result.getLastSequenceNumber());
        assertEquals(3, requests.size());
    }

    @Test
    void rejectsNonAdvancingNonEmptyPage() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                                                    () -> WebSocketEventStoreClient.getEvents(
                                                            "aggregate", 5L, -1, 2, 10L,
                                                            request -> new GetEventsResult(
                                                                    request.getRequestId(), new EventBatch(
                                                                    "aggregate", List.of(event(1)), false), 5L)));
        assertTrue(error.getMessage().contains("did not advance"));
    }

    @Test
    void rejectsPageThatExceedsRequestedCount() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                                                    () -> WebSocketEventStoreClient.getEvents(
                                                            "aggregate", -1L, 1, 2, 0L,
                                                            request -> new GetEventsResult(
                                                                    request.getRequestId(), new EventBatch(
                                                                    "aggregate", events(2, 1), false), 1L)));
        assertTrue(error.getMessage().contains("at most 1"));
    }

    private static Function<GetEvents, GetEventsResult> recordingStore(
            List<SerializedMessage> history, List<GetEvents> requests, boolean enforceBytes) {
        return request -> {
            requests.add(request);
            int from = Math.min(history.size(), Math.toIntExact(request.getLastSequenceNumber() + 1L));
            int to = Math.min(history.size(), from + request.getBatchSize());
            List<SerializedMessage> page = new ArrayList<>();
            long bytes = 0L;
            for (SerializedMessage event : history.subList(from, to)) {
                if (enforceBytes && request.getMaxBytes() > 0 && !page.isEmpty()
                    && bytes + event.getBytes() > request.getMaxBytes()) {
                    break;
                }
                page.add(event);
                bytes += event.getBytes();
            }
            long lastSequenceNumber = page.isEmpty() ? -1L : from + page.size() - 1L;
            return new GetEventsResult(request.getRequestId(),
                                       new EventBatch(request.getAggregateId(), page, false), lastSequenceNumber);
        };
    }

    private static List<SerializedMessage> events(int count, int bytes) {
        List<SerializedMessage> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(event(bytes));
        }
        return result;
    }

    private static SerializedMessage event(int bytes) {
        return new SerializedMessage(new Data<>(new byte[bytes], "event", 0), Metadata.empty(), null, 0L);
    }

    private static CommitModels commit(String id) {
        return new CommitModels(
                id, -1L, List.of(), List.of(),
                ModelConflictPolicy.ACCEPT, STORED, true);
    }

    private static ModelEventStream stream(String requestedId, String modelId) {
        return new ModelEventStream(
                requestedId,
                new ModelHeadState(modelId, "TestModel", 1L, 11L, true, false),
                List.of());
    }

    private static byte[] membershipBlock(String modelId) throws IOException {
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            packer.packInt(7);
            packer.packArrayHeader(1);
            packer.packLong(0L);
            packer.packLong(0L);
            packer.packLong(0L);
            packer.packBoolean(true);
            packer.packString("TestModel");
            packer.packString(modelId);
            packer.packLong(11L);
            packer.packLong(7L);
            packer.packLong(10L);
            packer.packString("commit-1");
            packer.packLong(1L);
            packer.packBoolean(true);
            packer.packLong(1L);
            packer.packNil();
            return packer.toByteArray();
        }
    }

    private static final class RecordingEventStoreClient
            extends WebSocketEventStoreClient {
        private final List<Integer> sentBatchSizes = new ArrayList<>();

        private RecordingEventStoreClient() {
            super(
                    URI.create("ws://localhost/event-sourcing"),
                    8_192,
                    WebSocketClient.newInstance(
                            WebSocketClient.ClientConfig.builder()
                                    .runtimeBaseUrl("ws://localhost")
                                    .name("ready-model-commit-test")
                                    .disableMetrics(true)
                                    .build()),
                    false);
        }

        @Override
        protected void sendPreparedRequests(
                List<? extends PreparedRequest<?>> preparedRequests) {
            sentBatchSizes.add(preparedRequests.size());
        }

        private String resultType(List<RequestResult> results) {
            return jfrResultType(results);
        }

        private CompletableFuture<Void> prepareResultsForTest(
                List<RequestResult> results,
                List<Object> contexts) {
            return prepareResults(results, contexts);
        }
    }
}
