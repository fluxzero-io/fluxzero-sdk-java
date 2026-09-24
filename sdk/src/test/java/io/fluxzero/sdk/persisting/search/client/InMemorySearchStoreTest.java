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
package io.fluxzero.sdk.persisting.search.client;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.search.SerializedDocument;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.fluxzero.common.Guarantee.STORED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemorySearchStoreTest {
    private final InMemorySearchStore store = new InMemorySearchStore(null);

    @Test
    void lateReaderReceivesUpdatesStoredWithoutMonitors() {
        index("one", "first");
        index("two", "second");
        index("one", "latest");
        var stream = new CollectionMessageStore(store, "documents");
        List<SerializedMessage> notifications = new ArrayList<>();
        var registration = stream.registerMonitor(notifications::addAll);
        assertTrue(notifications.isEmpty(), "Registration must not replay callbacks");
        var batch = stream.getBatch(0L, 10, true);
        assertEquals(List.of("second", "latest"), values(batch));
        assertTrue(batch.getFirst().getIndex() < batch.getLast().getIndex());
        assertEquals(List.of(batch.getLast()), stream.getBatch(batch.getFirst().getIndex(), 10, false));
        index("three", "live");
        assertEquals(List.of("live"), values(notifications));
        registration.cancel();
        index("four", "offline");
        assertEquals(List.of("live"), values(notifications));
        assertEquals(List.of("second", "latest", "live", "offline"), values(stream.getBatch(0L, 10, true)));
    }

    @Test
    void skippedWritesDoNotReplaceStreamEntries() {
        index("one", "original");
        store.index(List.of(document("one", "ignored")), STORED, true).join();
        assertEquals(List.of("original"), values(store.openStream("documents", null, 10).toList()));
    }

    @Test
    void bulkWritesRetainLastRevisionAndNotifyOnce() {
        List<List<SerializedMessage>> notifications = new ArrayList<>();
        store.registerMonitor("documents", notifications::add);
        store.index(List.of(document("one", "old"), document("two", "other"), document("one", "latest")),
                    STORED, false).join();
        assertEquals(1, notifications.size());
        assertEquals(List.of("latest", "other"), values(notifications.getFirst()));
        assertEquals(notifications.getFirst(), store.openStream("documents", null, 10).toList());
    }

    @Test
    void collectionDeletionClearsOfflineStream() {
        index("one", "before");
        store.deleteCollection("documents", STORED).join();
        assertEquals(0, store.openStream("documents", null, 10).count());
        index("one", "after");
        assertEquals(List.of("after"), values(store.openStream("documents", null, 10).toList()));
    }

    @Test
    void retentionStillAppliesWithoutMonitors() {
        store.setRetentionTime(Duration.ofDays(-1));
        index("one", "expired");
        assertEquals(0, store.openStream("documents", null, 10).count());
        store.setRetentionTime(null);
        index("one", "retained");
        index("one", "latest");
        assertEquals(List.of("latest"), values(store.openStream("documents", null, 10).toList()));
    }

    @Test
    void sameDocumentIdInDifferentCollectionsRemainsIndependent() {
        index("one", "original");
        store.index(List.of(document("one", "other").withCollection("other")), STORED, false).join();
        index("one", "latest");
        assertEquals(List.of("latest"), values(store.openStream("documents", null, 10).toList()));
        assertEquals(List.of("other"), values(store.openStream("other", null, 10).toList()));
        store.truncateCollection("documents");
        assertEquals(List.of("other"), values(store.openStream("other", null, 10).toList()));
    }

    @Test
    void failedNotificationDoesNotDiscardStoredUpdate() {
        store.registerMonitor((collection, messages) -> { throw new IllegalStateException("monitor failed"); });
        assertThrows(IllegalStateException.class, () -> index("one", "retained"));
        assertEquals(List.of("retained"), values(store.openStream("documents", null, 10).toList()));
    }

    private void index(String id, String value) {
        store.index(List.of(document(id, value)), STORED, false).join();
    }

    private SerializedDocument document(String id, String value) {
        return new SerializedDocument(id, 0L, null, "documents",
                new Data<>(value.getBytes(UTF_8), String.class.getName(), 0, "text/plain"), null, Set.of(), Set.of());
    }

    private static List<String> values(List<SerializedMessage> messages) {
        return messages.stream().map(m -> new String(m.getData().getValue(), UTF_8)).toList();
    }
}
