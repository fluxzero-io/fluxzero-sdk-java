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

package io.fluxzero.sdk;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FluxzeroClockTest {

    @Test
    void usesTheClockFromTheThreadBoundFluxzeroInstance() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-01T08:00:00Z"),
                ZoneOffset.UTC);
        Fluxzero fluxzero = mock(
                Fluxzero.class, Answers.CALLS_REAL_METHODS);
        when(fluxzero.clock()).thenReturn(clock);

        fluxzero.apply(current -> {
            assertSame(clock, Fluxzero.currentClock());
            assertEquals(clock.instant(), Fluxzero.currentTime());
            assertEquals(clock.millis(), Fluxzero.currentClock().millis());
            return null;
        });
    }
}
