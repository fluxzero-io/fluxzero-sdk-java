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
 *
 */

package io.fluxzero.common;

import lombok.Value;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SearchUtilsTest {

    @Test
    void returnsNullWhenTimestampPropertyExistsButValueIsMissing() {
        Instant fallback = Instant.parse("2024-01-01T00:00:00Z");

        Instant result = SearchUtils.parseTimeProperty(
                "nested.timestamp", new TimestampContainer(new NestedTimestamp(null)), false, () -> fallback);

        assertNull(result);
    }

    @Test
    void returnsFallbackWhenTimestampPropertyDoesNotExist() {
        Instant fallback = Instant.parse("2024-01-01T00:00:00Z");

        Instant result = SearchUtils.parseTimeProperty("nested.unknown", new TimestampContainer(new NestedTimestamp(
                Instant.parse("2024-02-01T00:00:00Z"))), false, () -> fallback);

        assertEquals(fallback, result);
    }

    @Test
    void parsesLocalDateTimestampProperties() {
        Instant result = SearchUtils.parseTimeProperty(
                "startDate", new DateContainer(LocalDate.of(2024, 2, 20)), false, () -> null);

        assertEquals(LocalDate.of(2024, 2, 20).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant(),
                     result);
    }

    @Test
    void returnsFallbackForUnsupportedTimestampType() {
        Instant fallback = Instant.parse("2024-01-01T00:00:00Z");

        Instant result = SearchUtils.parseTimeProperty("count", new UnsupportedTimestamp(5), false, () -> fallback);

        assertEquals(fallback, result);
    }

    @Value
    static class TimestampContainer {
        NestedTimestamp nested;
    }

    @Value
    static class NestedTimestamp {
        Instant timestamp;
    }

    @Value
    static class DateContainer {
        LocalDate startDate;
    }

    @Value
    static class UnsupportedTimestamp {
        Integer count;
    }
}
