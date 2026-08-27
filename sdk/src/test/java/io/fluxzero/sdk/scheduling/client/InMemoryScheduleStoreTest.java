/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.sdk.scheduling.client;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.scheduling.SerializedSchedule;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.fluxzero.common.Guarantee.STORED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryScheduleStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");

    @Test
    void invokesMonitorsAfterAtomicallyStoringScheduleAndReleasingStoreLock() {
        InMemoryScheduleStore store = new InMemoryScheduleStore(
                Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
        store.setRetentionTime(null);
        AtomicBoolean invoked = new AtomicBoolean();
        store.registerMonitor(messages -> {
            assertFalse(Thread.holdsLock(store));
            assertEquals("message-1", store.getSchedule("schedule").getMessage().getMessageId());
            invoked.set(true);
        });

        store.schedule(STORED, schedule("schedule", "message-1", false)).join();

        assertTrue(invoked.get());
    }

    @Test
    void preservesIfAbsentReplacementAndCancellationSemantics() {
        InMemoryScheduleStore store = new InMemoryScheduleStore(
                Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
        store.setRetentionTime(null);

        store.schedule(STORED, schedule("schedule", "original", false)).join();
        store.schedule(STORED, schedule("schedule", "ignored", true)).join();
        assertEquals("original", store.getSchedule("schedule").getMessage().getMessageId());

        store.schedule(STORED, schedule("schedule", "replacement", false)).join();
        assertEquals("replacement", store.getSchedule("schedule").getMessage().getMessageId());

        store.cancelSchedule("schedule", STORED).join();
        assertNull(store.getSchedule("schedule"));
    }

    private static SerializedSchedule schedule(String scheduleId, String messageId, boolean ifAbsent) {
        SerializedMessage message = new SerializedMessage(
                new Data<>(messageId.getBytes(UTF_8), String.class.getName(), 0, "text/plain"),
                Metadata.empty(), messageId, NOW.toEpochMilli());
        return new SerializedSchedule(scheduleId, NOW.plusSeconds(60).toEpochMilli(), message, ifAbsent);
    }
}
