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
    void getBatchRejectsNegativeMaxSize() {
        InMemoryMessageStore store = new InMemoryMessageStore(EVENT, Duration.ofMinutes(5));

        assertThrows(IllegalArgumentException.class, () -> store.getBatch(null, -1, true));
    }

    private static SerializedMessage message(long index) {
        SerializedMessage result = new SerializedMessage(
                new Data<>(("event-" + index).getBytes(UTF_8), String.class.getName(), 0, "text/plain"),
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
