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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultDocumentSerializerTest {
    private final DefaultDocumentSerializer subject = DefaultDocumentSerializer.INSTANCE;

    @ParameterizedTest
    @ValueSource(ints = {1023, 1024, 1025, 32768})
    void readsLegacyDocumentsAroundBufferBoundary(int size) throws Exception {
        int overhead = legacyDocument(new byte[1000]).length - 1000;
        byte[] value = "a".repeat(size - overhead).getBytes(StandardCharsets.UTF_8);
        byte[] bytes = legacyDocument(value);
        assertEquals(size, bytes.length);
        assertEquals(expected(value), subject.deserialize(data(bytes)));
    }

    @Test
    void preservesUnicodeAndMalformedStringReplacement() throws Exception {
        for (byte[] value : List.of(new byte[0], "é漢🙂\u0000".getBytes(StandardCharsets.UTF_8),
                                   new byte[]{(byte) 0xc0, (byte) 0xaf},
                                   new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                                   new byte[]{(byte) 0xf0, (byte) 0x9f})) {
            assertEquals(expected(value), subject.deserialize(data(legacyDocument(value))));
        }
    }

    @Test
    void readsLargeUnicodeDocumentFromSmallCompressedValue() throws Exception {
        byte[] value = "é漢🙂".repeat(4096).getBytes(StandardCharsets.UTF_8);
        byte[] bytes = legacyDocument(value);
        Data<byte[]> compressed = data(bytes);
        assertTrue(bytes.length > 8192);
        assertTrue(compressed.getValue().length < 1024);
        assertEquals(expected(value), subject.deserialize(compressed));
    }

    @Test
    void keepsDecodeFailureWrappingAndTrailingDataBehavior() throws Exception {
        byte[] bytes = legacyDocument("value".getBytes(StandardCharsets.UTF_8));
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
        byte[] bytes = legacyDocument(new byte[0]);
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
        return new Data<>(CompressionAlgorithm.LZ4.compress(bytes), "type", 0, Data.DOCUMENT_FORMAT);
    }

    private static byte[] legacyDocument(byte[] value) throws Exception {
        try (var packer = MessagePack.newDefaultBufferPacker()) {
            packer.packInt(0).packString("id").packNil().packLong(1234).packString("collection");
            packer.packArrayHeader(1).packByte(Document.EntryType.TEXT.serialize());
            packer.packRawStringHeader(value.length).addPayload(value);
            packer.packArrayHeader(2).packString("path/é").packString("other");
            return packer.toByteArray();
        }
    }
}
