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

package io.fluxzero.common.api.modeling;

import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.serialization.SerializedMessagePackCodec;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Expands the persisted blocks in a canonical model-event page into its logical payloads and stream memberships.
 */
public final class ModelEventPageDecoder {

    private ModelEventPageDecoder() {
    }

    /**
     * Returns the logical page for a request. Already expanded pages are returned unchanged.
     */
    public static GetModelEventsResult expand(
            GetModelEvents request, GetModelEventsResult result) {
        List<ModelEventPayloadBlock> payloadBlocks = result.getPayloadBlocks();
        List<ModelEventDataBlock> membershipBlocks = result.getMembershipBlocks();
        if (payloadBlocks.isEmpty() && membershipBlocks.isEmpty()) {
            return result;
        }
        long[] stateIndices = result.getPayloadStateIndices();
        List<ModelEventPayload> expanded =
                new ArrayList<>(result.getPayloads().size() + stateIndices.length);
        LongSet selectedStates =
                new LongSet(result.getPayloads().size() + stateIndices.length);
        for (ModelEventPayload payload : result.getPayloads()) {
            if (!selectedStates.add(payload.getStateIndex())) {
                throw new IllegalStateException(
                        "Duplicate model-event payload at state index "
                        + payload.getStateIndex());
            }
            expanded.add(payload);
        }
        if (!payloadBlocks.isEmpty()) {
            expandPayloads(
                    payloadBlocks, result.getPayloadEventIndices(), stateIndices,
                    selectedStates, expanded);
        }
        List<ModelEventStream> streams =
                expandMemberships(request, result, membershipBlocks, selectedStates);
        boolean ordered = true;
        for (int i = 1; i < expanded.size(); i++) {
            if (expanded.get(i - 1).getStateIndex()
                > expanded.get(i).getStateIndex()) {
                ordered = false;
                break;
            }
        }
        if (!ordered) {
            expanded.sort(Comparator.comparingLong(ModelEventPayload::getStateIndex));
        }
        GetModelEventsResult expandedResult =
                new GetModelEventsResult(
                        result.getRequestId(), result.getStateIndex(),
                        result.isExactBoundary(),
                        List.copyOf(expanded), streams);
        expandedResult.setRequestReceivedTimestamp(result.getRequestReceivedTimestamp());
        expandedResult.setResponseQueuedTimestamp(result.getResponseQueuedTimestamp());
        expandedResult.setResponseSendStartTimestamp(result.getResponseSendStartTimestamp());
        return expandedResult;
    }

    private static void expandPayloads(
            List<ModelEventPayloadBlock> payloadBlocks,
            long[] eventIndices,
            long[] stateIndices,
            LongSet selectedStates,
            List<ModelEventPayload> expanded) {
        for (int i = 1; i < eventIndices.length; i++) {
            if (eventIndices[i] <= eventIndices[i - 1]) {
                throw new IllegalStateException(
                        "Packed model-event indices are not strictly increasing");
            }
        }
        int selected = 0;
        List<DecodedPayloadBlock> decodedBlocks =
                payloadBlocks.size() < 8
                        ? payloadBlocks.stream().map(ModelEventPageDecoder::decodePayloadBlock).toList()
                        : payloadBlocks.parallelStream().map(ModelEventPageDecoder::decodePayloadBlock).toList();
        for (DecodedPayloadBlock decoded : decodedBlocks) {
            ModelEventPayloadBlock block = decoded.block();
            List<SerializedMessage> messages = decoded.messages();
            if (messages.size() != block.getMessageCount()) {
                throw new IllegalStateException(
                        "Packed model-event block at %d contains %d messages instead of %d"
                                .formatted(
                                        block.getFirstIndex(), messages.size(),
                                        block.getMessageCount()));
            }
            for (int ordinal = 0; ordinal < messages.size(); ordinal++) {
                SerializedMessage message = messages.get(ordinal);
                long eventIndex = message.getIndex() == null
                        ? block.getFirstIndex() + ordinal : message.getIndex();
                while (selected < eventIndices.length
                       && eventIndices[selected] < eventIndex) {
                    throw new IllegalStateException(
                            "Packed model-event blocks do not contain selected event "
                            + eventIndices[selected]);
                }
                if (selected < eventIndices.length
                    && eventIndices[selected] == eventIndex) {
                    long stateIndex = stateIndices[selected];
                    message.setIndex(eventIndex);
                    if (!selectedStates.add(stateIndex)) {
                        throw new IllegalStateException(
                                "Duplicate model-event payload at state index " + stateIndex);
                    }
                    expanded.add(new ModelEventPayload(stateIndex, message));
                    selected++;
                }
            }
        }
        if (selected != eventIndices.length) {
            throw new IllegalStateException(
                    "Packed model-event blocks contain %d of %d selected events"
                            .formatted(selected, eventIndices.length));
        }
    }

    private static List<ModelEventStream> expandMemberships(
            GetModelEvents request,
            GetModelEventsResult result,
            List<ModelEventDataBlock> blocks,
            LongSet selectedStateIndices) {
        if (blocks.isEmpty()) {
            return result.getStreams();
        }
        if (request.getRequests().size() != result.getStreams().size()) {
            throw new IllegalStateException(
                    "Model-event request and response contain different stream counts");
        }
        Map<String, List<Integer>> ordinalsByModel = new HashMap<>();
        List<List<ModelEventMembership>> memberships =
                new ArrayList<>(result.getStreams().size());
        for (int ordinal = 0; ordinal < result.getStreams().size(); ordinal++) {
            ModelEventStream stream = result.getStreams().get(ordinal);
            String storedId = stream.getHead() == null
                    ? stream.getModelId() : stream.getHead().getModelId();
            ordinalsByModel.computeIfAbsent(
                    storedId, ignored -> new ArrayList<>()).add(ordinal);
            memberships.add(new ArrayList<>(stream.getMemberships()));
        }
        List<List<ModelStreamBatchDecoder.Entry>> decodedBlocks =
                blocks.size() < 8
                        ? blocks.stream().map(ModelStreamBatchDecoder::decode).toList()
                        : blocks.parallelStream().map(ModelStreamBatchDecoder::decode).toList();
        for (List<ModelStreamBatchDecoder.Entry> block : decodedBlocks) {
            for (ModelStreamBatchDecoder.Entry entry : block) {
                for (int ordinal : ordinalsByModel.getOrDefault(
                        entry.modelId(), List.of())) {
                    ModelEventStreamRequest stream = request.getRequests().get(ordinal);
                    if (stream.getMaxSize() > 0
                        && entry.sequenceNumber() > stream.getLastSequenceNumber()
                        && entry.stateIndex() <= result.getStateIndex()
                        && selectedStateIndices.contains(entry.stateIndex())) {
                        memberships.get(ordinal).add(
                                new ModelEventMembership(
                                        entry.sequenceNumber(), entry.stateIndex(),
                                        entry.readStateIndex(), entry.commitId(), entry.substep()));
                    }
                }
            }
        }
        List<ModelEventStream> expanded = new ArrayList<>(result.getStreams().size());
        for (int ordinal = 0; ordinal < result.getStreams().size(); ordinal++) {
            ModelEventStream existing = result.getStreams().get(ordinal);
            ModelEventStreamRequest requested = request.getRequests().get(ordinal);
            List<ModelEventMembership> selected = memberships.get(ordinal);
            selected.sort(
                    Comparator.comparingLong(ModelEventMembership::getSequenceNumber)
                            .thenComparingLong(ModelEventMembership::getStateIndex));
            if (selected.size() > requested.getMaxSize()) {
                selected = new ArrayList<>(selected.subList(0, requested.getMaxSize()));
            }
            expanded.add(
                    new ModelEventStream(
                            existing.getModelId(), existing.getHead(), List.copyOf(selected)));
        }
        return List.copyOf(expanded);
    }

    private static DecodedPayloadBlock decodePayloadBlock(ModelEventPayloadBlock block) {
        byte[] data = block.isCompressed()
                ? CompressionAlgorithm.ZSTD.decompress(block.getData()) : block.getData();
        return new DecodedPayloadBlock(block, SerializedMessagePackCodec.decode(data));
    }

    private record DecodedPayloadBlock(
            ModelEventPayloadBlock block,
            List<SerializedMessage> messages) {
    }

    private static final class LongSet {
        private static final long EMPTY = Long.MIN_VALUE;

        private final long[] values;
        private final int mask;

        private LongSet(int expectedSize) {
            int capacity = 1;
            int required = Math.max(2, (int) Math.ceil(expectedSize / 0.6d));
            while (capacity < required) {
                capacity = Math.multiplyExact(capacity, 2);
            }
            values = new long[capacity];
            java.util.Arrays.fill(values, EMPTY);
            mask = capacity - 1;
        }

        private boolean add(long value) {
            if (value == EMPTY) {
                throw new IllegalArgumentException(
                        "Long.MIN_VALUE is not a valid model state index");
            }
            int slot = mix(value) & mask;
            while (true) {
                long present = values[slot];
                if (present == EMPTY) {
                    values[slot] = value;
                    return true;
                }
                if (present == value) {
                    return false;
                }
                slot = slot + 1 & mask;
            }
        }

        private boolean contains(long value) {
            int slot = mix(value) & mask;
            while (true) {
                long present = values[slot];
                if (present == EMPTY) {
                    return false;
                }
                if (present == value) {
                    return true;
                }
                slot = slot + 1 & mask;
            }
        }

        private static int mix(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdl;
            value ^= value >>> 33;
            return (int) (value ^ value >>> 32);
        }
    }
}
