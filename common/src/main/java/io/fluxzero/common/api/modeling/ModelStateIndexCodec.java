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

/** Encodes opaque model state indices as fixed-width text that retains signed-long ordering. */
public final class ModelStateIndexCodec {
    private static final int WIDTH = 20;
    private static final String ZEROES = "0".repeat(WIDTH);

    private ModelStateIndexCodec() {
    }

    /**
     * Returns an order-preserving representation of the complete signed {@code long} range.
     *
     * <p>The sign bit is flipped before unsigned decimal formatting. Fixed-width padding then makes lexical ordering
     * identical to the original numeric ordering without scaling or loss of precision.
     */
    public static String encode(long value) {
        String unsigned = Long.toUnsignedString(value ^ Long.MIN_VALUE);
        return ZEROES.substring(unsigned.length()) + unsigned;
    }
}
