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
import io.fluxzero.common.api.modeling.TrackModelUpdatesResult;
import io.fluxzero.sdk.configuration.client.WebSocketClient;
import io.fluxzero.sdk.persisting.eventsourcing.AggregateEventStream;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.fluxzero.common.Guarantee.STORED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void explicitMaxSizeStopsAfterPartialFirstBatch() {
        String aggregateId = "aggregate";
        int maxSize = 30_000;
        int resultSize = 20_785;
        long lastSequenceNumber = 21_061L;
        List<GetEvents> requests = new ArrayList<>();
        SerializedMessage event = new SerializedMessage(
                new Data<>(new byte[0], "event", 0), Metadata.empty(), "event-id", 0L);

        AggregateEventStream<SerializedMessage> result = WebSocketEventStoreClient.getEvents(
                aggregateId, -1L, maxSize, 8192, request -> {
                    requests.add(request);
                    return new GetEventsResult(
                            request.getRequestId(),
                            new EventBatch(aggregateId, Collections.nCopies(resultSize, event), false),
                            lastSequenceNumber);
                });

        assertEquals(resultSize, result.count());
        assertEquals(Optional.of(lastSequenceNumber), result.getLastSequenceNumber());
        assertEquals(1, requests.size());
        assertEquals(aggregateId, requests.getFirst().getAggregateId());
        assertEquals(-1L, requests.getFirst().getLastSequenceNumber());
        assertEquals(maxSize, requests.getFirst().getBatchSize());
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
            packer.packInt(6);
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
