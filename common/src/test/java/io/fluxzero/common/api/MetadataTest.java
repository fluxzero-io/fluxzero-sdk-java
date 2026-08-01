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

package io.fluxzero.common.api;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataTest {

    @Test
    void compactBuilderReplacesValuesAndGrows() {
        Metadata metadata = Metadata.builder(1)
                .put("one", "old")
                .put("two", "second")
                .put("one", "new")
                .putAll(Map.of("three", "third"))
                .put("two", null)
                .put("missing", null)
                .build();

        assertEquals(Map.of(
                "one", "new",
                "three", "third"), metadata.getEntries());
        assertSame(metadata.toData(), metadata.toData());
    }

    @Test
    void preservesCompactMetadataWireFormat() {
        String key = "broken-\ud800-key-😀";
        String value = "München-東京";
        byte[] encodedKey = key.getBytes(StandardCharsets.UTF_8);
        byte[] encodedValue = value.getBytes(StandardCharsets.UTF_8);
        byte[] expected = ByteBuffer.allocate(
                        3 * Integer.BYTES + encodedKey.length + encodedValue.length)
                .putInt(1)
                .putInt(encodedKey.length)
                .put(encodedKey)
                .putInt(encodedValue.length)
                .put(encodedValue)
                .array();

        assertArrayEquals(expected, Metadata.of(key, value).toData().getValue());
    }

    @Test
    void roundTripsCompactSerializedData() {
        Metadata original = Metadata.of("tenant", "München-東京", "attempt", 2);

        Data<byte[]> data = original.toData();
        Metadata restored = Metadata.fromData(data);

        assertSame(data, original.toData());
        assertSame(data, restored.toData());
        assertTrue(restored.containsKey("attempt"));
        assertFalse(restored.containsKey("missing"));
        assertEquals(original, restored);
        assertEquals("München-東京", restored.get("tenant"));
        assertEquals("2", restored.get("attempt"));
    }

    @Test
    void changingSerializedMetadataCreatesIndependentData() {
        Metadata original = Metadata.of("key", "old");
        Data<byte[]> originalData = original.toData();
        Metadata restored = Metadata.fromData(originalData);

        Metadata changed = restored.with("key", "new");

        assertEquals("old", restored.get("key"));
        assertEquals("new", changed.get("key"));
        assertNotSame(originalData, changed.toData());
    }

    @Test
    void changingMaterializedMetadataPreservesExistingStringValues() {
        String method = new String("POST");
        Metadata original = Metadata.of("method", method);

        Metadata changed = original.with("timeout", "1000");

        assertSame(method, changed.get("method"));
    }

    @Test
    void directlyMergesUnicodeStringAndEnumValuesIntoOpaqueData() {
        Metadata base = Metadata.fromData(Metadata.of("tenant", "old", "unchanged", "value").toData());

        Metadata changed = base.with("tenant", "München-東京").with("state", TestState.ACTIVE);

        assertEquals("old", base.get("tenant"));
        assertEquals("München-東京", changed.get("tenant"));
        assertEquals("ACTIVE", changed.get("state"));
        assertEquals("value", changed.get("unchanged"));
        assertEquals(changed, Metadata.fromData(changed.toData()));
    }

    @Test
    void readsIndividualSerializedValuesWithoutChangingTheOpaqueData() {
        Data<byte[]> data = Metadata.of(
                "tenant", "München-東京",
                "attempt", "2").toData();
        Metadata restored = Metadata.fromData(data);

        assertEquals("München-東京", restored.get("tenant"));
        assertEquals("2", restored.get("attempt"));
        assertNull(restored.get("missing"));
        assertSame(data, restored.toData());
    }

    @Test
    void directlyMatchesUtf8KeysInOpaqueData() {
        String malformed = "broken-\ud800-key";
        Data<byte[]> data = Metadata.of(
                "München", "one",
                "東京", "two",
                "emoji-😀", "three",
                malformed, "four").toData();
        Metadata restored = Metadata.fromData(data);

        assertTrue(restored.containsKey("München"));
        assertTrue(restored.containsKey("東京"));
        assertTrue(restored.containsKey("emoji-😀"));
        assertTrue(restored.containsKey(malformed));
        assertEquals("one", restored.get("München"));
        assertEquals("two", restored.get("東京"));
        assertEquals("three", restored.get("emoji-😀"));
        assertEquals("four", restored.get(malformed));
        assertFalse(restored.containsKey("emoji-😁"));
        assertNull(restored.get("Münche"));
        assertSame(data, restored.toData());
    }

    @Test
    void readsEmptyOpaqueMetadataWithoutMaterializingIt() {
        Data<byte[]> data = Metadata.empty().toData();
        Metadata restored = Metadata.fromData(data);

        assertFalse(restored.containsKey("$finalChunk"));
        assertNull(restored.get("$finalChunk"));
        assertSame(data, restored.toData());
    }

    @Test
    void extractsTraceValuesFromOpaqueData() {
        Data<byte[]> data = Metadata.of(
                "$trace.workflow", "München-東京",
                "$trace.attempt", "2",
                "tenant", "demo").toData();
        Metadata restored = Metadata.fromData(data);

        assertEquals(Map.of(
                "$trace.workflow", "München-東京",
                "$trace.attempt", "2"), restored.getTraceEntries());
        assertSame(data, restored.toData());
    }

    @Test
    void mergesNormalizedValuesIntoOpaqueDataWithoutChangingTheBase() {
        Metadata base = Metadata.fromData(Metadata.of(
                "tenant", "old",
                "unchanged", "München").toData());

        Metadata merged = base.with(Map.of(
                "tenant", "new",
                "新", "東京"));

        assertEquals("old", base.get("tenant"));
        assertEquals("new", merged.get("tenant"));
        assertEquals("München", merged.get("unchanged"));
        assertEquals("東京", merged.get("新"));
        assertEquals(3, merged.getEntries().size());
        assertEquals(merged, Metadata.fromData(merged.toData()));
    }

    @Test
    void appliesRepeatedChangesToSerializedMetadata() {
        Metadata original = Metadata.of(
                "tenant", "München",
                "remove", "me",
                "unchanged", "value");
        Data<byte[]> originalData = original.toData();

        Map<String, Object> firstChanges = new HashMap<>();
        firstChanges.put("tenant", "東京");
        firstChanges.put("remove", null);
        Metadata changed = Metadata.fromData(originalData)
                .with(firstChanges)
                .with("added", 42)
                .with("ignored", Optional.empty());

        assertEquals(3, changed.getEntries().size());
        assertEquals("東京", changed.get("tenant"));
        assertEquals("value", changed.get("unchanged"));
        assertEquals("42", changed.get("added"));
        assertFalse(changed.containsKey("remove"));
        assertFalse(changed.containsKey("ignored"));
        assertSame(changed.toData(), changed.toData());
        assertEquals(changed, Metadata.fromData(changed.toData()));
        assertSame(originalData, original.toData());
    }

    @Test
    void canRemoveAndRestoreSerializedEntryWithoutChangingBase() {
        Metadata base = Metadata.of(Map.of("key", "base", "other", "value"));
        Metadata restored = Metadata.fromData(base.toData());

        Metadata removed = restored.without("key");
        Metadata replaced = removed.with("key", "replacement");

        assertEquals(1, removed.getEntries().size());
        assertFalse(removed.containsKey("key"));
        assertEquals(2, replaced.getEntries().size());
        assertEquals("replacement", replaced.get("key"));
        assertEquals("base", restored.get("key"));
        assertEquals(replaced, Metadata.fromData(replaced.toData()));
    }

    @Test
    void rejectsUnknownSerializedMetadataDescriptor() {
        assertThrows(IllegalArgumentException.class,
                     () -> Metadata.fromData(new Data<>(new byte[4], "other", 0, Metadata.DATA_FORMAT)));
        assertThrows(IllegalArgumentException.class,
                     () -> Metadata.fromData(new Data<>(new byte[4], Metadata.DATA_TYPE, 1, Metadata.DATA_FORMAT)));
    }


    private enum TestState {
        ACTIVE
    }
}
