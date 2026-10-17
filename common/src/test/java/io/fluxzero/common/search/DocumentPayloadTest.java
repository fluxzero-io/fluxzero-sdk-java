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

import io.fluxzero.common.api.Data;
import io.fluxzero.common.serialization.MessagePackIO;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocumentPayloadTest {
    private final JacksonInverter inverter = new JacksonInverter();

    @ParameterizedTest
    @EnumSource(value = CompressionAlgorithm.class, names = {"LZ4", "ZSTD"})
    void payloadConversionKeepsRootsDuplicatesAndMetadataBoundaries(CompressionAlgorithm codec) {
        List<List<EncodedEntry>> cases = List.of(
                List.of(),
                List.of(entry("root")),
                List.of(entry("root", "")),
                List.of(entry("metadata", "$metadata/key")),
                List.of(entry("root", "$metadata")),
                List.of(entry("shared", "value", "$metadata/key")),
                List.of(entry("first", "value"), entry("second", "$metadata/key"),
                        entry("first", "$metadata/key")),
                List.of(entry("same", "$metadata/key"), entry("same", "value")),
                List.of(entry("same"), entry("same", "$metadata/key")),
                List.of(entry("same", "$metadata/key"), entry("same")),
                List.of(entry("literal", "$metadata-lookalike", "$metadataé", "$metadata\\/literal",
                              "$metadata/child", "nested/$metadata/key", "value/é漢🙂")));
        for (var entries : cases) {
            Data<byte[]> data = data(codec, encoded(entries));
            byte[] originalBytes = data.getValue().clone();
            assertEquals(inverter.convert(predecoded(data)), inverter.convert(data), entries.toString());
            assertArrayEquals(originalBytes, data.getValue());
        }
        assertTrue(inverter.convert(data(codec, encoded(cases.get(3)))).getValue().isNull());
        assertEquals("shared", inverter.convert(data(codec, encoded(cases.get(5)))).getValue().path("value").asText());
    }

    @ParameterizedTest
    @EnumSource(value = CompressionAlgorithm.class, names = {"LZ4", "ZSTD"})
    void payloadConversionStillValidatesDiscardedMetadataAndWholeDocument(CompressionAlgorithm codec) {
        byte[] raw = encoded(List.of(entry("root"), entry("ignored", "$metadata/key")));
        for (int length = 0; length < raw.length; length++) {
            Data<byte[]> truncated = data(codec, Arrays.copyOf(raw, length));
            assertEquals("Could not deserialize document", assertThrows(IllegalArgumentException.class,
                    () -> inverter.convert(truncated)).getMessage());
        }
        Data<byte[]> malformedNumber = data(codec, encoded(List.of(
                new EncodedEntry(Document.EntryType.NUMERIC, "not a number", List.of("$metadata/key")))));
        assertThrows(NumberFormatException.class, () -> inverter.convert(predecoded(malformedNumber)));
        assertThrows(NumberFormatException.class, () -> inverter.convert(malformedNumber));
        byte[] trailing = Arrays.copyOf(raw, raw.length + 1);
        trailing[raw.length] = (byte) 0xc1;
        assertEquals(inverter.convert(data(codec, raw)), inverter.convert(data(codec, trailing)));
    }

    @Test
    void metadataPathBytesKeepBinaryStringAcceptanceAndMalformedUtf8Replacement() {
        for (byte[] path : List.of("$metadata/key".getBytes(StandardCharsets.UTF_8),
                                  new byte[]{'$','m','e','t','a','d','a','t','a',(byte) 0xff},
                                  new byte[]{'$','m','e','t','a','d','a','t','a','/',(byte) 0xff},
                                  "é漢🙂".getBytes(StandardCharsets.UTF_8))) {
            try (var writer = header(1)) {
                writer.packByte(Document.EntryType.TEXT.serialize()).packString("value").packArrayHeader(1)
                        .packBinaryHeader(path.length).writePayload(path);
                var data = data(CompressionAlgorithm.ZSTD, writer.toByteArray());
                assertEquals(inverter.convert(predecoded(data)), inverter.convert(data));
            }
        }
    }

    private static EncodedEntry entry(String value, String... paths) {
        return new EncodedEntry(Document.EntryType.TEXT, value, List.of(paths));
    }

    private record EncodedEntry(Document.EntryType type, String value, List<String> paths) { }

    private static MessagePackIO.Writer header(int entries) {
        return new MessagePackIO.Writer().packInt(0).packString("id").packNil().packNil()
                .packString("docs").packArrayHeader(entries);
    }

    private static byte[] encoded(List<EncodedEntry> entries) {
        try (var writer = header(entries.size())) {
            for (var entry : entries) {
                writer.packByte(entry.type().serialize()).packString(entry.value()).packArrayHeader(entry.paths().size());
                entry.paths().forEach(writer::packString);
            }
            return writer.toByteArray();
        }
    }

    private static Data<byte[]> data(CompressionAlgorithm codec, byte[] raw) {
        return new Data<>(codec.compress(raw), "type", 2, Data.DOCUMENT_FORMAT);
    }

    private static Data<byte[]> predecoded(Data<byte[]> source) {
        byte[] bytes = source.getValue();
        Map<Document.Entry, List<Document.Path>> entries = DefaultDocumentSerializer.INSTANCE.deserialize(source);
        return new Data<>(new JacksonInverter.DocumentBytes() {
            @Override public Map<Document.Entry, List<Document.Path>> entries() { return entries; }
            @Override public byte[] get() { return bytes; }
            @Override public byte[] array() { return bytes; }
            @Override public int offset() { return 0; }
            @Override public int length() { return bytes.length; }
        }, source.getType(), source.getRevision(), source.getFormat());
    }
}
