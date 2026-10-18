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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.fluxzero.common.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class DecimalTest {
    record Facts(Decimal amount) { }

    @Test
    void equalityHashingAndRecordsIgnoreScaleButRetainRealChanges() {
        var values = List.of(new Decimal("900.00"), new Decimal("900"), new Decimal("9E2"));
        assertEquals(1, new HashSet<>(values).size());
        assertEquals(1, new TreeSet<>(values).size());
        assertEquals(new Facts(values.getFirst()), new Facts(values.getLast()));
        assertNotEquals(new Facts(values.getFirst()), new Facts(new Decimal("900.01")));
        assertEquals(Decimal.ZERO, new Decimal("0.000"));
        assertEquals(Decimal.ZERO.hashCode(), new Decimal("-0E+5").hashCode());
        assertNotEquals(BigDecimal.ONE, Decimal.ONE);
        assertNotEquals(Decimal.ONE, BigDecimal.ONE);
        assertEquals(new BigDecimal("900.00"), values.getFirst().toBigDecimal());
    }

    @Test
    void extremeScaleStillHasConsistentHashing() throws Exception {
        var a = new Decimal(new java.math.BigInteger("10"), Integer.MIN_VALUE);
        var b = new Decimal(new java.math.BigInteger("100"), Integer.MIN_VALUE + 1);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(1, new HashSet<>(List.of(a, b)).size());
        var restored = JsonUtils.writer.readValue(JsonUtils.asJson(a), Decimal.class);
        assertEquals(a.toBigDecimal(), restored.toBigDecimal());
        assertEquals(a.toBigDecimal(), JsonUtils.writer.readValue(a.toString(), Decimal.class).toBigDecimal());
        assertEquals(new BigDecimal(java.math.BigInteger.TEN, Integer.MIN_VALUE), a.toBigDecimal());
    }

    @Test
    void exposesTheCompleteBigDecimalApiWithDecimalResults() throws Exception {
        for (var constructor : BigDecimal.class.getConstructors()) {
            assertNotNull(Decimal.class.getConstructor(constructor.getParameterTypes()));
        }
        for (var method : BigDecimal.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isBridge() || method.isSynthetic()) {
                continue;
            }
            Class<?> result = decimalType(method.getReturnType());
            assertEquals(result, Decimal.class.getMethod(method.getName(), method.getParameterTypes()).getReturnType(),
                         method.toString());
            Class<?>[] decimalArguments = Arrays.stream(method.getParameterTypes()).map(DecimalTest::decimalType)
                    .toArray(Class<?>[]::new);
            assertEquals(result, Decimal.class.getMethod(method.getName(), decimalArguments).getReturnType());
        }
    }

    private static Class<?> decimalType(Class<?> type) {
        return type == BigDecimal.class ? Decimal.class : type == BigDecimal[].class ? Decimal[].class : type;
    }

    @Test
    void arithmeticRemainsDecimalAndRetainsBigDecimalRoundingAndExceptions() {
        var a = new Decimal("10.00");
        var b = new Decimal("3");
        assertEquals(new Decimal("39"), a.add(b).multiply(b));
        assertEquals(new Decimal("3.33"), a.divide(b, 2, RoundingMode.HALF_UP));
        assertEquals(a.toBigDecimal().divide(b.toBigDecimal(), MathContext.DECIMAL32),
                     a.divide(b, MathContext.DECIMAL32).toBigDecimal());
        assertArrayEquals(new Decimal[]{new Decimal("3.00"), Decimal.ONE}, a.divideAndRemainder(b));
        assertEquals(Decimal.TWO, new Decimal("4").sqrt(MathContext.DECIMAL128));
        assertEquals(new Decimal("1E+3"), Decimal.TEN.pow(3));
        assertEquals(new BigDecimal("10.00").ulp(), a.ulp().toBigDecimal());
        assertThrows(ArithmeticException.class, () -> a.divide(b));
        assertThrows(ArithmeticException.class, () -> a.divide(Decimal.ZERO));
        assertThrows(ArithmeticException.class, () -> new Decimal("1.1").intValueExact());
        assertEquals(new BigDecimal(0.1), new Decimal(0.1).toBigDecimal());
        assertEquals(BigDecimal.valueOf(0.1), Decimal.valueOf(0.1).toBigDecimal());
    }

    @Test
    void exactJsonUsesStringsAndReadsOldNumbers() throws Exception {
        String exact = "9007199254740993.010000000000000001";
        for (var mapper : List.of(JsonUtils.writer, JsonMapper.builder().build())) {
            var facts = new Facts(new Decimal(exact));
            assertEquals("{\"amount\":\"" + exact + "\"}", mapper.writeValueAsString(facts));
            assertEquals(facts, mapper.readValue(mapper.writeValueAsBytes(facts), Facts.class));
            assertEquals(facts, mapper.readValue("{\"amount\":" + exact + "}", Facts.class));
            assertEquals(new BigDecimal("900.00"), mapper.readValue("\"900.00\"", Decimal.class).toBigDecimal());
            assertNull(mapper.readValue("null", Decimal.class));
            assertEquals("900.00", mapper.writeValueAsString(new BigDecimal("900.00")));
            for (String invalid : List.of("\"\"", "\" \"", "\"NaN\"", "\"Infinity\"", "true", "{}", "[]")) {
                assertThrows(Exception.class, () -> mapper.readValue(invalid, Decimal.class), invalid);
            }
        }
    }

    @Test
    void numericLimitsApplyToStringInputToo() {
        var mapper = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(
                StreamReadConstraints.builder().maxNumberLength(5).build()).build()).build();
        assertThrows(Exception.class, () -> mapper.readValue("\"123456\"", Decimal.class));
        assertThrows(Exception.class, () -> mapper.readValue("123456", Decimal.class));
    }
}
