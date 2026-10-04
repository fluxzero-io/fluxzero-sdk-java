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

package io.fluxzero.common.search;

import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.Data;
import org.msgpack.core.MessagePack;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentMetadataTest {
    @Test
    void metadataKeysAreUnescapedExactlyOnceAcrossBinaryRoundTrips() {
        var expected = Metadata.of("\"quoted\"", "quoted", "slash\\key", "slash",
                                   "dot.and/slash", Map.of("nested", List.of(1, 2)), "", "empty key");
        var source = new JacksonInverter().toDocument(Map.of("value", "text"), "type", 0, "id", "docs",
                                                     null, null, expected);
        assertEquals(expected, source.getMetadata());
        assertEquals(expected, JacksonInverter.extractMetadata(
                DefaultDocumentSerializer.INSTANCE.deserialize(source.getDocument())));
        assertEquals(expected, DefaultDocumentSerializer.INSTANCE.deserializeMetadata(source.getDocument()));
    }

    @ParameterizedTest
    @EnumSource(value = CompressionAlgorithm.class, names = {"LZ4", "ZSTD"})
    void metadataScanPreservesPayloadPathsAndDuplicateEntryOrder(CompressionAlgorithm codec) throws Exception {
        List<List<EncodedEntry>> cases = List.of(
                List.of(new EncodedEntry("ordinary", List.of("value", "$metadata-lookalike"))),
                List.of(new EncodedEntry("root", List.of("$metadata"))),
                List.of(new EncodedEntry("shared", List.of("value", "$metadata/key"))),
                List.of(new EncodedEntry("same", List.of("$metadata/key")),
                        new EncodedEntry("same", List.of("value"))),
                List.of(new EncodedEntry("A", List.of("value")),
                        new EncodedEntry("B", List.of("$metadata/key")),
                        new EncodedEntry("A", List.of("$metadata/key"))));
        for (List<EncodedEntry> entries : cases) {
            var data = data(codec, encoded(entries));
            assertEquals(JacksonInverter.extractMetadata(DefaultDocumentSerializer.INSTANCE.deserialize(data)),
                         DefaultDocumentSerializer.INSTANCE.deserializeMetadata(data));
        }
        assertEquals(Metadata.of("key", "B"), DefaultDocumentSerializer.INSTANCE.deserializeMetadata(
                data(codec, encoded(cases.getLast()))));
    }

    @ParameterizedTest
    @EnumSource(value = CompressionAlgorithm.class, names = {"LZ4", "ZSTD"})
    void metadataScanStillRejectsTruncatedOrdinaryAndMetadataDocuments(CompressionAlgorithm codec) throws Exception {
        for (String path : List.of("value", "$metadata/key")) {
            byte[] bytes = encoded(List.of(new EncodedEntry("value", List.of(path))));
            for (int length = 0; length < bytes.length; length++) {
                var truncated = data(codec, Arrays.copyOf(bytes, length));
                assertEquals("Could not deserialize document", assertThrows(IllegalArgumentException.class,
                        () -> DefaultDocumentSerializer.INSTANCE.deserializeMetadata(truncated)).getMessage());
            }
        }
    }

    @Test
    void metadataScanKeepsInvalidEntryAndUnsupportedFormatFailures() throws Exception {
        byte[] raw = encoded(List.of(new EncodedEntry("value", List.of("value"))));
        // Header length is independent of the number of entries for these fixarray encodings.
        raw[encoded(List.of()).length] = 127;
        var invalid = data(CompressionAlgorithm.LZ4, raw);
        assertEquals("Could not deserialize document", assertThrows(IllegalArgumentException.class,
                () -> DefaultDocumentSerializer.INSTANCE.deserializeMetadata(invalid)).getMessage());
        var json = new Data<>(new byte[0], "type", 0, Data.JSON_FORMAT);
        assertEquals("Unsupported data format: application/json", assertThrows(IllegalArgumentException.class,
                () -> DefaultDocumentSerializer.INSTANCE.deserializeMetadata(json)).getMessage());
    }

    @Test
    void metadataScanPreservesSkippedStringKindsAndBounds() throws Exception {
        for (byte[] value : new byte[][]{{(byte) 0xa0}, {(byte) 0xc4, 2, (byte) 0xc0, (byte) 0xaf}}) {
            var data = data(CompressionAlgorithm.LZ4, encodedValue(value));
            assertEquals(JacksonInverter.extractMetadata(DefaultDocumentSerializer.INSTANCE.deserialize(data)),
                         DefaultDocumentSerializer.INSTANCE.deserializeMetadata(data));
        }
        for (byte[] value : new byte[][]{{1}, {(byte) 0xc0}, {(byte) 0x90}, {(byte) 0xc3},
                {(byte) 0xdb, -1, -1, -1, -1}, {(byte) 0xd9, 64}}) {
            var data = data(CompressionAlgorithm.LZ4, encodedValue(value));
            assertThrows(IllegalArgumentException.class, () -> DefaultDocumentSerializer.INSTANCE.deserialize(data));
            assertThrows(IllegalArgumentException.class,
                         () -> DefaultDocumentSerializer.INSTANCE.deserializeMetadata(data));
        }
    }

    private static byte[] encodedValue(byte[] value) throws Exception {
        try (var writer = MessagePack.newDefaultBufferPacker()) {
            writer.packInt(0).packString("id").packNil().packNil().packString("docs").packArrayHeader(1);
            writer.packByte(Document.EntryType.TEXT.serialize()).writePayload(value);
            writer.packArrayHeader(1).packString("value");
            return writer.toByteArray();
        }
    }

    private record EncodedEntry(String value, List<String> paths) { }

    private static byte[] encoded(List<EncodedEntry> entries) throws Exception {
        try (var writer = MessagePack.newDefaultBufferPacker()) {
            writer.packInt(0).packString("id").packNil().packNil().packString("docs").packArrayHeader(entries.size());
            for (var entry : entries) {
                writer.packByte(Document.EntryType.TEXT.serialize()).packString(entry.value())
                        .packArrayHeader(entry.paths().size());
                for (String path : entry.paths()) { writer.packString(path); }
            }
            return writer.toByteArray();
        }
    }

    private static Data<byte[]> data(CompressionAlgorithm codec, byte[] raw) {
        return new Data<>(codec.compress(raw), "type", 0, Data.DOCUMENT_FORMAT);
    }
}
