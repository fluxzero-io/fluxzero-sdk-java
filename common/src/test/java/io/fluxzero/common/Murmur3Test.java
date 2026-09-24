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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Murmur3Test {

    @ParameterizedTest
    @MethodSource("hashVectors")
    void preservesUtf8HashValues(String value, int expected) {
        assertEquals(expected, Murmur3.murmurhash3_x86_32(value));
    }

    private static Stream<Arguments> hashVectors() {
        return Stream.of(
                Arguments.of("", 0),
                Arguments.of("a", 1_009_084_850),
                Arguments.of("abc", -1_277_324_294),
                Arguments.of("abcd", 1_139_631_978),
                Arguments.of("abcde", -392_455_434),
                Arguments.of("0123456789abcdef0123456789abcdef", -1_287_447_058),
                Arguments.of("München", 1_269_059_171),
                Arguments.of("東京", -1_765_863_102),
                Arguments.of("😀", -1_095_487_750),
                Arguments.of("a😀z", -1_864_773_955));
    }
}
