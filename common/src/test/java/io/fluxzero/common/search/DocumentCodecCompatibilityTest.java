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

package io.fluxzero.common.search;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.serialization.compression.CompressionAlgorithm;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentCodecCompatibilityTest {
    @Test
    void completeDocumentsHaveIdenticalStoredBytesAndCrossReadWithFrozenOldCodec() {
        Random random = new Random(3101);
        for (int sample = 0; sample < 1024; sample++) {
            var entries = new LinkedHashMap<Document.Entry, List<Document.Path>>();
            for (int n = 0; n < sample % 33; n++) {
                String text = randomString(random, sample % 16 == 0 ? 2048 : random.nextInt(80));
                var paths = new ArrayList<Document.Path>();
                for (int k = 0; k < n % 4; k++) paths.add(new Document.Path("field/" + k + "/é"));
                Document.EntryType type = Document.EntryType.values()[n % Document.EntryType.values().length];
                String value = switch (type) {
                    case TEXT -> text;
                    case NUMERIC -> Long.toString(random.nextLong());
                    case BOOLEAN -> Boolean.toString(random.nextBoolean());
                    case NULL, EMPTY_ARRAY, EMPTY_OBJECT -> "";
                };
                entries.put(new Document.Entry(type, value), paths);
            }
            var document = Document.builder().id("id-" + sample).collection("collection-漢" + sample % 3)
                    .type("type").revision(random.nextInt())
                    .timestamp(sample % 2 == 0 ? null : Instant.ofEpochMilli(random.nextLong()))
                    .end(sample % 3 == 0 ? null : Instant.ofEpochMilli(random.nextLong()))
                    .entries(entries).build();
            var oldBytes = LegacyDocumentSerializer.INSTANCE.serialize(document);
            var newBytes = DefaultDocumentSerializer.INSTANCE.serialize(document);
            assertArrayEquals(oldBytes.getValue(), newBytes.getValue(), "stored document seed 3101, sample " + sample);
            var expected = LegacyDocumentSerializer.INSTANCE.deserialize(oldBytes);
            assertEquals(expected, DefaultDocumentSerializer.INSTANCE.deserialize(oldBytes));
            assertEquals(expected, LegacyDocumentSerializer.INSTANCE.deserialize(newBytes));
            byte[] raw = CompressionAlgorithm.ZSTD.decompress(oldBytes.getValue());
            // Same logical historical record through all envelopes the document reader accepts.
            byte[] legacy = CompressionAlgorithm.LZ4.compress(raw);
            byte[] framedLz4 = java.nio.ByteBuffer.allocate(legacy.length + 3)
                    .put((byte) 0xff).put((byte) 0).put((byte) 1).put(legacy).array();
            byte[] none = java.nio.ByteBuffer.allocate(raw.length + 7)
                    .put((byte) 0xff).put((byte) 0).put((byte) 0).putInt(raw.length).put(raw).array();
            for (byte[] envelope : List.of(legacy, framedLz4, none, newBytes.getValue())) {
                var input = new Data<>(envelope, "type", 0, Data.DOCUMENT_FORMAT);
                assertEquals(LegacyDocumentSerializer.INSTANCE.deserialize(input),
                             DefaultDocumentSerializer.INSTANCE.deserialize(input),
                             "envelope sample " + sample);
            }
        }
    }

    private static String randomString(Random random, int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) chars[i] = (char) random.nextInt(65536);
        return new String(chars);
    }
}
