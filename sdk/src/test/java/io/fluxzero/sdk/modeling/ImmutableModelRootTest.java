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

package io.fluxzero.sdk.modeling;

import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.common.serialization.Serializer;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImmutableModelRootTest {

    @Test
    void committedRootUsesTheAuthoritativeTimestampWithoutConsultingTheClock() {
        Instant timestamp = Instant.parse("2026-08-01T08:00:00Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenThrow(
                new AssertionError("ambient clock accessed"));
        Fluxzero fluxzero = mock(
                Fluxzero.class, Answers.CALLS_REAL_METHODS);
        when(fluxzero.clock()).thenReturn(clock);

        List<ImmutableModelRoot<String>> copies = fluxzero.apply(ignored -> {
            ImmutableModelRoot<String> result = ImmutableModelRoot.revision(
                    "model-1", String.class, "id", "value",
                    mock(EntityHelper.class), mock(Serializer.class),
                    "event-1", 42L, timestamp, 3L, 7L, null);
            return List.of(
                    result,
                    (ImmutableModelRoot<String>) result.withEventIndex(
                            43L, "event-2"),
                    (ImmutableModelRoot<String>) result.withSequenceNumber(4L),
                    result.withPrevious(result));
        });
        ImmutableModelRoot<String> result = copies.get(0);

        assertEquals(timestamp, result.timestamp());
        assertEquals(3L, result.sequenceNumber());
        assertEquals(7L, result.stateIndex());
        assertEquals("event-1", result.lastEventId());
        assertEquals(42L, result.lastEventIndex());
        assertNull(result.previous());
        assertEquals(timestamp, copies.get(1).timestamp());
        assertEquals("event-2", copies.get(1).lastEventId());
        assertEquals(43L, copies.get(1).lastEventIndex());
        assertEquals(timestamp, copies.get(2).timestamp());
        assertEquals(4L, copies.get(2).sequenceNumber());
        assertEquals(timestamp, copies.get(3).timestamp());
        assertSame(result, copies.get(3).previous());
    }
}
