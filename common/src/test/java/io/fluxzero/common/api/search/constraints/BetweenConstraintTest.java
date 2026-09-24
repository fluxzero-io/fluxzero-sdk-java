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

package io.fluxzero.common.api.search.constraints;

import io.fluxzero.common.api.modeling.ModelStateIndexCodec;
import io.fluxzero.common.api.search.SortableEntry;
import io.fluxzero.common.search.Document;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BetweenConstraintTest {

    @Test
    void matchesPreEncodedSortableText() {
        Document document = Document.builder()
                .id("document")
                .sortables(Set.of(new SortableEntry(
                        "stateIndex",
                        ModelStateIndexCodec.encode(Long.MAX_VALUE - 1L))))
                .build();

        assertTrue(BetweenConstraint.below(
                ModelStateIndexCodec.encode(Long.MAX_VALUE),
                "stateIndex").matches(document));
        assertFalse(BetweenConstraint.below(
                ModelStateIndexCodec.encode(Long.MAX_VALUE - 1L),
                "stateIndex").matches(document));
    }

    @Test
    void retainsNumericSortableMatching() {
        Document document = Document.builder()
                .id("document")
                .sortables(Set.of(new SortableEntry("value", 10L)))
                .build();

        assertTrue(BetweenConstraint.below(11L, "value").matches(document));
        assertFalse(BetweenConstraint.below(10L, "value").matches(document));
    }
}
