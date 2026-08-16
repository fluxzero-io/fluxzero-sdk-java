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
import io.fluxzero.common.api.modeling.ModelEventPayload;
import io.fluxzero.common.api.modeling.ModelEventStream;
import io.fluxzero.common.api.modeling.ModelEventStreamRequest;
import io.fluxzero.common.api.modeling.ModelStreamBatchDecoder;
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

/**
 * Coalesces concurrent current-state model reads into the native multi-stream request.
 *
 * <p>Historical and commit/event-boundary reads retain their dedicated request because their boundary is part of the
 * reconstruction contract. A response is split back into the exact shape requested by every caller, so the regular
 * loader remains the sole owner of paging validation.</p>
 */
final class ModelEventRequestBatcher {

    private static final long COALESCING_DELAY_NANOS = 200_000L;
    private static final int MAX_SHARED_COMPACT_CALLERS = 4;
    private static final int DIRECT_COMPACT_REQUEST_SIZE = 1_024;

    private final EventStoreClient eventStoreClient;
    private final int maxStreams;
    private final long coalescingDelayNanos;
    private final ConcurrentLinkedQueue<PendingRead> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushing = new AtomicBoolean();

    ModelEventRequestBatcher(EventStoreClient eventStoreClient, int maxStreams) {
        this(
                eventStoreClient,
                maxStreams,
                COALESCING_DELAY_NANOS);
    }

    ModelEventRequestBatcher(
            EventStoreClient eventStoreClient,
            int maxStreams,
            long coalescingDelayNanos) {
        this.eventStoreClient = eventStoreClient;
        this.maxStreams = maxStreams;
        if (coalescingDelayNanos < 0L) {
            throw new IllegalArgumentException(
                    "Coalescing delay must not be negative");
        }
        this.coalescingDelayNanos =
                coalescingDelayNanos;
    }

    GetModelEventsResult get(GetModelEvents request) {
        return get(request, false);
    }

    GetModelEventsResult getCompact(GetModelEvents request) {
        return get(request, true);
    }

    private GetModelEventsResult get(
            GetModelEvents request, boolean compact) {
        if (!isCurrentBoundary(request) || request.getRequests().isEmpty()) {
            return compact
                    ? eventStoreClient.getCompactModelEvents(request)
                    : eventStoreClient.getModelEvents(request);
        }
        /*
         * Local stores have no transport round trip to coalesce. More importantly, their append monitor may dispatch
         * stored events while holding the re-entrant in-memory store lock. A handler is allowed to load another model
         * from that callback. Moving such a nested read to the batcher's virtual thread would make it wait for the lock
         * still owned by the calling commit thread, while that thread waits for the read result.
         */
        if (eventStoreClient instanceof LocalEventStoreClient
            || eventStoreClient instanceof InMemoryEventStore) {
            return compact
                    ? eventStoreClient.getCompactModelEvents(request)
                    : eventStoreClient.getModelEvents(request);
        }
        /*
         * A large compact request is already a native multi-stream batch. Combining several such requests can make
         * physical storage blocks span multiple callers, forcing each caller to decode entries that it did not ask
         * for after the response is split. Keep coalescing for fine-grained loads, where it removes round trips, while
         * allowing independently batched loadModels calls to use the event-store client's normal concurrency.
         */
        if (compact
            && request.getRequests().size()
               >= DIRECT_COMPACT_REQUEST_SIZE) {
            return eventStoreClient.getCompactModelEvents(
                    request);
        }
        PendingRead read = new PendingRead(request, compact);
        pending.add(read);
        scheduleFlush();
        return read.result().join();
    }

    private void scheduleFlush() {
        if (flushing.compareAndSet(false, true)) {
            Thread.ofVirtual()
                    .name("fluxzero-model-read-batcher")
                    .start(this::flush);
        }
    }

    private void flush() {
        while (true) {
            LockSupport.parkNanos(coalescingDelayNanos);
            List<PendingRead> reads = new ArrayList<>(maxStreams);
            PendingRead read;
            while (reads.size() < maxStreams
                   && (read = pending.poll()) != null) {
                reads.add(read);
            }
            if (!reads.isEmpty()) {
                process(reads);
            }
            if (pending.isEmpty()) {
                flushing.set(false);
                if (pending.isEmpty()
                    || !flushing.compareAndSet(false, true)) {
                    return;
                }
            }
        }
    }

    private void process(List<PendingRead> reads) {
        List<PendingRead> remaining = new ArrayList<>(reads);
        while (!remaining.isEmpty()) {
            ReadGroup group = new ReadGroup(maxStreams);
            for (int i = 0; i < remaining.size(); ) {
                PendingRead candidate = remaining.get(i);
                if (group.add(candidate)) {
                    remaining.remove(i);
                } else {
                    i++;
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
            boolean compact,
            CompletableFuture<GetModelEventsResult> result) {

        private PendingRead(GetModelEvents request, boolean compact) {
            this(request, compact, new CompletableFuture<>());
        }
    }

    private static final class ReadGroup {
        private final int maxStreams;
        private final List<PendingRead> reads = new ArrayList<>();
        private final LinkedHashMap<String, ModelEventStreamRequest> streams =
                new LinkedHashMap<>();
        private long maxBytes;

        private ReadGroup(int maxStreams) {
            this.maxStreams = maxStreams;
        }

        private boolean add(PendingRead read) {
            if (!reads.isEmpty()
                && (reads.getFirst().request().isCompactPayloads()
                    != read.request().isCompactPayloads()
                    || reads.getFirst().compact() != read.compact())) {
                return false;
            }
            int additional = 0;
            for (ModelEventStreamRequest request :
                    read.request().getRequests()) {
                ModelEventStreamRequest existing =
                        streams.get(request.getModelId());
                if (existing == null) {
                    additional++;
                } else if (!existing.equals(request)) {
                    return false;
                }
            }
            if (!reads.isEmpty()
                && streams.size() + additional > maxStreams) {
                return false;
            }
            reads.add(read);
            read.request().getRequests()
                    .forEach(request ->
                                     streams.putIfAbsent(
                                             request.getModelId(),
                                             request));
            maxBytes = Math.max(
                    maxBytes,
                    read.request().getMaxBytes());
            return true;
        }

        private void execute(EventStoreClient eventStoreClient) {
            try {
                GetModelEvents combinedRequest =
                        new GetModelEvents(
                                List.copyOf(
                                        streams.values()),
                                null,
                                maxBytes,
                                reads.getFirst()
                                        .request()
                                        .isCompactPayloads());
                /*
                 * Every compact split references the same physical blocks. Each caller selects its own entries while
                 * decoding, so bound that duplicated decode work. Fine-grained fan-in keeps the established expanded
                 * split path; a few large concurrent loadModels calls retain the zero-copy representation.
                 */
                boolean useCompactTransport =
                        reads.getFirst().compact()
                        && reads.size()
                           <= MAX_SHARED_COMPACT_CALLERS;
                GetModelEventsResult response =
                        useCompactTransport
                                ? eventStoreClient.getCompactModelEvents(
                                        combinedRequest)
                                : eventStoreClient.getModelEvents(
                                        combinedRequest);
                boolean embeddedCompact =
                        useCompactTransport
                        && isEmbeddedCompact(response);
                if (reads.size() > 1
                    && useCompactTransport
                    && !embeddedCompact) {
                    /*
                     * Regular streams still use payload ordinals that must be split per caller. Keep their existing
                     * expanded path until the non-embedded compact payload format can be partitioned without copying.
                     */
                    response =
                            eventStoreClient.getModelEvents(
                                    combinedRequest);
                }
                if (reads.size() == 1) {
                    reads.getFirst().result().complete(response);
                    return;
                }
                Map<String, ModelEventStream> responseStreams =
                        new HashMap<>();
                response.getStreams()
                        .forEach(stream ->
                                         responseStreams.put(
                                                 stream.getModelId(),
                                                 stream));
                CompactSplitIndex compactIndex =
                        embeddedCompact
                                ? compactIndex(response, reads)
                                : CompactSplitIndex.empty(reads.size());
                for (int readIndex = 0;
                     readIndex < reads.size();
                     readIndex++) {
                    PendingRead read = reads.get(readIndex);
                    GetModelEventsResult split =
                            embeddedCompact
                                    ? splitEmbeddedCompact(
                                            read.request(),
                                            response,
                                            responseStreams,
                                            compactIndex.blocksByRead()
                                                    .get(readIndex))
                                    : split(
                                            read.request(),
                                            response,
                                            responseStreams);
                    if (madeNoProgress(
                            read.request(),
                            split,
                            compactIndex.progress())) {
                        split =
                                read.compact()
                                        ? eventStoreClient
                                                .getCompactModelEvents(
                                                        read.request())
                                        : eventStoreClient
                                                .getModelEvents(
                                                        read.request());
                    }
                    read.result().complete(split);
                }
            } catch (Throwable failure) {
                reads.forEach(read ->
                                      read.result()
                                              .completeExceptionally(
                                                      failure));
            }
        }

        private static GetModelEventsResult split(
                GetModelEvents request,
                GetModelEventsResult response,
                Map<String, ModelEventStream> responseStreams) {
            List<ModelEventStream> selectedStreams =
                    request.getRequests().stream()
                            .map(ModelEventStreamRequest::getModelId)
                            .map(responseStreams::get)
                            .toList();
            Map<Long, Boolean> referencedPayloads =
                    new HashMap<>();
            selectedStreams.stream()
                    .filter(java.util.Objects::nonNull)
                    .flatMap(stream ->
                                     stream.getMemberships()
                                             .stream())
                    .forEach(membership ->
                                     referencedPayloads.put(
                                             membership
                                                     .getStateIndex(),
                                             Boolean.TRUE));
            List<ModelEventPayload> payloads =
                    response.getPayloads().stream()
                            .filter(payload ->
                                            referencedPayloads
                                                    .containsKey(
                                                            payload.getStateIndex()))
                            .toList();
            return new GetModelEventsResult(
                    request.getRequestId(),
                    response.getStateIndex(),
                    payloads,
                    selectedStreams);
        }

        private static GetModelEventsResult splitEmbeddedCompact(
                GetModelEvents request,
                GetModelEventsResult response,
                Map<String, ModelEventStream> responseStreams,
                List<ModelEventDataBlock> compactBlocks) {
            List<ModelEventStream> selectedStreams =
                    request.getRequests().stream()
                            .map(ModelEventStreamRequest::getModelId)
                            .map(responseStreams::get)
                            .toList();
            return new GetModelEventsResult(
                    request.getRequestId(),
                    response.getStateIndex(),
                    List.of(),
                    selectedStreams,
                    null,
                    null,
                    null,
                    null,
                    compactBlocks);
        }

        private static boolean isEmbeddedCompact(
                GetModelEventsResult response) {
            return response.getPayloads().isEmpty()
                   && (response.getCompactPayloads() == null
                       || response.getCompactPayloads().length == 0)
                   && (response.getCompactPayloadBlocks() == null
                       || response.getCompactPayloadBlocks().isEmpty())
                   && response.getCompactMembershipBlocks() != null
                   && !response.getCompactMembershipBlocks().isEmpty();
        }

        private static CompactSplitIndex compactIndex(
                GetModelEventsResult response,
                List<PendingRead> reads) {
            Map<String, Integer> readMasks = new HashMap<>();
            for (int readIndex = 0;
                 readIndex < reads.size();
                 readIndex++) {
                int mask = 1 << readIndex;
                for (ModelEventStreamRequest request :
                        reads.get(readIndex)
                                .request()
                                .getRequests()) {
                    readMasks.merge(
                            request.getModelId(),
                            mask,
                            (left, right) -> left | right);
                }
            }
            List<List<ModelEventDataBlock>> blocksByRead =
                    new ArrayList<>(reads.size());
            for (int readIndex = 0;
                 readIndex < reads.size();
                 readIndex++) {
                blocksByRead.add(new ArrayList<>());
            }
            Map<String, Long> progress = new HashMap<>();
            for (ModelEventDataBlock block :
                    response.getCompactMembershipBlocks()) {
                int blockMask = 0;
                for (ModelStreamBatchDecoder.Entry entry :
                        ModelStreamBatchDecoder.decode(block)) {
                    if (entry.stateIndex()
                        <= response.getStateIndex()) {
                        Integer readMask =
                                readMasks.get(entry.modelId());
                        if (readMask != null) {
                            blockMask |= readMask;
                            progress.merge(
                                    entry.modelId(),
                                    entry.sequenceNumber(),
                                    Math::max);
                        }
                    }
                }
                for (int readIndex = 0;
                     readIndex < reads.size();
                     readIndex++) {
                    if ((blockMask & (1 << readIndex)) != 0) {
                        blocksByRead.get(readIndex).add(block);
                    }
                }
            }
            return new CompactSplitIndex(
                    Map.copyOf(progress),
                    blocksByRead.stream()
                            .map(List::copyOf)
                            .toList());
        }

        private static boolean madeNoProgress(
                GetModelEvents request,
                GetModelEventsResult response,
                Map<String, Long> compactProgress) {
            boolean incomplete = false;
            for (int i = 0;
                 i < request.getRequests().size();
                 i++) {
                ModelEventStreamRequest requested =
                        request.getRequests().get(i);
                ModelEventStream stream =
                        response.getStreams().get(i);
                if (stream != null
                    && !stream.getMemberships().isEmpty()) {
                    return false;
                }
                if (compactProgress.getOrDefault(
                            requested.getModelId(),
                            Long.MIN_VALUE)
                    > requested.getLastSequenceNumber()) {
                    return false;
                }
                incomplete |=
                        requested.getMaxSize() > 0
                        && stream != null
                        && stream.getHead() != null
                        && requested.getLastSequenceNumber()
                           < stream.getHead()
                                   .getSequenceNumber();
            }
            return incomplete;
        }

        private record CompactSplitIndex(
                Map<String, Long> progress,
                List<List<ModelEventDataBlock>> blocksByRead) {

            private static CompactSplitIndex empty(int readCount) {
                List<List<ModelEventDataBlock>> blocksByRead =
                        new ArrayList<>(readCount);
                for (int index = 0; index < readCount; index++) {
                    blocksByRead.add(List.of());
                }
                return new CompactSplitIndex(
                        Map.of(),
                        List.copyOf(blocksByRead));
            }
        }
    }
}
