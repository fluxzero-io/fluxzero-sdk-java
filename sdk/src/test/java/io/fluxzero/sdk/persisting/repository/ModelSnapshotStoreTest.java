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

package io.fluxzero.sdk.persisting.repository;

import io.fluxzero.common.api.modeling.ModelSnapshotMutation;
import io.fluxzero.common.api.search.SerializedDocument;
import io.fluxzero.sdk.common.serialization.jackson.JacksonSerializer;
import io.fluxzero.sdk.persisting.search.DocumentStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ModelSnapshotStoreTest {

    private final JacksonSerializer serializer =
            new JacksonSerializer();
    private final ModelSnapshotStore subject =
            new ModelSnapshotStore(
                    mock(DocumentStore.class), serializer);

    @Test
    void readsRawModelSnapshotDocument() throws Exception {
        Instant timestamp = Instant.ofEpochMilli(1234L);
        SerializedDocument document =
                new ModelSnapshotMutation(
                        serializer.serialize("value"),
                        timestamp.toEpochMilli(),
                        2, 3)
                        .toDocument("model-1", 5L, 8L);

        ModelSnapshotStore.Snapshot snapshot =
                deserialize(document).orElseThrow();

        assertEquals("value", snapshot.value());
        assertEquals(5L, snapshot.sequenceNumber());
        assertEquals(8L, snapshot.stateIndex());
        assertEquals(timestamp, snapshot.timestamp());
    }

    @Test
    void readsLegacyWrappedModelSnapshotDocument() throws Exception {
        Instant timestamp = Instant.ofEpochMilli(1234L);
        var legacy = new ModelSnapshotStore.SnapshotDocument(
                "snapshot-1", "model-1", 5L, 8L,
                timestamp, serializer.serialize("value"));
        SerializedDocument document = new SerializedDocument(
                legacy.id(), timestamp.toEpochMilli(), null,
                ModelSnapshotStore.SNAPSHOT_COLLECTION,
                serializer.serialize(legacy), null,
                Set.of(), Set.of());

        ModelSnapshotStore.Snapshot snapshot =
                deserialize(document).orElseThrow();

        assertEquals("value", snapshot.value());
        assertEquals(5L, snapshot.sequenceNumber());
        assertEquals(8L, snapshot.stateIndex());
        assertEquals(timestamp, snapshot.timestamp());
    }

    @SuppressWarnings("unchecked")
    private Optional<ModelSnapshotStore.Snapshot> deserialize(
            SerializedDocument document) throws Exception {
        Method method = ModelSnapshotStore.class.getDeclaredMethod(
                "deserialize", SerializedDocument.class);
        method.setAccessible(true);
        return (Optional<ModelSnapshotStore.Snapshot>)
                method.invoke(subject, document);
    }
}
