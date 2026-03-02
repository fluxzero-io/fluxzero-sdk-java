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

package io.fluxzero.common.api.tracking;

import io.fluxzero.common.api.Data;
import io.fluxzero.common.api.SerializedMessage;
import io.fluxzero.common.serialization.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.fluxzero.common.serialization.JsonUtils.convertValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("mergeScenarios")
    void mergeScenarios(String scenarioName, String left, String right, String expected) {
        Position merged = parse(left).merge(parse(right));
        assertEquals(indexBySegment(parse(expected)), indexBySegment(merged), scenarioName);

        Position reversed = parse(right).merge(parse(left));
        assertEquals(indexBySegment(parse(expected)), indexBySegment(reversed), scenarioName + " (commutative)");
    }

    @Test
    void getIndexReturnsExpectedValues() {
        Position position = parse("[[0,64],100,[64,128],200]");
        assertEquals(100L, position.getIndex(0).orElse(null));
        assertEquals(200L, position.getIndex(64).orElse(null));
        assertNull(position.getIndex(128).orElse(null));
    }

    @Test
    void isNewAndLowestIndexCoverOverlapAndDisjointCases() {
        Position position = parse("[[0,64],200,[64,128],100]");
        assertFalse(position.isNew(new int[]{32, 96}));
        assertTrue(position.isNew(new int[]{128, 129}));
        assertEquals(100L, position.lowestIndexForSegment(new int[]{32, 96}).orElse(null));
        assertNull(position.lowestIndexForSegment(new int[]{128, 129}).orElse(null));
    }

    @Test
    void isNewIndexAndIsNewMessageAreConsistent() {
        Position position = parse("[[0,64],100,[64,128],200]");
        assertTrue(position.isNewIndex(32, 101L));
        assertFalse(position.isNewIndex(32, 100L));
        assertTrue(position.isNewMessage(serializedMessage(32, 101L)));
        assertFalse(position.isNewMessage(serializedMessage(33, 100L)));
    }

    @Test
    void serializePosition() {
        Position position = parse("[[0,16],100,[24,32],100,[32,64],200]");
        assertEquals(position, JsonUtils.convertValue(position, Position.class));
    }

    @Test
    void serializeLegacyPosition() {
        Position position = parse("[[0,16],100,[24,32],100,[32,64],200]");
        assertEquals(position,
                convertValue(Map.of("indexBySegment", expandRanges(position.getSegmentRanges())), Position.class));
    }

    static Stream<Arguments> mergeScenarios() {
        return Stream.of(
                scenario("empty + empty", "[]", "[]", "[]"),
                scenario("empty + full range", "[]", "[[0,128],100]", "[[0,128],100]"),
                scenario("non-overlapping (left before right)", "[[0,32],100]", "[[64,96],200]", "[[0,32],100,[64,96],200]"),
                scenario("non-overlapping (right before left)", "[[96,128],200]", "[[32,64],100]", "[[32,64],100,[96,128],200]"),
                scenario("adjacent but not overlapping", "[[0,64],100]", "[[64,128],200]", "[[0,64],100,[64,128],200]"),
                scenario("exact same range, right higher", "[[0,128],100]", "[[0,128],120]", "[[0,128],120]"),
                scenario("exact same range, left higher", "[[0,128],120]", "[[0,128],100]", "[[0,128],120]"),
                scenario("exact same range, equal indexes", "[[0,128],100]", "[[0,128],100]", "[[0,128],100]"),
                scenario("partial overlap (left before right), right higher", "[[0,96],100]", "[[64,128],200]", "[[0,64],100,[64,128],200]"),
                scenario("partial overlap (left before right), right lower", "[[0,96],200]", "[[64,128],100]", "[[0,96],200,[96,128],100]"),
                scenario("partial overlap (left before right), equal index value", "[[0,96],200]", "[[64,128],200]", "[[0,128],200]"),
                scenario("partial overlap (right before left), right higher", "[[64,128],100]", "[[0,96],200]", "[[0,96],200,[96,128],100]"),
                scenario("left contains right, right higher", "[[0,128],100]", "[[32,96],200]", "[[0,32],100,[32,96],200,[96,128],100]"),
                scenario("left contains right, right lower", "[[0,128],200]", "[[32,96],100]", "[[0,128],200]"),
                scenario("right contains left, right higher", "[[32,96],100]", "[[0,128],200]", "[[0,128],200]"),
                scenario("right contains left, right lower", "[[32,96],200]", "[[0,128],100]", "[[0,32],100,[32,96],200,[96,128],100]"),
                scenario("single-point overlap at boundary segment", "[[0,64],100]", "[[63,128],200]", "[[0,63],100,[63,128],200]"),
                scenario("interleaved multi-range positions", "[[0,32],500,[64,96],700,[112,128],900]", "[[16,80],600,[96,120],800]", "[[0,16],500,[16,64],600,[64,96],700,[96,112],800,[112,128],900]"),
                scenario("mixed complete cover + disjoint tails", "[[0,24],300,[24,88],1000,[104,120],100]", "[[16,96],800,[120,128],200]", "[[0,16],300,[16,24],800,[24,88],1000,[88,96],800,[104,120],100,[120,128],200]"),
                scenario("multiple overlaps with same resulting index", "[[0,48],500,[48,96],700]", "[[24,72],700,[72,128],500]", "[[0,24],500,[24,96],700,[96,128],500]")
        );
    }

    private static Position parse(String json) {
        return JsonUtils.fromJson(json, Position.class);
    }

    private static Map<Integer, Long> indexBySegment(Position position) {
        Map<Integer, Long> map = new HashMap<>();
        for (SegmentRange range : position.getSegmentRanges()) {
            for (int segment = range.segmentStart(); segment < range.segmentEnd(); segment++) {
                map.put(segment, range.index());
            }
        }
        return map;
    }

    private static Map<Integer, Long> expandRanges(List<SegmentRange> ranges) {
        Map<Integer, Long> map = new HashMap<>();
        for (SegmentRange range : ranges) {
            for (int segment = range.segmentStart(); segment < range.segmentEnd(); segment++) {
                map.put(segment, range.index());
            }
        }
        return map;
    }

    private static Arguments scenario(String name, String left, String right, String expected) {
        return Arguments.of(name, left, right, expected);
    }

    private static SerializedMessage serializedMessage(int segment, Long index) {
        return new SerializedMessage(new Data<>(new byte[0], "Test", 0), null,
                segment, index, null, null, null, null, null, null);
    }
}
