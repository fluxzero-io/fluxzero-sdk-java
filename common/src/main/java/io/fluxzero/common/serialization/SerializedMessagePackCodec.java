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

package io.fluxzero.common.serialization;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.internal.BinaryWire;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes the shared binary or legacy MessagePack representations used by the Fluxzero event store.
 * <p>
 * This codec deliberately owns only the read contract needed by compact model-event responses. Event-store writes
 * remain owned by the runtime.
 */
public final class SerializedMessagePackCodec {

    private static final byte[] EMPTY = new byte[0];
    private static final int MAXIMUM_VALUE_SIZE = 512 * 1024 * 1024;
    private static final ThreadLocal<ReusableUnpacker> UNPACKERS =
            ThreadLocal.withInitial(ReusableUnpacker::new);

    private SerializedMessagePackCodec() {
    }

    /**
     * Decodes zero or more consecutive serialized messages.
     */
    public static List<SerializedMessage> decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        return decode(bytes, 0, bytes.length);
    }

    /** Decodes consecutive serialized messages from a zero-copy byte range. */
    public static List<SerializedMessage> decode(
            byte[] bytes, int offset, int length) {
        if (BinaryWire.isEnvelopeSequence(bytes, offset, length)) {
            try {
                return BinaryWire.decodeEnvelopes(bytes, offset, length, MAXIMUM_VALUE_SIZE);
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not decode binary event payloads", e);
            }
        }
        ReusableUnpacker reusable = UNPACKERS.get();
        try {
            MessagePackIO.Reader unpacker =
                    reusable.reset(
                            bytes, offset, length);
            List<SerializedMessage> result = new ArrayList<>();
            while (unpacker.hasNext()) {
                result.add(decodeMessage(unpacker));
            }
            return result;
        } catch (Exception e) {
            UNPACKERS.remove();
            throw new IllegalArgumentException("Could not decode compact event payloads", e);
        } finally {
            reusable.clear();
        }
    }

    private static SerializedMessage decodeMessage(MessagePackIO.Reader unpacker) throws IOException {
        int version = unpacker.unpackInt();
        if (version >= 0) {
            return decodeVersionZero(unpacker, version);
        }
        if (version != -1 && version != -2) {
            throw new UnsupportedOperationException("Unrecognized serialized-message version: " + -version);
        }
        return new SerializedMessage(
                new Data<>(
                        unpacker.readPayload(unpacker.unpackInt()),
                        unpackString(unpacker),
                        unpacker.unpackInt(),
                        unpackString(unpacker)),
                version == -2 ? unpackSerializedMetadata(unpacker) : unpackMetadata(unpacker),
                unpackInt(unpacker),
                unpackLong(unpacker),
                unpackString(unpacker),
                unpackString(unpacker),
                unpackInt(unpacker),
                unpackLong(unpacker),
                unpackString(unpacker),
                null);
    }

    private static Metadata unpackSerializedMetadata(MessagePackIO.Reader unpacker) throws IOException {
        byte[] value = unpacker.readPayload(unpacker.unpackInt());
        return Metadata.fromData(new Data<>(value, Metadata.DATA_TYPE, 0, Metadata.DATA_FORMAT));
    }

    private static SerializedMessage decodeVersionZero(
            MessagePackIO.Reader unpacker, int payloadSize) throws IOException {
        return new SerializedMessage(
                new Data<>(
                        unpacker.readPayload(payloadSize),
                        unpackString(unpacker),
                        unpacker.unpackInt(),
                        null),
                unpackMetadata(unpacker),
                unpackInt(unpacker),
                null,
                unpackString(unpacker),
                unpackString(unpacker),
                unpackInt(unpacker),
                unpackLong(unpacker),
                unpackString(unpacker),
                null);
    }

    private static Metadata unpackMetadata(MessagePackIO.Reader unpacker) throws IOException {
        int size = unpacker.unpackInt();
        if (size == 0) {
            return Metadata.empty();
        }
        Map<String, String> values = new HashMap<>(Math.max(16, (int) (size / 0.75f) + 1));
        for (int i = 0; i < size; i++) {
            values.put(unpackString(unpacker), unpackString(unpacker));
        }
        return Metadata.ofStrings(values);
    }

    private static Integer unpackInt(MessagePackIO.Reader unpacker) throws IOException {
        if (unpacker.tryUnpackNil()) {
            return null;
        }
        return unpacker.unpackInt();
    }

    private static Long unpackLong(MessagePackIO.Reader unpacker) throws IOException {
        if (unpacker.tryUnpackNil()) {
            return null;
        }
        return unpacker.unpackLong();
    }

    private static String unpackString(MessagePackIO.Reader unpacker) throws IOException {
        if (unpacker.tryUnpackNil()) {
            return null;
        }
        return unpacker.unpackString();
    }

    private static final class ReusableUnpacker {
        private final MessagePackIO.Reader unpacker = new MessagePackIO.Reader(EMPTY);

        private MessagePackIO.Reader reset(byte[] bytes, int offset, int length) {
            return unpacker.reset(bytes, offset, length);
        }

        private void clear() {
            unpacker.close();
        }
    }
}
