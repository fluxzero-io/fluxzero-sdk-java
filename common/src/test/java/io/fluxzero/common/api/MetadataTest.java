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

package io.fluxzero.common.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetadataTest {

    @Test
    void deserializesObjectsAndMapsStringsAndInvalidJson() {
        SampleValue objectValue = new SampleValue("object");
        SampleValue mappedValue = new SampleValue("mapped");
        Metadata metadata = Metadata.of("object", objectValue)
                .with("text", "user-123")
                .with("openObject", "{invalid")
                .with("invalidObject", "{invalid}")
                .with("invalidArray", "[invalid]")
                .with("trailingObject", "{\"value\":\"ignored\"}{}")
                .withNull("null");

        assertEquals(objectValue, metadata.get("object", SampleValue.class, value -> mappedValue));
        assertEquals(mappedValue, metadata.get("text", SampleValue.class, value -> mappedValue));
        assertEquals(mappedValue, metadata.get("openObject", SampleValue.class, value -> mappedValue));
        assertEquals(mappedValue, metadata.get("invalidObject", SampleValue.class, value -> mappedValue));
        assertEquals(mappedValue, metadata.get("invalidArray", SampleValue.class, value -> mappedValue));
        assertEquals(mappedValue, metadata.get("trailingObject", SampleValue.class, value -> mappedValue));
        assertNull(metadata.get("null", SampleValue.class, value -> mappedValue));
        assertNull(metadata.get("missing", SampleValue.class, value -> mappedValue));
    }

    @Test
    void propagatesMappingFailureForValidJson() {
        Metadata metadata = Metadata.of("object", "{\"value\":\"not-a-number\"}");

        assertThrows(IllegalStateException.class,
                     () -> metadata.get("object", NumericValue.class, value -> new NumericValue(1)));
    }

    private record SampleValue(String value) {
    }

    private record NumericValue(int value) {
    }
}
