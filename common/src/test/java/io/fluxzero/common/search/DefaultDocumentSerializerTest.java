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
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.msgpack.core.MessagePack;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultDocumentSerializerTest {
    private final DefaultDocumentSerializer subject = DefaultDocumentSerializer.INSTANCE;

    @Test
    void writesZstdDocumentWithUnchangedMessagePackContent() throws Exception {
        byte[] value = "value".getBytes(StandardCharsets.UTF_8);
        Document document = Document.builder().id("id").collection("collection").type("type").revision(3)
                .end(Instant.ofEpochMilli(1234)).entries(expected(value)).build();

        Data<byte[]> data = subject.serialize(document);

        assertEquals(Data.DOCUMENT_FORMAT, data.getFormat());
        assertEquals("type", data.getType());
        assertEquals(3, data.getRevision());
        assertEquals(2, data.getValue()[2]);
        assertArrayEquals(
                versionZeroDocument(value), CompressionAlgorithm.ZSTD.decompress(data.getValue()));
        assertEquals(document.getEntries(), subject.deserialize(data));
    }

    @Test
    void readsDocumentWrittenByReleasedSdkWithNativeLz4() {
        // Serialized by the unmodified common 2.0.0-rc.20 jar using its native LZ4 writer.
        byte[] bytes = java.util.Base64.getDecoder().decode(
                "AAAALPAdAKpoaXN0b3JpY2FswMCqY29sbGVjdGlvbpEAqcOp5ryi8J+ZgpGldmFsdWU=");
        assertEquals(Map.of(new Document.Entry(Document.EntryType.TEXT, "é漢🙂"),
                            List.of(new Document.Path("value"))),
                     subject.deserialize(new Data<>(bytes, "type", 7, Data.DOCUMENT_FORMAT)));
    }

    @Test
    void readsLegacyAndFramedDocuments() throws Exception {
        byte[] value = "historical value".getBytes(StandardCharsets.UTF_8);
        byte[] raw = versionZeroDocument(value);
        byte[] legacy = CompressionAlgorithm.LZ4.compress(raw);
        byte[] framedLz4 = new byte[legacy.length + 3];
        framedLz4[0] = (byte) 0xff;
        framedLz4[2] = 1;
        System.arraycopy(legacy, 0, framedLz4, 3, legacy.length);
        byte[] none = java.nio.ByteBuffer.allocate(raw.length + 7)
                .put((byte) 0xff).put((byte) 0).put((byte) 0).putInt(raw.length).put(raw).array();
        for (byte[] bytes : List.of(legacy, framedLz4, none, CompressionAlgorithm.ZSTD.compress(raw))) {
            assertEquals(expected(value), subject.deserialize(new Data<>(bytes, "type", 0, Data.DOCUMENT_FORMAT)));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1023, 1024, 1025, 32768})
    void readsVersionZeroDocumentsAroundBufferBoundary(int size) throws Exception {
        int overhead = versionZeroDocument(new byte[1000]).length - 1000;
        byte[] value = "a".repeat(size - overhead).getBytes(StandardCharsets.UTF_8);
        byte[] bytes = versionZeroDocument(value);
        assertEquals(size, bytes.length);
        assertEquals(expected(value), subject.deserialize(data(bytes)));
    }

    @Test
    void preservesUnicodeAndMalformedStringReplacement() throws Exception {
        for (byte[] value : List.of(new byte[0], "é漢🙂\u0000".getBytes(StandardCharsets.UTF_8),
                                   new byte[]{(byte) 0xc0, (byte) 0xaf},
                                   new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                                   new byte[]{(byte) 0xf0, (byte) 0x9f})) {
            assertEquals(expected(value), subject.deserialize(data(versionZeroDocument(value))));
        }
    }

    @Test
    void readsLargeUnicodeDocumentFromSmallCompressedValue() throws Exception {
        byte[] value = "é漢🙂".repeat(4096).getBytes(StandardCharsets.UTF_8);
        byte[] bytes = versionZeroDocument(value);
        Data<byte[]> compressed = data(bytes);
        assertTrue(bytes.length > 8192);
        assertTrue(compressed.getValue().length < 1024);
        assertEquals(expected(value), subject.deserialize(compressed));
    }

    @Test
    void keepsDecodeFailureWrappingAndTrailingDataBehavior() throws Exception {
        byte[] bytes = versionZeroDocument("value".getBytes(StandardCharsets.UTF_8));
        for (int length = 0; length < bytes.length; length++) {
            Data<byte[]> truncated = data(Arrays.copyOf(bytes, length));
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> subject.deserialize(truncated));
            assertEquals("Could not deserialize document", error.getMessage());
            assertNotNull(error.getCause());
        }
        for (byte[] invalid : Arrays.asList(null, new byte[0], new byte[]{-1})) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> subject.deserialize(new Data<>(invalid, "type", 0, Data.DOCUMENT_FORMAT)));
            assertEquals("Could not deserialize document", error.getMessage());
            assertNotNull(error.getCause());
        }
        byte[] trailing = Arrays.copyOf(bytes, bytes.length + 3);
        trailing[bytes.length] = (byte) 0xc1;
        assertEquals(subject.deserialize(data(bytes)), subject.deserialize(data(trailing)));
    }

    @Test
    void preservesUnsupportedFormatAndRevisionErrors() throws Exception {
        assertEquals("Unsupported data format: unsupported", assertThrows(IllegalArgumentException.class,
                () -> subject.deserialize(new Data<>(new byte[0], "type", 0, "unsupported"))).getMessage());
        byte[] bytes = versionZeroDocument(new byte[0]);
        bytes[0] = 1;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> subject.deserialize(data(bytes)));
        assertEquals("Could not deserialize document", error.getMessage());
        assertEquals("Unsupported document revision: 1", error.getCause().getMessage());
    }

    @Test
    void roundTripsIndependentDocumentsConcurrently() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<Void>> tasks = IntStream.range(0, 64).mapToObj(i -> (Callable<Void>) () -> {
                byte[] value = ("é漢🙂" + i).getBytes(StandardCharsets.UTF_8);
                Document document = Document.builder().id("id-" + i).collection("collection").type("type")
                        .entries(expected(value)).build();
                assertEquals(document.getEntries(), subject.deserialize(subject.serialize(document)));
                return null;
            }).toList();
            for (var future : executor.invokeAll(tasks)) {
                future.get();
            }
        }
    }

    private static Map<Document.Entry, List<Document.Path>> expected(byte[] value) {
        return Map.of(new Document.Entry(Document.EntryType.TEXT, new String(value, StandardCharsets.UTF_8)),
                      List.of(new Document.Path("path/é"), new Document.Path("other")));
    }

    private static Data<byte[]> data(byte[] bytes) {
        return new Data<>(CompressionAlgorithm.ZSTD.compress(bytes), "type", 0, Data.DOCUMENT_FORMAT);
    }

    private static byte[] versionZeroDocument(byte[] value) throws Exception {
        try (var packer = MessagePack.newDefaultBufferPacker()) {
            packer.packInt(0).packString("id").packNil().packLong(1234).packString("collection");
            packer.packArrayHeader(1).packByte(Document.EntryType.TEXT.serialize());
            packer.packRawStringHeader(value.length).addPayload(value);
            packer.packArrayHeader(2).packString("path/é").packString("other");
            return packer.toByteArray();
        }
    }
}
