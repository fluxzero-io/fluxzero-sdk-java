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

package io.fluxzero.sdk.persisting.search.client;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.search.SerializedDocument;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.fluxzero.common.Guarantee.STORED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemorySearchStoreMonitoringTest {

    @Test
    void replacesByMessageIdWhileRetainingIndexAndCursorOrdering() {
        InMemorySearchStore store = new InMemorySearchStore(Duration.ofDays(1));
        List<Integer> notificationSizes = new ArrayList<>();
        store.registerMonitor("documents", messages -> notificationSizes.add(messages.size()));

        store.index(List.of(document("a"), document("b")), STORED, false).join();
        store.index(List.of(document("a")), STORED, false).join();

        List<SerializedMessage> messages = store.openStream(
                "documents", null, Integer.MAX_VALUE).toList();
        assertEquals(List.of("b", "a"), messages.stream()
                .map(SerializedMessage::getMessageId).toList());
        assertTrue(messages.get(0).getIndex() < messages.get(1).getIndex());
        assertEquals(List.of("a"), store.openStream(
                        "documents", messages.getFirst().getIndex(), Integer.MAX_VALUE)
                .map(SerializedMessage::getMessageId).toList());
        assertEquals(List.of(2, 1), notificationSizes);
    }

    @Test
    void retentionRemovesOrderedEntriesAndTheirMessageIdLookups() {
        InMemorySearchStore.DocumentUpdateLog log = new InMemorySearchStore.DocumentUpdateLog();
        log.store(List.of(message("a", 10L), message("b", 20L), message("a", 30L)));

        assertEquals(2, log.messageCount());
        assertEquals(2, log.lookupCount());
        assertEquals(30L, log.latest("a").getIndex());

        log.purgeThrough(20L);

        assertEquals(1, log.messageCount());
        assertEquals(1, log.lookupCount());
        assertNull(log.latest("b"));
        assertEquals(30L, log.latest("a").getIndex());

        log.purgeThrough(30L);

        assertTrue(log.isEmpty());
        assertEquals(0, log.lookupCount());
        assertNull(log.latest("a"));
    }

    @Test
    void truncationAndCollectionDeletionDiscardTheCompleteMonitorLog() {
        InMemorySearchStore store = new InMemorySearchStore(Duration.ofDays(1));
        store.registerMonitor((collection, messages) -> { });

        store.index(List.of(document("a")), STORED, false).join();
        store.truncateCollection("documents");
        assertEquals(0, store.openStream("documents", null, Integer.MAX_VALUE).count());

        store.index(List.of(document("a")), STORED, false).join();
        store.deleteCollection("documents", STORED).join();
        assertEquals(0, store.openStream("documents", null, Integer.MAX_VALUE).count());

        store.index(List.of(document("a")), STORED, false).join();
        assertEquals(List.of("a"), store.openStream("documents", null, Integer.MAX_VALUE)
                .map(SerializedMessage::getMessageId).toList());
    }

    private static SerializedDocument document(String id) {
        return new SerializedDocument(
                id, null, null, "documents",
                new Data<>(new byte[0], "TestDocument", 0),
                null, Set.of(), Set.of());
    }

    private static SerializedMessage message(String id, long index) {
        SerializedMessage message = new SerializedMessage(
                new Data<>(new byte[0], "TestDocument", 0),
                Metadata.empty(), id, 0L);
        message.setIndex(index);
        return message;
    }
}
