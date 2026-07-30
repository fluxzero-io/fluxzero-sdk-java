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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final int VERSION = 2;
    private static final int SHARED_DATA_TYPE = 1;
    private static final int SHARED_DATA_FORMAT = 1 << 1;
    private static final int SHARED_MODEL_TYPE = 1 << 2;
    private static final int SHARED_SOURCE = 1 << 3;
    private static final int SHARED_TARGET = 1 << 4;
    private static final int READ_TARGET_ONLY = 1 << 5;
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
        int magic = readInt(bytes, 0);
        if (magic != REQUEST_MAGIC && magic != RESULT_MAGIC) {
            return null;
        }
        try {
            BinaryReader input = new BinaryReader(bytes);
            input.readInt();
            int version = input.readUnsignedByte();
            if (version != VERSION) {
                throw new IOException("Unsupported compact model commit wire version " + version);
            }
            JsonType result = magic == REQUEST_MAGIC ? decodeRequests(input) : decodeResults(input);
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
                    || result.getSubsteps().size() != 1
                    || result.getSubsteps().getFirst().getTargets().size() != 1) {
                return false;
            }
        }
        return true;
    }

    private static byte[] encodeRequests(RequestBatch<?> batch) throws IOException {
        RequestBatchDescriptor descriptor = RequestBatchDescriptor.of(batch);
        BinaryWriter output =
                new BinaryWriter(Math.max(256, batch.getRequests().size() * 192));
        output.writeInt(REQUEST_MAGIC);
        output.writeByte(VERSION);
        output.writeInt(batch.getRequests().size());
        output.writeByte(descriptor.flags());
        descriptor.writeSharedValues(output);
        for (JsonType value : batch.getRequests()) {
            writeCommit(output, (CommitModels) value, descriptor);
        }
        return output.toByteArray();
    }

    private static void writeCommit(
            BinaryWriter output, CommitModels commit, RequestBatchDescriptor descriptor) {
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
        writeMessage(output, step.getEvent(), descriptor);

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
            BinaryWriter output, SerializedMessage message, RequestBatchDescriptor descriptor) {
        Data<byte[]> data = message.getData();
        writeBytes(output, data.getValue());
        if (!descriptor.sharedDataType()) {
            writeString(output, data.getType());
        }
        output.writeInt(data.getRevision());
        if (!descriptor.sharedDataFormat()) {
            writeString(output, data.getFormat());
        }

        Map<String, String> metadata =
                message.getMetadata() == null ? Map.of() : message.getMetadata().getEntries();
        output.writeInt(metadata.size());
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            writeString(output, entry.getKey());
            writeString(output, entry.getValue());
        }
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
        writeString(output, message.getMessageId());
    }

    private static RequestBatch<CommitModels> decodeRequests(BinaryReader input) throws IOException {
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
            SerializedMessage event = readMessage(input, descriptor);
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
            BinaryReader input, RequestBatchDescriptor descriptor) throws IOException {
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
        int metadataSize = readSize(input, MAX_COLLECTION_SIZE, "metadata");
        Map<String, String> metadata = new LinkedHashMap<>(capacity(metadataSize));
        for (int i = 0; i < metadataSize; i++) {
            metadata.put(readString(input), readString(input));
        }
        return new SerializedMessage(
                data,
                Metadata.of(metadata),
                readNullableInt(input),
                readNullableLong(input),
                descriptor.sharedSource() ? descriptor.source() : readString(input),
                descriptor.sharedTarget() ? descriptor.target() : readString(input),
                readNullableInt(input),
                readNullableLong(input),
                readString(input),
                null);
    }

    private static byte[] encodeResults(ResultBatch batch) throws IOException {
        BinaryWriter output =
                new BinaryWriter(Math.max(128, batch.getResults().size() * 128));
        output.writeInt(RESULT_MAGIC);
        output.writeByte(VERSION);
        output.writeInt(batch.getResults().size());
        for (var value : batch.getResults()) {
            CommitModelsResult result = (CommitModelsResult) value;
            ModelCommitStepResult step = result.getSubsteps().getFirst();
            ModelCommitTargetResult target = step.getTargets().getFirst();
            output.writeLong(result.getRequestId());
            writeString(output, result.getCommitId());
            output.writeLong(step.getStateIndex());
            writeNullableLong(output, step.getEventIndex());
            writeString(output, target.getModelId());
            output.writeLong(target.getSequenceNumber());
            output.writeBoolean(target.isHistoryComplete());
            output.writeLong(result.getRequestReceivedTimestamp());
            output.writeLong(result.getResponseQueuedTimestamp());
            output.writeLong(result.getResponseSendStartTimestamp());
        }
        return output.toByteArray();
    }

    private static ResultBatch decodeResults(BinaryReader input) throws IOException {
        int size = readSize(input, MAX_BATCH_SIZE, "batch");
        List<CommitModelsResult> results = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long requestId = input.readLong();
            String commitId = readString(input);
            long stateIndex = input.readLong();
            Long eventIndex = readNullableLong(input);
            String modelId = readString(input);
            long sequenceNumber = input.readLong();
            boolean historyComplete = input.readBoolean();
            CommitModelsResult result =
                    CommitModelsResult.accepted(
                            requestId,
                            commitId,
                            List.of(
                                    new ModelCommitStepResult(
                                            stateIndex,
                                            eventIndex,
                                            List.of(
                                                    new ModelCommitTargetResult(
                                                            modelId,
                                                            sequenceNumber,
                                                            historyComplete)))));
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
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
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

    private static int capacity(int size) {
        return size < 3 ? size + 1 : (int) Math.ceil(size / 0.75d);
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24
                | (bytes[offset + 1] & 0xff) << 16
                | (bytes[offset + 2] & 0xff) << 8
                | bytes[offset + 3] & 0xff;
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
                    | READ_TARGET_ONLY;
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
            }
            return new RequestBatchDescriptor(
                    flags, dataType, dataFormat, modelType, source, target);
        }

        private static RequestBatchDescriptor read(BinaryReader input) throws IOException {
            int flags = input.readUnsignedByte();
            if ((flags & ~(SHARED_DATA_TYPE
                    | SHARED_DATA_FORMAT
                    | SHARED_MODEL_TYPE
                    | SHARED_SOURCE
                    | SHARED_TARGET
                    | READ_TARGET_ONLY)) != 0) {
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

        private byte[] toByteArray() {
            return position == bytes.length ? bytes : Arrays.copyOf(bytes, position);
        }

        private void ensureCapacity(int additionalBytes) {
            int required = position + additionalBytes;
            if (required < 0) {
                throw new IllegalArgumentException("Compact model commit batch exceeds maximum byte array size");
            }
            if (required > bytes.length) {
                int grown = Math.max(required, Math.min(Integer.MAX_VALUE - 8, bytes.length << 1));
                if (grown < required) {
                    grown = required;
                }
                bytes = Arrays.copyOf(bytes, grown);
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
