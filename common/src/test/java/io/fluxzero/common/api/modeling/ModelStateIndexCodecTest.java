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

package io.fluxzero.common.api.modeling;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelStateIndexCodecTest {

    @Test
    void retainsSignedLongOrderLexicographically() {
        List<Long> values = List.of(
                Long.MIN_VALUE,
                Long.MIN_VALUE + 1L,
                -1L,
                0L,
                1L,
                Long.MAX_VALUE - 1L,
                Long.MAX_VALUE);

        List<String> encoded = values.stream()
                .map(ModelStateIndexCodec::encode)
                .toList();

        assertEquals(encoded.stream().sorted().toList(), encoded);
        assertEquals(values.size(), encoded.stream().distinct().count());
        encoded.forEach(value -> assertEquals(20, value.length()));
    }
}
