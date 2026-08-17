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
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.LocalEventStoreClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelEventRequestBatcherTest {

    @Test
    void executesLocalStoreReadsOnTheCallingThread() {
        LocalEventStoreClient client = mock(LocalEventStoreClient.class);
        AtomicReference<Thread> invocationThread = new AtomicReference<>();
        when(client.getModelEvents(any())).thenAnswer(invocation -> {
            invocationThread.set(Thread.currentThread());
            return response(invocation.getArgument(0));
        });
        ModelEventRequestBatcher subject = new ModelEventRequestBatcher(client, 16, 20_000_000L);

        Thread callingThread = Thread.currentThread();
        subject.get(request("local"));

        assertSame(callingThread, invocationThread.get());
    }

    @Test
    void coalescesConcurrentReadsAndSplitsTheirStreams() throws Exception {
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelEvents(any())).thenAnswer(
                invocation -> response(invocation.getArgument(0)));
        ModelEventRequestBatcher subject = new ModelEventRequestBatcher(client, 16, 20_000_000L);

        List<GetModelEventsResult> results = readConcurrently(subject, List.of("a", "b", "c", "d"));

        assertEquals(List.of("a", "b", "c", "d"), results.stream()
                .map(result -> result.getStreams().getFirst().getModelId()).toList());
        verify(client, times(1)).getModelEvents(any());
    }

    @Test
    void executesLargeNativeRequestsDirectly() {
        EventStoreClient client = mock(EventStoreClient.class);
        when(client.getModelEvents(any())).thenAnswer(
                invocation -> response(invocation.getArgument(0)));
        ModelEventRequestBatcher subject = new ModelEventRequestBatcher(client, 4_096, 20_000_000L);
        List<ModelEventStreamRequest> streams = new ArrayList<>(1_024);
        for (int index = 0; index < 1_024; index++) {
            streams.add(new ModelEventStreamRequest("model-" + index, -1L, 16));
        }
        GetModelEvents request = new GetModelEvents(streams, null, 1_024L, true);

        assertEquals(1_024, subject.get(request).getStreams().size());
        verify(client).getModelEvents(request);
    }

    private static List<GetModelEventsResult> readConcurrently(
            ModelEventRequestBatcher subject, List<String> modelIds) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(modelIds.size())) {
            List<Future<GetModelEventsResult>> futures = new ArrayList<>(modelIds.size());
            for (String modelId : modelIds) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return subject.get(request(modelId));
                }));
            }
            start.countDown();
            List<GetModelEventsResult> result = new ArrayList<>(futures.size());
            for (Future<GetModelEventsResult> future : futures) {
                result.add(future.get());
            }
            return List.copyOf(result);
        }
    }

    private static GetModelEvents request(String modelId) {
        return new GetModelEvents(
                List.of(new ModelEventStreamRequest(modelId, -1L, 16)),
                null, 1_024L, true);
    }

    private static GetModelEventsResult response(GetModelEvents request) {
        return new GetModelEventsResult(
                request.getRequestId(), 11L, List.of(),
                request.getRequests().stream()
                        .map(stream -> new ModelEventStream(stream.getModelId(), null, List.of()))
                        .toList());
    }
}
