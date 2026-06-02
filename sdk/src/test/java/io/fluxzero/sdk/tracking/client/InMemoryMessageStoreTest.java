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

package io.fluxzero.sdk.tracking.client;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.tracking.MessageStoreBatch;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static io.fluxzero.common.MessageType.EVENT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryMessageStoreTest {

    @Test
    void getBatchDoesNotReadPastMaxSize() {
        CountingStore store = new CountingStore(2);
        store.setRetentionTime(null);
        store.append(List.of(message(1L), message(2L), message(3L))).join();

        List<SerializedMessage> batch = store.getBatch(null, 2, true);

        assertEquals(List.of(1L, 2L), batch.stream().map(SerializedMessage::getIndex).toList());
        assertEquals(2, store.iteratedCount());
    }

    @Test
    void getBatchDoesNotReadPastMaxBytes() {
        CountingStore store = new CountingStore(2);
        store.setRetentionTime(null);
        store.append(List.of(message(1L, "1234"), message(2L, "5678"), message(3L, "9012"))).join();

        List<SerializedMessage> batch = store.getBatch(null, 10, true, 5L);

        assertEquals(List.of(1L), batch.stream().map(SerializedMessage::getIndex).toList());
        assertEquals(2, store.iteratedCount());
    }

    @Test
    void getBatchIncludesFirstMessageWhenItExceedsMaxBytes() {
        InMemoryMessageStore store = new InMemoryMessageStore(EVENT, Duration.ofMinutes(5));
        store.setRetentionTime(null);
        store.append(List.of(message(1L, "123456"), message(2L, "7890"))).join();

        List<SerializedMessage> batch = store.getBatch(null, 10, true, 5L);

        assertEquals(List.of(1L), batch.stream().map(SerializedMessage::getIndex).toList());
        assertEquals(6L, batch.getFirst().getBytes());
    }

    @Test
    void scanBatchAppliesMaxBytesAfterPredicateWithoutReadingRest() {
        CountingStore store = new CountingStore(4);
        store.setRetentionTime(null);
        store.append(List.of(message(1L, "ignored-large-payload"),
                             message(2L, "1234"),
                             message(3L, "5678"),
                             message(4L, "9012"),
                             message(5L, "3456"))).join();

        MessageStoreBatch batch = store.scanBatch(
                null, 10, true, 8L, message -> message.getIndex() != 1L);

        assertEquals(List.of(2L, 3L), batch.messages().stream().map(SerializedMessage::getIndex).toList());
        assertEquals(4L, batch.lastScannedIndex());
        assertEquals(4, batch.scannedSize());
        assertTrue(batch.byteLimited());
        assertEquals(4, store.iteratedCount());
    }

    @Test
    void scanBatchReportsSourceProgressWhenMessagesAreFilteredOut() {
        InMemoryMessageStore store = new InMemoryMessageStore(EVENT, Duration.ofMinutes(5));
        store.setRetentionTime(null);
        store.append(List.of(message(1L), message(2L), message(3L))).join();

        MessageStoreBatch batch = store.scanBatch(null, 3, true, 0L, message -> false);

        assertEquals(List.of(), batch.messages());
        assertEquals(3L, batch.lastScannedIndex());
        assertEquals(3, batch.scannedSize());
    }

    @Test
    void getBatchRejectsNegativeMaxSize() {
        InMemoryMessageStore store = new InMemoryMessageStore(EVENT, Duration.ofMinutes(5));

        assertThrows(IllegalArgumentException.class, () -> store.getBatch(null, -1, true));
    }

    private static SerializedMessage message(long index) {
        return message(index, "event-" + index);
    }

    private static SerializedMessage message(long index, String payload) {
        SerializedMessage result = new SerializedMessage(
                new Data<>(payload.getBytes(UTF_8), String.class.getName(), 0, "text/plain"),
                Metadata.empty(), "event-" + index + "-" + UUID.randomUUID(), Instant.now().toEpochMilli());
        result.setIndex(index);
        return result;
    }

    private static class CountingStore extends InMemoryMessageStore {
        private final int maxIterated;
        private final AtomicInteger iterated = new AtomicInteger();

        private CountingStore(int maxIterated) {
            super(EVENT, Duration.ofMinutes(5));
            this.maxIterated = maxIterated;
        }

        private int iteratedCount() {
            return iterated.get();
        }

        @Override
        protected Collection<SerializedMessage> filterMessages(Collection<SerializedMessage> messages) {
            return new AbstractCollection<>() {
                @Override
                public Iterator<SerializedMessage> iterator() {
                    Iterator<SerializedMessage> delegate = messages.iterator();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return delegate.hasNext();
                        }

                        @Override
                        public SerializedMessage next() {
                            int count = iterated.incrementAndGet();
                            if (count > maxIterated) {
                                throw new AssertionError("Batch read continued past maxSize");
                            }
                            return delegate.next();
                        }
                    };
                }

                @Override
                public int size() {
                    return messages.size();
                }
            };
        }
    }
}
