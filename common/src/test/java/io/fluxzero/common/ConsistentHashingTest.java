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

package io.fluxzero.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsistentHashingTest {

    @Test
    void minimumHashReturnsNonNegativeSegment() {
        int maxSegments = 127;

        int segment = ConsistentHashing.computeSegment("routing-key", ignored -> Integer.MIN_VALUE, maxSegments);

        assertEquals((int) (Math.abs((long) Integer.MIN_VALUE) % maxSegments), segment);
        assertTrue(segment >= 0 && segment < maxSegments);
    }

    @Test
    void regularNegativeHashMappingIsPreserved() {
        assertEquals(5, ConsistentHashing.computeSegment("routing-key", ignored -> -5, 127));
    }

    @Test
    void fallsInRange() {
        int[] range = new int[]{0, 64};
        assertTrue(ConsistentHashing.fallsInRange(0, range));
        assertTrue(ConsistentHashing.fallsInRange(32, range));
        assertTrue(ConsistentHashing.fallsInRange(33, range));
        assertFalse(ConsistentHashing.fallsInRange(64, range));
        assertFalse(ConsistentHashing.fallsInRange(65, range));
        assertFalse(ConsistentHashing.fallsInRange(200, range));
    }

    @Test
    void neverFallsInRange() {
        int[] range = new int[]{0, 0};
        assertFalse(ConsistentHashing.fallsInRange(0, range));
        assertFalse(ConsistentHashing.fallsInRange(32, range));
        assertFalse(ConsistentHashing.fallsInRange(33, range));
        assertFalse(ConsistentHashing.fallsInRange(64, range));
        assertFalse(ConsistentHashing.fallsInRange(65, range));
        assertFalse(ConsistentHashing.fallsInRange(200, range));
    }

    @Test
    void emptyRangeCheck() {
        assertTrue(ConsistentHashing.isEmptyRange(new int[]{0, 0}));
        assertTrue(ConsistentHashing.isEmptyRange(new int[]{1, 1}));
    }
}
