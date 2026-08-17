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
import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.RequestBatch;
import io.fluxzero.common.api.ResultBatch;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.internal.BinaryWire;
import io.fluxzero.common.api.internal.BinaryWire.Reader;
import io.fluxzero.common.api.internal.BinaryWire.Writer;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private static final int VERSION = 4;
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

    private static boolean isSupportedRequestBatch(RequestBatch<?> batch) {
        if (batch.getRequests().isEmpty()) {
            return false;
        }
        for (JsonType request : batch.getRequests()) {
            if (!(request instanceof CommitModels commit)
                    || Boolean.TRUE.equals(commit.getPossibleDuplicate())
                    || commit.getSubsteps().size() != 1) {
                return false;
            }
            ModelCommitStep step = commit.getSubsteps().getFirst();
            if (step.getEvent() == null || !step.isPublishEvent() || step.getTargets().size() != 1) {
                return false;
            }
            ModelCommitTarget target = step.getTargets().getFirst();
            if (target.getDocument() != null
                    || target.getSnapshot() != null
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
            if (!(value instanceof CommitModelsResult result)
                    || !result.isAccepted()
                    || result.isDuplicate()
                    || !result.hasSingleTargetResult()) {
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
        writeString(output, commit.getCommitId());
        output.writeLong(commit.getReadStateIndex());
        if (!descriptor.readTargetOnly()) {
            writeStrings(output, commit.getReadModelIds());
        }
        output.writeByte(commit.getConflictPolicy() == null ? -1 : commit.getConflictPolicy().ordinal());
        output.writeByte(commit.getGuarantee().ordinal());
        output.writeBoolean(Boolean.FALSE.equals(commit.getPossibleDuplicate()));

        ModelCommitStep step = commit.getSubsteps().getFirst();
        output.writeEnvelope(message);

        ModelCommitTarget target = step.getTargets().getFirst();
        writeString(output, target.getModelId());
        if (!descriptor.sharedModelType()) {
            writeString(output, target.getModelType());
        }
        writeNullableLong(output, target.getExpectedSequenceNumber());
        output.writeBoolean(target.isStoreEvent());
        output.writeBoolean(target.isUpdateState());
        output.writeBoolean(target.isDelete());
    }

    private static RequestBatch<CommitModels> decodeRequests(Reader input) throws IOException {
        int size = readSize(input, MAX_BATCH_SIZE, "batch");
        RequestBatchDescriptor descriptor = RequestBatchDescriptor.read(input);
        List<CommitModels> commits = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long requestId = input.readLong();
            String commitId = readString(input);
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
            Boolean possibleDuplicate = input.readBoolean() ? false : null;
            SerializedMessage event = input.readEnvelope();
            String modelId = readString(input);
            ModelCommitTarget target =
                    ModelCommitTarget.builder()
                            .modelId(modelId)
                            .modelType(
                                    descriptor.sharedModelType()
                                            ? descriptor.modelType()
                                            : readString(input))
                            .expectedSequenceNumber(readNullableLong(input))
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
                            possibleDuplicate));
        }
        return new RequestBatch<>(commits);
    }

    private static byte[] encodeResults(ResultBatch batch) throws IOException {
        int encodedSize = Integer.BYTES + 1 + Integer.BYTES;
        for (var value : batch.getResults()) {
            CommitModelsResult result = (CommitModelsResult) value;
            encodedSize = addSize(encodedSize, Long.BYTES * 6 + 1 + nullableLongSize(
                    result.getSingleTargetEventIndex()));
        }
        Writer output = new Writer(encodedSize, MAX_VALUE_BYTES);
        output.writeInt(RESULT_MAGIC);
        output.writeByte(VERSION);
        output.writeInt(batch.getResults().size());
        for (var value : batch.getResults()) {
            CommitModelsResult result = (CommitModelsResult) value;
            output.writeLong(result.getRequestId());
            output.writeLong(result.getSingleTargetStateIndex());
            writeNullableLong(output, result.getSingleTargetEventIndex());
            output.writeLong(result.getSingleTargetSequenceNumber());
            output.writeBoolean(result.isSingleTargetHistoryComplete());
            output.writeLong(result.getRequestReceivedTimestamp());
            output.writeLong(result.getResponseQueuedTimestamp());
            output.writeLong(result.getResponseSendStartTimestamp());
        }
        return output.toExactByteArray();
    }

    private static ResultBatch decodeResults(Reader input) throws IOException {
        int size = readSize(input, MAX_BATCH_SIZE, "batch");
        List<CommitModelsResult> results = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long requestId = input.readLong();
            long stateIndex = input.readLong();
            Long eventIndex = readNullableLong(input);
            long sequenceNumber = input.readLong();
            boolean historyComplete = input.readBoolean();
            CommitModelsResult result =
                    CommitModelsResult.acceptedSingleTarget(
                            requestId,
                            null,
                            stateIndex,
                            eventIndex,
                            null,
                            sequenceNumber,
                            historyComplete);
            result.setRequestReceivedTimestamp(input.readLong());
            result.setResponseQueuedTimestamp(input.readLong());
            result.setResponseSendStartTimestamp(input.readLong());
            results.add(result);
        }
        return new ResultBatch(new ArrayList<>(results));
    }

    private static void writeStrings(Writer output, List<String> values) {
        output.writeInt(values.size());
        for (String value : values) {
            writeString(output, value);
        }
    }

    private static List<String> readStrings(Reader input) throws IOException {
        int size = readSize(input, MAX_COLLECTION_SIZE, "string collection");
        List<String> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add(readString(input));
        }
        return result;
    }

    private static void writeString(Writer output, String value) {
        output.writeString(value);
    }

    private static String readString(Reader input) throws IOException {
        return input.readString();
    }

    private static void writeNullableLong(Writer output, Long value) {
        output.writeBoolean(value != null);
        if (value != null) {
            output.writeLong(value);
        }
    }

    private static Long readNullableLong(Reader input) throws IOException {
        return input.readBoolean() ? input.readLong() : null;
    }

    private static int readSize(Reader input, int maximum, String description) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > maximum) {
            throw new IOException("Invalid compact model commit " + description + " size " + size);
        }
        return size;
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
        size = addSize(size, stringSize(commit.getCommitId()));
        size = addSize(size, Long.BYTES);
        if (!descriptor.readTargetOnly()) {
            size = addSize(size, stringsSize(commit.getReadModelIds()));
        }
        size = addSize(size, 3); // conflict policy, guarantee and possible-duplicate marker
        ModelCommitStep step = commit.getSubsteps().getFirst();
        size = addSize(size, addSize(Integer.BYTES, message.envelopeSize()));
        ModelCommitTarget target = step.getTargets().getFirst();
        size = addSize(size, stringSize(target.getModelId()));
        if (!descriptor.sharedModelType()) {
            size = addSize(size, stringSize(target.getModelType()));
        }
        size = addSize(size, nullableLongSize(target.getExpectedSequenceNumber()));
        return addSize(size, 3); // store event, update state and delete
    }

    private static int stringsSize(List<String> values) {
        int size = Integer.BYTES;
        for (String value : values) {
            size = addSize(size, stringSize(value));
        }
        return size;
    }

    private static int stringSize(String value) {
        return addSize(Integer.BYTES, value == null ? 0 : utf8Size(value));
    }

    private static int utf8Size(String value) {
        int size = 0;
        int index = 0;
        while (index <= value.length() - Integer.BYTES) {
            char first = value.charAt(index);
            char second = value.charAt(index + 1);
            char third = value.charAt(index + 2);
            char fourth = value.charAt(index + 3);
            if ((first | second | third | fourth) > 0x7f) {
                break;
            }
            size = addSize(size, Integer.BYTES);
            index += Integer.BYTES;
        }
        for (; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current <= 0x7f) {
                size = addSize(size, 1);
            } else if (current <= 0x7ff) {
                size = addSize(size, 2);
            } else if (Character.isHighSurrogate(current)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                size = addSize(size, 4);
                index++;
            } else if (Character.isSurrogate(current)) {
                size = addSize(size, 1); // Standard UTF-8 replacement byte ('?')
            } else {
                size = addSize(size, 3);
            }
        }
        return size;
    }

    private static int nullableLongSize(Long value) {
        return 1 + (value == null ? 0 : Long.BYTES);
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
                SerializedMessage message = SerializedMessage.encode(
                        commit.getSubsteps().getFirst().getEvent());
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
                    has(flags, SHARED_MODEL_TYPE) ? readString(input) : null);
        }

        private void writeSharedValues(Writer output) {
            if (sharedModelType()) {
                writeString(output, modelType);
            }
        }

        private int sharedValuesSize() {
            return sharedModelType() ? stringSize(modelType) : 0;
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
