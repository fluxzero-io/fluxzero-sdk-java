/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.common.api;

import io.fluxzero.common.api.internal.BinaryWire;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.With;

import java.beans.Transient;

/**
 * A serialized message value. Transport codecs own its binary representation; this class owns only message data.
 */
@lombok.Data
@AllArgsConstructor
public class SerializedMessage implements SerializedObject<byte[]>, HasMetadata {

    @NonNull
    private volatile Data<byte[]> data;

    @With
    private volatile Metadata metadata;

    @With
    private volatile Integer segment;

    private volatile Long index;
    private volatile String source;
    private volatile String target;
    private volatile Integer requestId;
    private volatile Long timestamp;
    private volatile String messageId;
    private transient volatile Integer originalRevision;

    public SerializedMessage(
            Data<byte[]> data, Metadata metadata,
            String messageId, Long timestamp) {
        this(data, metadata, null, null, null, null, null,
             timestamp, messageId, null);
    }

    /** Returns the payload revision before any upcasting. */
    public int getOriginalRevision() {
        return originalRevision == null ? data.getRevision() : originalRevision;
    }

    @Override
    public Data<byte[]> data() {
        return data;
    }

    @Override
    public SerializedMessage withData(@NonNull Data<byte[]> data) {
        return this.data == data ? this : new SerializedMessage(
                data, metadata, segment, index, source, target, requestId,
                timestamp, messageId, getOriginalRevision());
    }

    @Override
    @Transient
    public int getRevision() {
        return data.getRevision();
    }

    @Override
    @Transient
    public String getType() {
        return data.getType();
    }

    /** Returns the exact size used by the compact binary transports. */
    @Transient
    public long getBytes() {
        return BinaryWire.envelopeSize(this);
    }

    /** Checks a metadata key without forcing callers to handle absent metadata. */
    public boolean metadataContainsKey(String key) {
        Metadata value = getMetadata();
        return value != null && value.containsKey(key);
    }

    /** Returns one metadata value, or {@code null} when metadata or the key is absent. */
    public String getMetadataValue(String key) {
        Metadata value = getMetadata();
        return value == null ? null : value.get(key);
    }

    /** Returns a decimal metadata value, or the fallback when absent or malformed. */
    public long getMetadataLongValue(String key, long defaultValue) {
        String value = getMetadataValue(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    protected static boolean encodedMetadataContainsKey(
            byte[] bytes, int offset, int length, String key) {
        return Metadata.containsKey(bytes, offset, length, key);
    }

    protected static String encodedMetadataValue(
            byte[] bytes, int offset, int length, String key) {
        return Metadata.get(bytes, offset, length, key);
    }

    protected static long encodedMetadataLongValue(
            byte[] bytes, int offset, int length, String key, long defaultValue) {
        return Metadata.getLong(bytes, offset, length, key, defaultValue);
    }

    /** Compares the target without an intermediate optional value. */
    public boolean targetEquals(String candidate) {
        return java.util.Objects.equals(target, candidate);
    }

    /** Compares the serialized payload type. */
    public boolean typeEquals(String candidate) {
        return java.util.Objects.equals(data.getType(), candidate);
    }
}
