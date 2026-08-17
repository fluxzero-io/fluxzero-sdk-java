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
import io.fluxzero.common.api.modeling.ModelEventPayload;
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.sdk.persisting.eventsourcing.client.EventStoreClient;
import io.fluxzero.sdk.persisting.eventsourcing.client.InMemoryEventStore;
import io.fluxzero.sdk.persisting.eventsourcing.client.LocalEventStoreClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Coalesces concurrent current-state model reads into one native multi-stream request. */
final class ModelEventRequestBatcher {

    private static final long COALESCING_DELAY_NANOS = 200_000L;
    private static final int DIRECT_REQUEST_SIZE = 1_024;

    private final EventStoreClient eventStoreClient;
    private final int maxStreams;
    private final long coalescingDelayNanos;
    private final ConcurrentLinkedQueue<PendingRead> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushing = new AtomicBoolean();

    ModelEventRequestBatcher(EventStoreClient eventStoreClient, int maxStreams) {
        this(eventStoreClient, maxStreams, COALESCING_DELAY_NANOS);
    }

    ModelEventRequestBatcher(EventStoreClient eventStoreClient, int maxStreams, long coalescingDelayNanos) {
        this.eventStoreClient = eventStoreClient;
        this.maxStreams = maxStreams;
        if (coalescingDelayNanos < 0L) {
            throw new IllegalArgumentException("Coalescing delay must not be negative");
        }
        this.coalescingDelayNanos = coalescingDelayNanos;
    }

    GetModelEventsResult get(GetModelEvents request) {
        if (!isCurrentBoundary(request) || request.getRequests().isEmpty()
                || eventStoreClient instanceof LocalEventStoreClient
                || eventStoreClient instanceof InMemoryEventStore) {
            return eventStoreClient.getModelEvents(request);
        }
        if (request.getRequests().size() >= DIRECT_REQUEST_SIZE) {
            return eventStoreClient.getModelEvents(request);
        }
        PendingRead read = new PendingRead(request, new CompletableFuture<>());
        pending.add(read);
        scheduleFlush();
        return read.result().join();
    }

    private void scheduleFlush() {
        if (flushing.compareAndSet(false, true)) {
            Thread.ofVirtual().name("fluxzero-model-read-batcher").start(this::flush);
        }
    }

    private void flush() {
        while (true) {
            LockSupport.parkNanos(coalescingDelayNanos);
            List<PendingRead> reads = new ArrayList<>(maxStreams);
            PendingRead read;
            while (reads.size() < maxStreams && (read = pending.poll()) != null) {
                reads.add(read);
            }
            if (!reads.isEmpty()) {
                process(reads);
            }
            if (pending.isEmpty()) {
                flushing.set(false);
                if (pending.isEmpty() || !flushing.compareAndSet(false, true)) {
                    return;
                }
            }
        }
    }

    private void process(List<PendingRead> reads) {
        List<PendingRead> remaining = new ArrayList<>(reads);
        while (!remaining.isEmpty()) {
            ReadGroup group = new ReadGroup(maxStreams);
            for (int index = 0; index < remaining.size(); ) {
                if (group.add(remaining.get(index))) {
                    remaining.remove(index);
                } else {
                    index++;
                }
            }
            group.execute(eventStoreClient);
        }
    }

    private static boolean isCurrentBoundary(GetModelEvents request) {
        return request.getMaxStateIndex() == null
               && request.getBoundaryCommitId() == null
               && request.getBoundaryEventIndex() == null;
    }

    private record PendingRead(
            GetModelEvents request,
            CompletableFuture<GetModelEventsResult> result) {
    }

    private static final class ReadGroup {
        private final int maxStreams;
        private final List<PendingRead> reads = new ArrayList<>();
        private final LinkedHashMap<String, ModelEventStreamRequest> streams = new LinkedHashMap<>();
        private long maxBytes;

        private ReadGroup(int maxStreams) {
            this.maxStreams = maxStreams;
        }

        private boolean add(PendingRead read) {
            if (!reads.isEmpty() && reads.getFirst().request().isCompactPayloads()
                    != read.request().isCompactPayloads()) {
                return false;
            }
            int additional = 0;
            for (ModelEventStreamRequest request : read.request().getRequests()) {
                ModelEventStreamRequest existing = streams.get(request.getModelId());
                if (existing == null) {
                    additional++;
                } else if (!existing.equals(request)) {
                    return false;
                }
            }
            if (!reads.isEmpty() && streams.size() + additional > maxStreams) {
                return false;
            }
            reads.add(read);
            read.request().getRequests().forEach(
                    request -> streams.putIfAbsent(request.getModelId(), request));
            maxBytes = Math.max(maxBytes, read.request().getMaxBytes());
            return true;
        }

        private void execute(EventStoreClient eventStoreClient) {
            try {
                GetModelEventsResult response = eventStoreClient.getModelEvents(new GetModelEvents(
                        List.copyOf(streams.values()), null, maxBytes,
                        reads.getFirst().request().isCompactPayloads()));
                if (reads.size() == 1) {
                    reads.getFirst().result().complete(response);
                    return;
                }
                Map<String, ModelEventStream> responseStreams = new HashMap<>();
                response.getStreams().forEach(
                        stream -> responseStreams.put(stream.getModelId(), stream));
                for (PendingRead read : reads) {
                    GetModelEventsResult split = split(read.request(), response, responseStreams);
                    if (madeNoProgress(read.request(), split)) {
                        split = eventStoreClient.getModelEvents(read.request());
                    }
                    read.result().complete(split);
                }
            } catch (Throwable failure) {
                reads.forEach(read -> read.result().completeExceptionally(failure));
            }
        }

        private static GetModelEventsResult split(
                GetModelEvents request,
                GetModelEventsResult response,
                Map<String, ModelEventStream> responseStreams) {
            List<ModelEventStream> selectedStreams = request.getRequests().stream()
                    .map(ModelEventStreamRequest::getModelId)
                    .map(responseStreams::get)
                    .toList();
            Map<Long, Boolean> referencedPayloads = new HashMap<>();
            selectedStreams.stream().filter(java.util.Objects::nonNull)
                    .flatMap(stream -> stream.getMemberships().stream())
                    .forEach(membership -> referencedPayloads.put(membership.getStateIndex(), Boolean.TRUE));
            List<ModelEventPayload> payloads = response.getPayloads().stream()
                    .filter(payload -> referencedPayloads.containsKey(payload.getStateIndex()))
                    .toList();
            return new GetModelEventsResult(
                    request.getRequestId(), response.getStateIndex(), payloads, selectedStreams);
        }

        private static boolean madeNoProgress(
                GetModelEvents request,
                GetModelEventsResult response) {
            boolean incomplete = false;
            for (int index = 0; index < request.getRequests().size(); index++) {
                ModelEventStreamRequest requested = request.getRequests().get(index);
                ModelEventStream stream = response.getStreams().get(index);
                if (stream != null && !stream.getMemberships().isEmpty()) {
                    return false;
                }
                incomplete |= requested.getMaxSize() > 0 && stream != null && stream.getHead() != null
                              && requested.getLastSequenceNumber() < stream.getHead().getSequenceNumber();
            }
            return incomplete;
        }
    }
}
