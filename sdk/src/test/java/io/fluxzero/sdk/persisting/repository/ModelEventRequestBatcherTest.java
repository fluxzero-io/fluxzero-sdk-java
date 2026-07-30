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

import io.fluxzero.common.api.modeling.GetModelEvents;
import io.fluxzero.common.api.modeling.GetModelEventsResult;
import io.fluxzero.common.api.modeling.ModelEventDataBlock;
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelHeadState;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelEventRequestBatcherTest {

    @Test
    void preservesEmbeddedCompactBlocksWhenConcurrentReadsAreCoalesced()
            throws Exception {
        EventStoreClient client =
                mock(EventStoreClient.class);
        ModelEventDataBlock block =
                compactBlock(List.of("a", "b"));
        when(client.getCompactModelEvents(any()))
                .thenAnswer(invocation ->
                                    compactResponse(
                                            invocation.getArgument(0),
                                            block));
        ModelEventRequestBatcher subject =
                new ModelEventRequestBatcher(
                        client,
                        16,
                        20_000_000L);

        List<GetModelEventsResult> results =
                readConcurrently(subject, "a", "b");

        assertEquals(
                List.of("a"),
                results.getFirst().getStreams().stream()
                        .map(ModelEventStream::getModelId)
                        .toList());
        assertEquals(
                List.of("b"),
                results.getLast().getStreams().stream()
                        .map(ModelEventStream::getModelId)
                        .toList());
        assertSame(
                block,
                results.getFirst()
                        .getCompactMembershipBlocks()
                        .getFirst());
        assertSame(
                block,
                results.getLast()
                        .getCompactMembershipBlocks()
                        .getFirst());
        verify(client, times(1))
                .getCompactModelEvents(any());
        verify(client, never())
                .getModelEvents(any());
    }

    @Test
    void retriesACompactCallerThatMadeNoProgressInTheCombinedByteWindow()
            throws Exception {
        EventStoreClient client =
                mock(EventStoreClient.class);
        ModelEventDataBlock aBlock =
                compactBlock(List.of("a"));
        ModelEventDataBlock bBlock =
                compactBlock(List.of("b"));
        when(client.getCompactModelEvents(any()))
                .thenAnswer(invocation -> {
                    GetModelEvents request =
                            invocation.getArgument(0);
                    return request.getRequests().size() == 2
                            ? compactResponse(
                                    request,
                                    aBlock)
                            : compactResponse(
                                    request,
                                    bBlock);
                });
        ModelEventRequestBatcher subject =
                new ModelEventRequestBatcher(
                        client,
                        16,
                        20_000_000L);

        List<GetModelEventsResult> results =
                readConcurrently(subject, "a", "b");

        assertSame(
                aBlock,
                results.getFirst()
                        .getCompactMembershipBlocks()
                        .getFirst());
        assertSame(
                bBlock,
                results.getLast()
                        .getCompactMembershipBlocks()
                        .getFirst());
        verify(client, times(2))
                .getCompactModelEvents(any());
        verify(client, never())
                .getModelEvents(any());
    }

    @Test
    void givesEachCompactCallerOnlyBlocksContainingItsModels()
            throws Exception {
        EventStoreClient client =
                mock(EventStoreClient.class);
        ModelEventDataBlock aBlock =
                compactBlock(List.of("a"));
        ModelEventDataBlock bBlock =
                compactBlock(List.of("b"));
        when(client.getCompactModelEvents(any()))
                .thenAnswer(invocation -> {
                    GetModelEvents request =
                            invocation.getArgument(0);
                    return compactResponse(
                            request,
                            List.of(aBlock, bBlock));
                });
        ModelEventRequestBatcher subject =
                new ModelEventRequestBatcher(
                        client,
                        16,
                        20_000_000L);

        List<GetModelEventsResult> results =
                readConcurrently(subject, "a", "b");

        assertEquals(
                List.of(aBlock),
                results.getFirst()
                        .getCompactMembershipBlocks());
        assertEquals(
                List.of(bBlock),
                results.getLast()
                        .getCompactMembershipBlocks());
    }

    @Test
    void keepsFineGrainedFanInOnTheExpandedSplitPath()
            throws Exception {
        EventStoreClient client =
                mock(EventStoreClient.class);
        when(client.getModelEvents(any()))
                .thenAnswer(invocation -> {
                    GetModelEvents request =
                            invocation.getArgument(0);
                    return new GetModelEventsResult(
                            request.getRequestId(),
                            11L,
                            List.of(),
                            request.getRequests().stream()
                                    .map(stream ->
                                                 new ModelEventStream(
                                                         stream.getModelId(),
                                                         null,
                                                         List.of()))
                                    .toList());
                });
        ModelEventRequestBatcher subject =
                new ModelEventRequestBatcher(
                        client,
                        16,
                        20_000_000L);

        List<GetModelEventsResult> results =
                readConcurrently(
                        subject,
                        List.of("a", "b", "c", "d", "e"));

        assertEquals(5, results.size());
        verify(client, times(1))
                .getModelEvents(any());
        verify(client, never())
                .getCompactModelEvents(any());
    }

    @Test
    void keepsAlreadyBatchedCompactReadsIndependent()
            throws Exception {
        EventStoreClient client =
                mock(EventStoreClient.class);
        when(client.getCompactModelEvents(any()))
                .thenAnswer(invocation -> {
                    GetModelEvents request =
                            invocation.getArgument(0);
                    return new GetModelEventsResult(
                            request.getRequestId(),
                            11L,
                            List.of(),
                            request.getRequests().stream()
                                    .map(stream ->
                                                 new ModelEventStream(
                                                         stream.getModelId(),
                                                         null,
                                                         List.of()))
                                    .toList());
                });
        ModelEventRequestBatcher subject =
                new ModelEventRequestBatcher(
                        client,
                        4_096,
                        20_000_000L);
        GetModelEvents request =
                new GetModelEvents(
                        java.util.stream.IntStream.range(0, 1_024)
                                .mapToObj(index ->
                                                  new ModelEventStreamRequest(
                                                          "model-" + index,
                                                          -1L,
                                                          16))
                                .toList(),
                        null,
                        1_024L,
                        true);

        GetModelEventsResult result =
                subject.getCompact(request);

        assertEquals(1_024, result.getStreams().size());
        verify(client, times(1))
                .getCompactModelEvents(request);
        verify(client, never())
                .getModelEvents(any());
    }

    private static List<GetModelEventsResult> readConcurrently(
            ModelEventRequestBatcher subject,
            String first,
            String second) throws Exception {
        return readConcurrently(
                subject,
                List.of(first, second));
    }

    private static List<GetModelEventsResult> readConcurrently(
            ModelEventRequestBatcher subject,
            List<String> modelIds) throws Exception {
        CountDownLatch start =
                new CountDownLatch(1);
        try (ExecutorService executor =
                     Executors.newFixedThreadPool(
                             modelIds.size())) {
            List<Future<GetModelEventsResult>> futures =
                    new ArrayList<>(
                            modelIds.size());
            for (String modelId : modelIds) {
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return subject.getCompact(
                                            request(modelId));
                                }));
            }
            start.countDown();
            List<GetModelEventsResult> results =
                    new ArrayList<>(
                            futures.size());
            for (Future<GetModelEventsResult> future :
                    futures) {
                results.add(
                        future.get());
            }
            return List.copyOf(results);
        }
    }

    private static GetModelEvents request(
            String modelId) {
        return new GetModelEvents(
                List.of(
                        new ModelEventStreamRequest(
                                modelId,
                                -1L,
                                16)),
                null,
                1_024L,
                true);
    }

    private static GetModelEventsResult compactResponse(
            GetModelEvents request,
            ModelEventDataBlock block) {
        return compactResponse(
                request,
                List.of(block));
    }

    private static GetModelEventsResult compactResponse(
            GetModelEvents request,
            List<ModelEventDataBlock> blocks) {
        List<ModelEventStream> streams =
                request.getRequests().stream()
                        .map(stream ->
                                     new ModelEventStream(
                                             stream.getModelId(),
                                             new ModelHeadState(
                                                     stream.getModelId(),
                                                     "example.Model",
                                                     0L,
                                                     11L,
                                                     true,
                                                     false),
                                             List.of()))
                        .toList();
        return new GetModelEventsResult(
                request.getRequestId(),
                11L,
                List.of(),
                streams,
                null,
                null,
                null,
                null,
                blocks);
    }

    private static ModelEventDataBlock compactBlock(
            List<String> modelIds) throws IOException {
        try (MessageBufferPacker packer =
                     MessagePack.newDefaultBufferPacker()) {
            packer.packInt(5);
            packer.packArrayHeader(
                    modelIds.size());
            packer.packLong(0L);
            packer.packLong(100L);
            packer.packLong(-1L);
            packer.packBoolean(true);
            packer.packString("example.Model");
            long stateIndex = 0L;
            for (String modelId : modelIds) {
                packer.packString(modelId);
                packer.packLong(10L - stateIndex);
                stateIndex = 10L;
                packer.packLong(0L);
                packer.packLong(0L);
                packer.packString(
                        "commit-" + modelId);
                packer.packLong(0L);
                packer.packBoolean(true);
                packer.packLong(1L);
                packer.packNil();
            }
            packer.packBoolean(true);
            packer.packBinaryHeader(1);
            packer.writePayload(
                    new byte[]{0});
            return new ModelEventDataBlock(
                    packer.toByteArray());
        }
    }
}
