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

package io.fluxzero.testserver.scheduling;

import io.fluxzero.common.Guarantee;
import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.Metadata;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.api.scheduling.SerializedSchedule;
import io.fluxzero.sdk.scheduling.client.InMemoryScheduleStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestServerScheduleStoreTest {

    @Test
    void getBatchReschedulesWhenTheHiddenFutureScheduleIsTheImmediateNextIndex() throws Exception {
        InMemoryScheduleStore delegate = new InMemoryScheduleStore();
        delegate.setRetentionTime(null);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        delegate.setClock(Clock.fixed(now, ZoneOffset.UTC));
        AtomicLong rescheduledIndex = new AtomicLong(-1);
        TestServerScheduleStore subject = new TestServerScheduleStore(delegate) {
            @Override
            protected void rescheduleNextDeadline(long nextIndex) {
                rescheduledIndex.set(nextIndex);
            }
        };

        Instant deadline = now.plusMillis(150);
        SerializedSchedule schedule = new SerializedSchedule(
                "schedule-1",
                deadline.toEpochMilli(),
                new SerializedMessage(new Data<>("test".getBytes(), "test", 0, null),
                                      Metadata.empty(), "message-1", now.toEpochMilli()),
                false);
        delegate.schedule(Guarantee.STORED, schedule).get();

        long scheduleIndex = schedule.getMessage().getIndex();
        subject.getBatch(scheduleIndex - 1, 1, false);

        assertEquals(scheduleIndex, rescheduledIndex.get());
        assertTrue(subject.getBatch(scheduleIndex - 1, 1, false).isEmpty());
    }
}
