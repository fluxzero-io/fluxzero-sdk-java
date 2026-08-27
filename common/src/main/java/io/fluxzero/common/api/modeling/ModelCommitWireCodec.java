/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.common.api.modeling;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.AbstractRequestResult;
import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.RequestBatch;
import io.fluxzero.common.api.RequestResult;
import io.fluxzero.common.api.ResultBatch;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.internal.BinaryWire;
import io.fluxzero.common.api.internal.BinaryWire.Reader;
import io.fluxzero.common.api.internal.BinaryWire.Writer;
import lombok.Value;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compact websocket representation for the common one-event, one-target model commit.
 *
 * <p>The regular polymorphic JSON/CBOR representation remains authoritative and is used
 * automatically for every commit shape this codec does not support. This format only removes
 * repeated object descriptors from homogeneous transport batches; it does not combine logical
 * commits or alter their individual request IDs, idempotency keys, results, or transaction
 * boundaries.
 */
public final class ModelCommitWireCodec {

    private static final int REQUEST_MAGIC = 0x465A4D43; // FZMC
    private static final int RESULT_MAGIC = 0x465A4D52; // FZMR
    private static final int VERSION = 5;
    private static final int SHARED_MODEL_TYPE = 1;
    private static final int READ_TARGET_ONLY = 1 << 1;
    private static final int MAX_BATCH_SIZE = 1_000_000;
    private static final int MAX_COLLECTION_SIZE = 1_000_000;
    private static final int MAX_VALUE_BYTES = 256 * 1024 * 1024;
    private static final ModelConflictPolicy[] CONFLICT_POLICIES = ModelConflictPolicy.values();
    private static final Guarantee[] GUARANTEES = Guarantee.values();

    private ModelCommitWireCodec() {
    }

    /**
     * Encodes a supported homogeneous request or result batch, returning {@code null} when the
     * ordinary transport codec should be used.
     */
    public static byte[] tryEncode(JsonType value) throws IOException {
        if (value instanceof RequestBatch<?> batch && isSupportedRequestBatch(batch)) {
            return encodeRequests(batch);
        }
        if (value instanceof ResultBatch batch && isSupportedResultBatch(batch)) {
            return encodeResults(batch);
        }
        if (value instanceof RequestResult result && isCompactResult(result)) {
            return encodeResults(new ResultBatch(List.of(result)));
        }
        return null;
    }

    /**
     * Decodes a compact batch, returning {@code null} when the payload uses the ordinary transport
     * representation.
     */
    public static JsonType tryDecode(byte[] bytes) throws IOException {
        if (bytes.length < Integer.BYTES + 1) {
            return null;
        }
        int magic = BinaryWire.peekInt(bytes, 0);
        if (magic != REQUEST_MAGIC && magic != RESULT_MAGIC) {
            return null;
        }
        try {
            Reader input = new Reader(bytes, MAX_VALUE_BYTES);
            input.readInt();
            int version = input.readUnsignedByte();
            if (version != VERSION) {
                throw new IOException("Unsupported compact model commit wire version " + version);
            }
            JsonType result = magic == REQUEST_MAGIC
                    ? decodeRequests(input) : decodeResults(input);
            if (input.available() != 0) {
                throw new IOException("Unexpected trailing compact model commit bytes");
            }
            return result;
        } catch (EOFException e) {
            throw new IOException("Truncated compact model commit batch", e);
        }
    }

    /**
     * Completes a compact decoded result with identities owned by its correlated request.
     * Other result representations are returned unchanged.
     */
    public static RequestResult restoreResultContext(
            RequestResult candidate, CommitModels request) {
        if (!(candidate instanceof CompactSingleTargetResult result)
                || request == null) {
            return candidate;
        }
        ModelCommitTarget target = request.singleTarget();
        if (target == null) {
            throw new IllegalStateException(
                    "Compact model commit result requires a single-target request");
        }
        CommitModelsResult restored = CommitModelsResult.acceptedSingleTarget(
                result.requestId,
                request.getCommitId(),
                result.stateIndex,
                result.eventIndex,
                target.getModelId(),
                result.sequenceNumber,
                result.historyComplete);
        restored.setRequestReceivedTimestamp(result.getRequestReceivedTimestamp());
        restored.setResponseQueuedTimestamp(result.getResponseQueuedTimestamp());
        restored.setResponseSendStartTimestamp(result.getResponseSendStartTimestamp());
        return restored;
    }

    /**
     * Creates the compact transport form of an accepted single-target result without the durable
     * commit and model identities owned by its correlated request. The SDK restores those
     * identities before publication.
     */
    public static RequestResult compactAcceptedResult(
            long requestId,
            long stateIndex,
            Long eventIndex,
            long sequenceNumber,
            boolean historyComplete) {
        return new CompactSingleTargetResult(
                requestId, stateIndex, eventIndex, sequenceNumber, historyComplete);
    }

    /** Returns whether a decoded transport result still needs its correlated request context. */
    public static boolean requiresRequestContext(RequestResult candidate) {
        return candidate instanceof CompactSingleTargetResult;
    }

    /** Returns whether a result can use the compact single-target wire representation. */
    public static boolean isCompactResult(RequestResult candidate) {
        if (requiresRequestContext(candidate)) {
            return true;
        }
        return candidate instanceof CommitModelsResult result
                && result.isAccepted()
                && !result.isDuplicate()
                && result.hasSingleTargetResult();
    }

    private static boolean isSupportedRequestBatch(RequestBatch<?> batch) {
        if (batch.getRequests().isEmpty()) {
            return false;
        }
        for (JsonType request : batch.getRequests()) {
            if (!(request instanceof CommitModels commit)) {
                return false;
            }
            if (commit.isMigration()) {
                return false;
            }
            ModelCommitTarget target = commit.singleTarget();
            if (target == null) {
                return false;
            }
            ModelCommitStep step = commit.getSubsteps().getFirst();
            if (step.getEvent() == null || !step.isPublishEvent()) {
                return false;
            }
            if (target.getDocument() != null
                    || target.getSnapshot() != null
                    || target.getModelType() == null
                    || target.getModelType().isBlank()
                    || target.isCascadeDelete()
                    || target.isUpdateRelationships()
                    || !target.getRelationships().isEmpty()
                    || target.getAliases() != null) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSupportedResultBatch(ResultBatch batch) {
        if (batch.getResults().isEmpty()) {
            return false;
        }
        for (var value : batch.getResults()) {
            if (!isCompactResult(value)) {
                return false;
            }
        }
        return true;
    }

    private static byte[] encodeRequests(RequestBatch<?> batch) throws IOException {
        RequestEncodingPlan plan = RequestEncodingPlan.of(batch);
        RequestBatchDescriptor descriptor = plan.descriptor();
        Writer output = new Writer(plan.encodedSize(), MAX_VALUE_BYTES);
        output.writeInt(REQUEST_MAGIC);
        output.writeByte(VERSION);
        output.writeInt(batch.getRequests().size());
        output.writeByte(descriptor.flags());
        descriptor.writeSharedValues(output);
        for (int index = 0; index < batch.getRequests().size(); index++) {
            writeCommit(output, (CommitModels) batch.getRequests().get(index), descriptor,
                        plan.messages()[index]);
        }
        return output.toExactByteArray();
    }

    private static void writeCommit(
            Writer output, CommitModels commit, RequestBatchDescriptor descriptor,
            SerializedMessage message) {
        output.writeLong(commit.getRequestId());
        output.writeString(commit.getCommitId());
        output.writeLong(commit.getReadStateIndex());
        if (!descriptor.readTargetOnly()) {
            writeStrings(output, commit.getReadModelIds());
        }
        output.writeByte(commit.getConflictPolicy() == null ? -1 : commit.getConflictPolicy().ordinal());
        output.writeByte(commit.getGuarantee().ordinal());
        output.writeBoolean(commit.isPossibleDuplicate());

        ModelCommitStep step = commit.getSubsteps().getFirst();
        output.writeEnvelope(message);

        ModelCommitTarget target = step.getTargets().getFirst();
        output.writeString(target.getModelId());
        if (!descriptor.sharedModelType()) {
            output.writeString(target.getModelType());
        }
        output.writeNullableLong(target.getExpectedSequenceNumber());
        output.writeBoolean(target.isStoreEvent());
        output.writeBoolean(target.isUpdateState());
        output.writeBoolean(target.isDelete());
    }

    private static RequestBatch<CommitModels> decodeRequests(Reader input) throws IOException {
        int size = input.readSize(MAX_BATCH_SIZE, "model commit batch");
        RequestBatchDescriptor descriptor = RequestBatchDescriptor.read(input);
        List<CommitModels> commits = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long requestId = input.readLong();
            String commitId = input.readString();
            long readStateIndex = input.readLong();
            List<String> readModelIds =
                    descriptor.readTargetOnly() ? null : readStrings(input);
            int conflictOrdinal = input.readByte();
            ModelConflictPolicy conflictPolicy =
                    conflictOrdinal < 0
                            ? null
                            : enumValue(CONFLICT_POLICIES, conflictOrdinal, "conflict policy");
            Guarantee guarantee =
                    enumValue(GUARANTEES, input.readUnsignedByte(), "guarantee");
            boolean possibleDuplicate = input.readBoolean();
            SerializedMessage event = input.readEnvelope();
            String modelId = input.readString();
            ModelCommitTarget target =
                    ModelCommitTarget.builder()
                            .modelId(modelId)
                            .modelType(
                                    descriptor.sharedModelType()
                                            ? descriptor.modelType()
                                            : input.readString())
                            .expectedSequenceNumber(input.readNullableLong())
                            .storeEvent(input.readBoolean())
                            .updateState(input.readBoolean())
                            .delete(input.readBoolean())
                            .relationships(List.of())
                            .build();
            commits.add(
                    new CommitModels(
                            requestId,
                            commitId,
                            readStateIndex,
                            descriptor.readTargetOnly() ? List.of(modelId) : readModelIds,
                            List.of(
                                    ModelCommitStep.builder()
                                            .event(event)
                                            .publishEvent(true)
                                            .targets(List.of(target))
                                            .build()),
                            conflictPolicy,
                            guarantee,
                            possibleDuplicate,
                            false));
        }
        return new RequestBatch<>(commits);
    }

    private static byte[] encodeResults(ResultBatch batch) throws IOException {
        int encodedSize = Integer.BYTES + 1 + Integer.BYTES;
        for (var value : batch.getResults()) {
            encodedSize = addSize(encodedSize, Long.BYTES * 6 + 1 + BinaryWire.nullableLongSize(
                    eventIndex(value)));
        }
        Writer output = new Writer(encodedSize, MAX_VALUE_BYTES);
        output.writeInt(RESULT_MAGIC);
        output.writeByte(VERSION);
        output.writeInt(batch.getResults().size());
        for (var value : batch.getResults()) {
            output.writeLong(value.getRequestId());
            output.writeLong(stateIndex(value));
            output.writeNullableLong(eventIndex(value));
            output.writeLong(sequenceNumber(value));
            output.writeBoolean(historyComplete(value));
            output.writeLong(value.getRequestReceivedTimestamp());
            output.writeLong(value.getResponseQueuedTimestamp());
            output.writeLong(value.getResponseSendStartTimestamp());
        }
        return output.toExactByteArray();
    }

    private static ModelUpdate update(RequestResult value) {
        return ((CommitModelsResult) value).getUpdates().getFirst();
    }

    private static long stateIndex(RequestResult value) {
        return value instanceof CompactSingleTargetResult result
                ? result.stateIndex : update(value).getStateIndex();
    }

    private static Long eventIndex(RequestResult value) {
        return value instanceof CompactSingleTargetResult result
                ? result.eventIndex : update(value).getEventIndex();
    }

    private static long sequenceNumber(RequestResult value) {
        return value instanceof CompactSingleTargetResult result
                ? result.sequenceNumber
                : update(value).getTargets().getFirst().getSequenceNumber();
    }

    private static boolean historyComplete(RequestResult value) {
        return value instanceof CompactSingleTargetResult result
                ? result.historyComplete
                : update(value).getTargets().getFirst().isHistoryComplete();
    }

    private static ResultBatch decodeResults(Reader input) throws IOException {
        int size = input.readSize(MAX_BATCH_SIZE, "model commit batch");
        List<RequestResult> results = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            CompactSingleTargetResult result = new CompactSingleTargetResult(
                    input.readLong(),
                    input.readLong(),
                    input.readNullableLong(),
                    input.readLong(),
                    input.readBoolean());
            result.setRequestReceivedTimestamp(input.readLong());
            result.setResponseQueuedTimestamp(input.readLong());
            result.setResponseSendStartTimestamp(input.readLong());
            results.add(result);
        }
        return new ResultBatch(results);
    }

    /** Transport-only scalar state that must not cross the request-correlation boundary. */
    @Value
    private static final class CompactSingleTargetResult
            extends AbstractRequestResult {
        long requestId;
        long stateIndex;
        Long eventIndex;
        long sequenceNumber;
        boolean historyComplete;
        long timestamp = System.currentTimeMillis();

        @Override
        public Object toMetric() {
            return new CommitModelsResult.Metric(
                    1, 1, 0, false, false, false, timestamp);
        }
    }

    private static void writeStrings(Writer output, List<String> values) {
        output.writeInt(values.size());
        for (String value : values) {
            output.writeString(value);
        }
    }

    private static List<String> readStrings(Reader input) throws IOException {
        int size = input.readSize(MAX_COLLECTION_SIZE, "model commit string collection");
        List<String> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add(input.readString());
        }
        return result;
    }

    private static <T> T enumValue(T[] values, int ordinal, String description) throws IOException {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IOException("Invalid compact model commit " + description + " " + ordinal);
        }
        return values[ordinal];
    }

    private static int commitSize(
            CommitModels commit,
            RequestBatchDescriptor descriptor,
            SerializedMessage message) {
        int size = Long.BYTES;
        size = addSize(size, BinaryWire.stringSize(commit.getCommitId()));
        size = addSize(size, Long.BYTES);
        if (!descriptor.readTargetOnly()) {
            size = addSize(size, stringsSize(commit.getReadModelIds()));
        }
        size = addSize(size, 3); // conflict policy, guarantee and possible-duplicate marker
        ModelCommitStep step = commit.getSubsteps().getFirst();
        size = addSize(size, BinaryWire.nestedEnvelopeSize(message));
        ModelCommitTarget target = step.getTargets().getFirst();
        size = addSize(size, BinaryWire.stringSize(target.getModelId()));
        if (!descriptor.sharedModelType()) {
            size = addSize(size, BinaryWire.stringSize(target.getModelType()));
        }
        size = addSize(size, BinaryWire.nullableLongSize(target.getExpectedSequenceNumber()));
        return addSize(size, 3); // store event, update state and delete
    }

    private static int stringsSize(List<String> values) {
        int size = Integer.BYTES;
        for (String value : values) {
            size = addSize(size, BinaryWire.stringSize(value));
        }
        return size;
    }

    private static int addSize(int current, int addition) {
        try {
            return Math.addExact(current, addition);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Compact model commit batch exceeds maximum byte array size", e);
        }
    }

    private record RequestEncodingPlan(
            RequestBatchDescriptor descriptor,
            SerializedMessage[] messages,
            int encodedSize) {

        private static RequestEncodingPlan of(RequestBatch<?> batch) {
            RequestBatchDescriptor descriptor = RequestBatchDescriptor.of(batch);
            SerializedMessage[] messages = new SerializedMessage[batch.getRequests().size()];
            int size = Integer.BYTES + 1 + Integer.BYTES + 1;
            size = addSize(size, descriptor.sharedValuesSize());
            for (int index = 0; index < batch.getRequests().size(); index++) {
                CommitModels commit = (CommitModels) batch.getRequests().get(index);
                SerializedMessage message = commit.getSubsteps().getFirst().getEvent();
                messages[index] = message;
                size = addSize(size, commitSize(commit, descriptor, message));
            }
            return new RequestEncodingPlan(descriptor, messages, size);
        }
    }

    private record RequestBatchDescriptor(
            int flags,
            String modelType) {

        private static RequestBatchDescriptor of(RequestBatch<?> batch) {
            CommitModels first = (CommitModels) batch.getRequests().getFirst();
            ModelCommitTarget firstTarget = first.getSubsteps().getFirst().getTargets().getFirst();
            String modelType = firstTarget.getModelType();
            int flags = SHARED_MODEL_TYPE | READ_TARGET_ONLY;
            for (JsonType value : batch.getRequests()) {
                CommitModels commit = (CommitModels) value;
                ModelCommitTarget target = commit.getSubsteps().getFirst().getTargets().getFirst();
                if (!Objects.equals(modelType, target.getModelType())) {
                    flags &= ~SHARED_MODEL_TYPE;
                }
                if (commit.getReadModelIds().size() != 1
                        || !Objects.equals(commit.getReadModelIds().getFirst(), target.getModelId())) {
                    flags &= ~READ_TARGET_ONLY;
                }
            }
            return new RequestBatchDescriptor(flags, modelType);
        }

        private static RequestBatchDescriptor read(Reader input) throws IOException {
            int flags = input.readUnsignedByte();
            int supportedFlags = SHARED_MODEL_TYPE | READ_TARGET_ONLY;
            if ((flags & ~supportedFlags) != 0) {
                throw new IOException("Invalid compact model commit request descriptor " + flags);
            }
            return new RequestBatchDescriptor(
                    flags,
                    has(flags, SHARED_MODEL_TYPE) ? input.readString() : null);
        }

        private void writeSharedValues(Writer output) {
            if (sharedModelType()) {
                output.writeString(modelType);
            }
        }

        private int sharedValuesSize() {
            return sharedModelType() ? BinaryWire.stringSize(modelType) : 0;
        }

        private boolean sharedModelType() {
            return has(flags, SHARED_MODEL_TYPE);
        }

        private boolean readTargetOnly() {
            return has(flags, READ_TARGET_ONLY);
        }

        private static boolean has(int flags, int flag) {
            return (flags & flag) != 0;
        }
    }

}
