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
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.JsonType;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.RequestBatch;
import io.fluxzero.common.api.ResultBatch;
import io.fluxzero.common.api.SerializedMessage;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final int VERSION = 3;
    private static final int NATIVE_VERSION = 4;
    private static final int IDENTIFIED_RESULT_VERSION = 2;
    private static final int SHARED_DATA_TYPE = 1;
    private static final int SHARED_DATA_FORMAT = 1 << 1;
    private static final int SHARED_MODEL_TYPE = 1 << 2;
    private static final int SHARED_SOURCE = 1 << 3;
    private static final int SHARED_TARGET = 1 << 4;
    private static final int READ_TARGET_ONLY = 1 << 5;
    private static final int EVENT_ID_IS_COMMIT_ID = 1 << 6;
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
            return encodeRequests(batch, false);
        }
        if (value instanceof ResultBatch batch && isSupportedResultBatch(batch)) {
            return encodeResults(batch);
        }
        return null;
    }

    /**
     * Encodes supported model commits with self-contained native event envelopes.
     * Results and unsupported shapes retain their existing representation.
     */
    public static byte[] tryEncodeNative(JsonType value) throws IOException {
        if (value instanceof RequestBatch<?> batch && isSupportedRequestBatch(batch)) {
            return encodeRequests(batch, true);
        }
        return tryEncode(value);
    }

    /**
     * Decodes a compact batch, returning {@code null} when the payload uses the ordinary transport
     * representation.
     */
    public static JsonType tryDecode(byte[] bytes) throws IOException {
        return tryDecode(bytes, false);
    }

    /**
     * Decodes both native and legacy compact model commit representations.
     */
    public static JsonType tryDecodeNative(byte[] bytes) throws IOException {
        return tryDecode(bytes, true);
    }

    private static JsonType tryDecode(byte[] bytes, boolean nativeVersionAllowed) throws IOException {
        if (bytes.length < Integer.BYTES + 1) {
            return null;
        }
        int magic = readInt(bytes, 0);
        if (magic != REQUEST_MAGIC && magic != RESULT_MAGIC) {
            return null;
        }
        try {
            BinaryReader input = new BinaryReader(bytes);
            input.readInt();
            int version = input.readUnsignedByte();
            if (version < IDENTIFIED_RESULT_VERSION
                    || version > VERSION && (!nativeVersionAllowed || version != NATIVE_VERSION)) {
                throw new IOException("Unsupported compact model commit wire version " + version);
            }
            JsonType result = magic == REQUEST_MAGIC
                    ? decodeRequests(input, version) : decodeResults(input, version);
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
                    || target.isUpdateRelationships()
                    || !target.getRelationships().isEmpty()) {
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

    private static byte[] encodeRequests(RequestBatch<?> batch, boolean nativeMessages) throws IOException {
        RequestEncodingPlan plan = RequestEncodingPlan.of(batch, nativeMessages);
        RequestBatchDescriptor descriptor = plan.descriptor();
        BinaryWriter output = new BinaryWriter(plan.encodedSize());
        output.writeInt(REQUEST_MAGIC);
        output.writeByte(nativeMessages ? NATIVE_VERSION : VERSION);
        output.writeInt(batch.getRequests().size());
        output.writeByte(descriptor.flags());
        descriptor.writeSharedValues(output);
        for (int index = 0; index < batch.getRequests().size(); index++) {
            writeCommit(output, (CommitModels) batch.getRequests().get(index), descriptor,
                        nativeMessages ? plan.nativeMessages()[index] : null,
                        nativeMessages ? null : plan.metadataBytes()[index]);
        }
        return output.toByteArray();
    }

    private static void writeCommit(
            BinaryWriter output, CommitModels commit, RequestBatchDescriptor descriptor,
            SerializedMessage nativeMessage, byte[] metadataBytes) {
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
        if (nativeMessage != null) {
            writeNativeMessage(output, nativeMessage);
        } else {
            writeMessage(output, step.getEvent(), commit.getCommitId(), descriptor, metadataBytes);
        }

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

    private static void writeMessage(
            BinaryWriter output,
            SerializedMessage message,
            String commitId,
            RequestBatchDescriptor descriptor,
            byte[] metadataBytes) {
        Data<byte[]> data = message.getData();
        writeBytes(output, data.getValue());
        if (!descriptor.sharedDataType()) {
            writeString(output, data.getType());
        }
        output.writeInt(data.getRevision());
        if (!descriptor.sharedDataFormat()) {
            writeString(output, data.getFormat());
        }

        output.write(metadataBytes);
        writeNullableInt(output, message.getSegment());
        writeNullableLong(output, message.getIndex());
        if (!descriptor.sharedSource()) {
            writeString(output, message.getSource());
        }
        if (!descriptor.sharedTarget()) {
            writeString(output, message.getTarget());
        }
        writeNullableInt(output, message.getRequestId());
        writeNullableLong(output, message.getTimestamp());
        if (!descriptor.eventIdIsCommitId()) {
            writeString(output, message.getMessageId());
        }
    }

    private static void writeNativeMessage(BinaryWriter output, SerializedMessage nativeMessage) {
        output.writeInt(nativeMessage.envelopeSize());
        output.ensureCapacity(nativeMessage.envelopeSize());
        nativeMessage.copyEnvelopeTo(output.bytes, output.position);
        output.position += nativeMessage.envelopeSize();
    }

    private static RequestBatch<CommitModels> decodeRequests(
            BinaryReader input, int version) throws IOException {
        int size = readSize(input, MAX_BATCH_SIZE, "batch");
        RequestBatchDescriptor descriptor = RequestBatchDescriptor.read(input, version);
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
            SerializedMessage event = version == NATIVE_VERSION
                    ? input.readNativeMessage() : readMessage(input, commitId, descriptor);
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

    private static SerializedMessage readMessage(
            BinaryReader input,
            String commitId,
            RequestBatchDescriptor descriptor) throws IOException {
        Data<byte[]> data =
                new Data<>(
                        readBytes(input),
                        descriptor.sharedDataType()
                                ? descriptor.dataType()
                                : readString(input),
                        input.readInt(),
                        descriptor.sharedDataFormat()
                                ? descriptor.dataFormat()
                                : readString(input));
        Metadata metadata = input.readMetadata();
        return new SerializedMessage(
                data,
                metadata,
                readNullableInt(input),
                readNullableLong(input),
                descriptor.sharedSource() ? descriptor.source() : readString(input),
                descriptor.sharedTarget() ? descriptor.target() : readString(input),
                readNullableInt(input),
                readNullableLong(input),
                descriptor.eventIdIsCommitId()
                        ? commitId : readString(input),
                null);
    }

    private static byte[] encodeResults(ResultBatch batch) throws IOException {
        int encodedSize = Integer.BYTES + 1 + Integer.BYTES;
        for (var value : batch.getResults()) {
            CommitModelsResult result = (CommitModelsResult) value;
            encodedSize = addSize(encodedSize, Long.BYTES * 6 + 1 + nullableLongSize(
                    result.getSingleTargetEventIndex()));
        }
        BinaryWriter output = new BinaryWriter(encodedSize);
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
        return output.toByteArray();
    }

    private static ResultBatch decodeResults(
            BinaryReader input, int version) throws IOException {
        int size = readSize(input, MAX_BATCH_SIZE, "batch");
        List<CommitModelsResult> results = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long requestId = input.readLong();
            String commitId = version <= IDENTIFIED_RESULT_VERSION
                    ? readString(input) : null;
            long stateIndex = input.readLong();
            Long eventIndex = readNullableLong(input);
            String modelId = version <= IDENTIFIED_RESULT_VERSION
                    ? readString(input) : null;
            long sequenceNumber = input.readLong();
            boolean historyComplete = input.readBoolean();
            CommitModelsResult result =
                    CommitModelsResult.acceptedSingleTarget(
                            requestId,
                            commitId,
                            stateIndex,
                            eventIndex,
                            modelId,
                            sequenceNumber,
                            historyComplete);
            result.setRequestReceivedTimestamp(input.readLong());
            result.setResponseQueuedTimestamp(input.readLong());
            result.setResponseSendStartTimestamp(input.readLong());
            results.add(result);
        }
        return new ResultBatch(new ArrayList<>(results));
    }

    private static void writeStrings(BinaryWriter output, List<String> values) {
        output.writeInt(values.size());
        for (String value : values) {
            writeString(output, value);
        }
    }

    private static List<String> readStrings(BinaryReader input) throws IOException {
        int size = readSize(input, MAX_COLLECTION_SIZE, "string collection");
        List<String> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add(readString(input));
        }
        return result;
    }

    private static void writeBytes(BinaryWriter output, byte[] value) {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBytes(BinaryReader input) throws IOException {
        int size = input.readInt();
        if (size == -1) {
            return null;
        }
        if (size < 0 || size > MAX_VALUE_BYTES) {
            throw new IOException("Invalid compact model commit byte value size " + size);
        }
        return input.readBytes(size);
    }

    private static void writeString(BinaryWriter output, String value) {
        output.writeString(value);
    }

    private static String readString(BinaryReader input) throws IOException {
        return input.readString();
    }

    private static void writeNullableLong(BinaryWriter output, Long value) {
        output.writeBoolean(value != null);
        if (value != null) {
            output.writeLong(value);
        }
    }

    private static Long readNullableLong(BinaryReader input) throws IOException {
        return input.readBoolean() ? input.readLong() : null;
    }

    private static void writeNullableInt(BinaryWriter output, Integer value) {
        output.writeBoolean(value != null);
        if (value != null) {
            output.writeInt(value);
        }
    }

    private static Integer readNullableInt(BinaryReader input) throws IOException {
        return input.readBoolean() ? input.readInt() : null;
    }

    private static int readSize(BinaryReader input, int maximum, String description) throws IOException {
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

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24
                | (bytes[offset + 1] & 0xff) << 16
                | (bytes[offset + 2] & 0xff) << 8
                | bytes[offset + 3] & 0xff;
    }

    private static int commitSize(
            CommitModels commit,
            RequestBatchDescriptor descriptor,
            SerializedMessage nativeMessage,
            byte[] metadataBytes) {
        int size = Long.BYTES;
        size = addSize(size, stringSize(commit.getCommitId()));
        size = addSize(size, Long.BYTES);
        if (!descriptor.readTargetOnly()) {
            size = addSize(size, stringsSize(commit.getReadModelIds()));
        }
        size = addSize(size, 3); // conflict policy, guarantee and possible-duplicate marker
        ModelCommitStep step = commit.getSubsteps().getFirst();
        size = addSize(size, nativeMessage == null
                ? messageSize(step.getEvent(), commit.getCommitId(), descriptor, metadataBytes)
                : addSize(Integer.BYTES, nativeMessage.envelopeSize()));
        ModelCommitTarget target = step.getTargets().getFirst();
        size = addSize(size, stringSize(target.getModelId()));
        if (!descriptor.sharedModelType()) {
            size = addSize(size, stringSize(target.getModelType()));
        }
        size = addSize(size, nullableLongSize(target.getExpectedSequenceNumber()));
        return addSize(size, 3); // store event, update state and delete
    }

    private static int messageSize(
            SerializedMessage message,
            String commitId,
            RequestBatchDescriptor descriptor,
            byte[] metadataBytes) {
        Data<byte[]> data = message.getData();
        byte[] payload = data.getValue();
        int size = Integer.BYTES + (payload == null ? 0 : payload.length);
        if (!descriptor.sharedDataType()) {
            size = addSize(size, stringSize(data.getType()));
        }
        size = addSize(size, Integer.BYTES);
        if (!descriptor.sharedDataFormat()) {
            size = addSize(size, stringSize(data.getFormat()));
        }
        size = addSize(size, metadataBytes.length);
        size = addSize(size, nullableIntSize(message.getSegment()));
        size = addSize(size, nullableLongSize(message.getIndex()));
        if (!descriptor.sharedSource()) {
            size = addSize(size, stringSize(message.getSource()));
        }
        if (!descriptor.sharedTarget()) {
            size = addSize(size, stringSize(message.getTarget()));
        }
        size = addSize(size, nullableIntSize(message.getRequestId()));
        size = addSize(size, nullableLongSize(message.getTimestamp()));
        if (!descriptor.eventIdIsCommitId()) {
            size = addSize(size, stringSize(message.getMessageId()));
        }
        return size;
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

    private static int nullableIntSize(Integer value) {
        return 1 + (value == null ? 0 : Integer.BYTES);
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
            SerializedMessage[] nativeMessages,
            byte[][] metadataBytes,
            int encodedSize) {

        private static RequestEncodingPlan of(RequestBatch<?> batch, boolean nativeEncoding) {
            RequestBatchDescriptor descriptor = nativeEncoding
                    ? RequestBatchDescriptor.nativeOf(batch) : RequestBatchDescriptor.of(batch);
            SerializedMessage[] nativeMessages = nativeEncoding
                    ? new SerializedMessage[batch.getRequests().size()] : null;
            byte[][] metadataBytes = nativeEncoding
                    ? null : new byte[batch.getRequests().size()][];
            int size = Integer.BYTES + 1 + Integer.BYTES + 1;
            size = addSize(size, descriptor.sharedValuesSize());
            for (int index = 0; index < batch.getRequests().size(); index++) {
                CommitModels commit = (CommitModels) batch.getRequests().get(index);
                SerializedMessage nativeMessage = nativeEncoding
                        ? SerializedMessage.encode(commit.getSubsteps().getFirst().getEvent()) : null;
                byte[] messageMetadata = nativeEncoding ? null
                        : (commit.getSubsteps().getFirst().getEvent().getMetadata() == null
                                ? Metadata.empty()
                                : commit.getSubsteps().getFirst().getEvent().getMetadata())
                        .toData().getValue();
                if (nativeEncoding) {
                    nativeMessages[index] = nativeMessage;
                } else {
                    metadataBytes[index] = messageMetadata;
                }
                size = addSize(size, commitSize(
                        commit, descriptor, nativeMessage, messageMetadata));
            }
            return new RequestEncodingPlan(descriptor, nativeMessages, metadataBytes, size);
        }
    }

    private record RequestBatchDescriptor(
            int flags,
            String dataType,
            String dataFormat,
            String modelType,
            String source,
            String target) {

        private static RequestBatchDescriptor of(RequestBatch<?> batch) {
            CommitModels first = (CommitModels) batch.getRequests().getFirst();
            SerializedMessage firstMessage = first.getSubsteps().getFirst().getEvent();
            ModelCommitTarget firstTarget = first.getSubsteps().getFirst().getTargets().getFirst();
            String dataType = firstMessage.getData().getType();
            String dataFormat = firstMessage.getData().getFormat();
            String modelType = firstTarget.getModelType();
            String source = firstMessage.getSource();
            String target = firstMessage.getTarget();
            int flags = SHARED_DATA_TYPE
                    | SHARED_DATA_FORMAT
                    | SHARED_MODEL_TYPE
                    | SHARED_SOURCE
                    | SHARED_TARGET
                    | READ_TARGET_ONLY
                    | EVENT_ID_IS_COMMIT_ID;
            for (JsonType value : batch.getRequests()) {
                CommitModels commit = (CommitModels) value;
                SerializedMessage message = commit.getSubsteps().getFirst().getEvent();
                ModelCommitTarget commitTarget =
                        commit.getSubsteps().getFirst().getTargets().getFirst();
                if (!Objects.equals(dataType, message.getData().getType())) {
                    flags &= ~SHARED_DATA_TYPE;
                }
                if (!Objects.equals(dataFormat, message.getData().getFormat())) {
                    flags &= ~SHARED_DATA_FORMAT;
                }
                if (!Objects.equals(modelType, commitTarget.getModelType())) {
                    flags &= ~SHARED_MODEL_TYPE;
                }
                if (!Objects.equals(source, message.getSource())) {
                    flags &= ~SHARED_SOURCE;
                }
                if (!Objects.equals(target, message.getTarget())) {
                    flags &= ~SHARED_TARGET;
                }
                if (commit.getReadModelIds().size() != 1
                        || !Objects.equals(
                                commit.getReadModelIds().getFirst(), commitTarget.getModelId())) {
                    flags &= ~READ_TARGET_ONLY;
                }
                if (!Objects.equals(
                        commit.getCommitId(), message.getMessageId())) {
                    flags &= ~EVENT_ID_IS_COMMIT_ID;
                }
            }
            return new RequestBatchDescriptor(
                    flags, dataType, dataFormat, modelType, source, target);
        }

        private static RequestBatchDescriptor nativeOf(RequestBatch<?> batch) {
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
            return new RequestBatchDescriptor(flags, null, null, modelType, null, null);
        }

        private static RequestBatchDescriptor read(
                BinaryReader input, int version) throws IOException {
            int flags = input.readUnsignedByte();
            int supportedFlags = SHARED_DATA_TYPE
                    | SHARED_DATA_FORMAT
                    | SHARED_MODEL_TYPE
                    | SHARED_SOURCE
                    | SHARED_TARGET
                    | READ_TARGET_ONLY;
            if (version >= VERSION) {
                supportedFlags |= EVENT_ID_IS_COMMIT_ID;
            }
            if ((flags & ~supportedFlags) != 0) {
                throw new IOException("Invalid compact model commit request descriptor " + flags);
            }
            return new RequestBatchDescriptor(
                    flags,
                    has(flags, SHARED_DATA_TYPE) ? readString(input) : null,
                    has(flags, SHARED_DATA_FORMAT) ? readString(input) : null,
                    has(flags, SHARED_MODEL_TYPE) ? readString(input) : null,
                    has(flags, SHARED_SOURCE) ? readString(input) : null,
                    has(flags, SHARED_TARGET) ? readString(input) : null);
        }

        private void writeSharedValues(BinaryWriter output) {
            if (sharedDataType()) {
                writeString(output, dataType);
            }
            if (sharedDataFormat()) {
                writeString(output, dataFormat);
            }
            if (sharedModelType()) {
                writeString(output, modelType);
            }
            if (sharedSource()) {
                writeString(output, source);
            }
            if (sharedTarget()) {
                writeString(output, target);
            }
        }

        private int sharedValuesSize() {
            int size = 0;
            if (sharedDataType()) {
                size = addSize(size, stringSize(dataType));
            }
            if (sharedDataFormat()) {
                size = addSize(size, stringSize(dataFormat));
            }
            if (sharedModelType()) {
                size = addSize(size, stringSize(modelType));
            }
            if (sharedSource()) {
                size = addSize(size, stringSize(source));
            }
            if (sharedTarget()) {
                size = addSize(size, stringSize(target));
            }
            return size;
        }

        private boolean sharedDataType() {
            return has(flags, SHARED_DATA_TYPE);
        }

        private boolean sharedDataFormat() {
            return has(flags, SHARED_DATA_FORMAT);
        }

        private boolean sharedModelType() {
            return has(flags, SHARED_MODEL_TYPE);
        }

        private boolean sharedSource() {
            return has(flags, SHARED_SOURCE);
        }

        private boolean sharedTarget() {
            return has(flags, SHARED_TARGET);
        }

        private boolean readTargetOnly() {
            return has(flags, READ_TARGET_ONLY);
        }

        private boolean eventIdIsCommitId() {
            return has(flags, EVENT_ID_IS_COMMIT_ID);
        }


        private static boolean has(int flags, int flag) {
            return (flags & flag) != 0;
        }
    }

    private static final class BinaryWriter {
        private byte[] bytes;
        private int position;

        private BinaryWriter(int initialCapacity) {
            bytes = new byte[initialCapacity];
        }

        private void writeBoolean(boolean value) {
            writeByte(value ? 1 : 0);
        }

        private void writeByte(int value) {
            ensureCapacity(1);
            bytes[position++] = (byte) value;
        }

        private void writeInt(int value) {
            ensureCapacity(Integer.BYTES);
            bytes[position++] = (byte) (value >>> 24);
            bytes[position++] = (byte) (value >>> 16);
            bytes[position++] = (byte) (value >>> 8);
            bytes[position++] = (byte) value;
        }

        private void writeLong(long value) {
            ensureCapacity(Long.BYTES);
            bytes[position++] = (byte) (value >>> 56);
            bytes[position++] = (byte) (value >>> 48);
            bytes[position++] = (byte) (value >>> 40);
            bytes[position++] = (byte) (value >>> 32);
            bytes[position++] = (byte) (value >>> 24);
            bytes[position++] = (byte) (value >>> 16);
            bytes[position++] = (byte) (value >>> 8);
            bytes[position++] = (byte) value;
        }

        private void write(byte[] value) {
            ensureCapacity(value.length);
            System.arraycopy(value, 0, bytes, position, value.length);
            position += value.length;
        }

        private void writeString(String value) {
            if (value == null) {
                writeInt(-1);
                return;
            }
            int length = value.length();
            int start = position;
            writeInt(length);
            ensureCapacity(length);
            for (int index = 0; index < length; index++) {
                char current = value.charAt(index);
                if (current > 0x7f) {
                    position = start;
                    writeBytes(this, value.getBytes(StandardCharsets.UTF_8));
                    return;
                }
                bytes[position++] = (byte) current;
            }
        }

        private byte[] toByteArray() {
            if (position != bytes.length) {
                throw new IllegalStateException(
                        "Compact model commit size mismatch: expected " + bytes.length + ", wrote " + position);
            }
            return bytes;
        }

        private void ensureCapacity(int additionalBytes) {
            int required = position + additionalBytes;
            if (required < 0) {
                throw new IllegalArgumentException("Compact model commit batch exceeds maximum byte array size");
            }
            if (required > bytes.length) {
                throw new IllegalStateException(
                        "Compact model commit size estimate was too small: " + bytes.length + " < " + required);
            }
        }
    }

    private static final class BinaryReader {
        private final byte[] bytes;
        private int position;

        private BinaryReader(byte[] bytes) {
            this.bytes = bytes;
        }

        private boolean readBoolean() throws EOFException {
            return readUnsignedByte() != 0;
        }

        private byte readByte() throws EOFException {
            require(1);
            return bytes[position++];
        }

        private int readUnsignedByte() throws EOFException {
            require(1);
            return bytes[position++] & 0xff;
        }

        private int readInt() throws EOFException {
            require(Integer.BYTES);
            int result = ModelCommitWireCodec.readInt(bytes, position);
            position += Integer.BYTES;
            return result;
        }

        private long readLong() throws EOFException {
            require(Long.BYTES);
            long result =
                    (long) (bytes[position] & 0xff) << 56
                            | (long) (bytes[position + 1] & 0xff) << 48
                            | (long) (bytes[position + 2] & 0xff) << 40
                            | (long) (bytes[position + 3] & 0xff) << 32
                            | (long) (bytes[position + 4] & 0xff) << 24
                            | (long) (bytes[position + 5] & 0xff) << 16
                            | (long) (bytes[position + 6] & 0xff) << 8
                            | bytes[position + 7] & 0xffL;
            position += Long.BYTES;
            return result;
        }

        private byte[] readBytes(int size) throws EOFException {
            require(size);
            byte[] result = Arrays.copyOfRange(bytes, position, position + size);
            position += size;
            return result;
        }

        private SerializedMessage readNativeMessage() throws IOException {
            int size = readInt();
            if (size < SerializedMessage.HEADER_SIZE || size > MAX_VALUE_BYTES) {
                throw new IOException("Invalid native model commit message size " + size);
            }
            require(size);
            SerializedMessage result = SerializedMessage.decodeView(bytes, position, size);
            position += size;
            return result;
        }

        private String readString() throws IOException {
            int size = readInt();
            if (size == -1) {
                return null;
            }
            if (size < 0 || size > MAX_VALUE_BYTES) {
                throw new IOException("Invalid compact model commit string size " + size);
            }
            require(size);
            String result = new String(bytes, position, size, StandardCharsets.UTF_8);
            position += size;
            return result;
        }

        private Metadata readMetadata() throws IOException {
            int start = position;
            int size = readInt();
            if (size < 0 || size > MAX_COLLECTION_SIZE) {
                throw new IOException("Invalid compact model commit metadata size " + size);
            }
            for (int i = 0; i < size; i++) {
                skipMetadataString();
                skipMetadataString();
            }
            return Metadata.fromData(new Data<>(Arrays.copyOfRange(bytes, start, position),
                                                Metadata.DATA_TYPE, 0, Metadata.DATA_FORMAT));
        }

        private void skipMetadataString() throws IOException {
            int size = readInt();
            if (size < 0 || size > MAX_VALUE_BYTES) {
                throw new IOException("Invalid compact model commit metadata string size " + size);
            }
            require(size);
            position += size;
        }

        private int available() {
            return bytes.length - position;
        }

        private void require(int size) throws EOFException {
            if (size < 0 || position > bytes.length - size) {
                throw new EOFException();
            }
        }
    }

}
