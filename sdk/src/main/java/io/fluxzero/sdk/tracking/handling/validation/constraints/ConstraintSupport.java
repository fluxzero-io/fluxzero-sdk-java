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

package io.fluxzero.sdk.tracking.handling.validation.constraints;

import jakarta.validation.constraints.Pattern;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;

final class ConstraintSupport {
    private ConstraintSupport() {
    }

    static Optional<BigDecimal> asBigDecimal(Object value) {
        try {
            return switch (value) {
                case BigDecimal bigDecimal -> Optional.of(bigDecimal);
                case BigInteger bigInteger -> Optional.of(new BigDecimal(bigInteger));
                case Number number -> Optional.of(new BigDecimal(number.toString()));
                case CharSequence sequence -> Optional.of(new BigDecimal(sequence.toString()));
                default -> Optional.empty();
            };
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static boolean matches(String value, String regexp, Pattern.Flag[] flags) {
        int patternFlags = 0;
        for (Pattern.Flag flag : flags) {
            patternFlags |= flag.getValue();
        }
        return java.util.regex.Pattern.compile(regexp, patternFlags).matcher(value).matches();
    }

    static Iterable<?> iterable(Object value) {
        if (value instanceof Iterable<?> iterable) {
            return iterable;
        }
        if (value != null && value.getClass().isArray()) {
            return () -> new java.util.Iterator<>() {
                private int index;

                /** {@inheritDoc} */
                @Override
                public boolean hasNext() {
                    return index < Array.getLength(value);
                }

                /** {@inheritDoc} */
                @Override
                public Object next() {
                    return Array.get(value, index++);
                }
            };
        }
        return null;
    }
}
