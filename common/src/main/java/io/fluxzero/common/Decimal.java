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

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.fluxzero.common.serialization.DecimalDeserializer;
import io.fluxzero.common.serialization.DecimalSerializer;
import lombok.experimental.Delegate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable arbitrary-precision decimal with numerical equality, independent of trailing zeros.
 * <p>Arithmetic, scale, conversions, rounding and exceptions follow {@link BigDecimal}. Decimal-valued results
 * remain Decimal, including array elements. Both Decimal and BigDecimal operands are accepted. Scale is preserved,
 * but does not participate in equality or hashing. Equality with other Number types is deliberately false.
 * <p>JSON uses an exact decimal string; readers also accept numeric tokens. Document indexing uses numeric entries.
 * Use string constructors or {@link #valueOf(long)} for exact input; double constructors retain BigDecimal's
 * exact binary-floating-point conversion semantics. {@link #valueOf(double)} follows BigDecimal.valueOf(double).
 * Standard numeric constraints are supported by the Fluxzero validator; third-party providers may require adapters.
 */
@JsonSerialize(using = DecimalSerializer.class)
@JsonDeserialize(using = DecimalDeserializer.class)
public final class Decimal extends Number implements Comparable<Decimal> {
    private static final long serialVersionUID = 1L;

    @Delegate(types = Number.class)
    private final BigDecimal value;

    /** Wraps an immutable BigDecimal without rounding or changing its scale. */
    public Decimal(BigDecimal value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    /** Returns the underlying immutable value, preserving its scale. */
    public BigDecimal toBigDecimal() {
        return value;
    }

    /** The value BigDecimal.ZERO, as a Decimal. */
    public static final Decimal ZERO = new Decimal(BigDecimal.ZERO);

    /** The value BigDecimal.ONE, as a Decimal. */
    public static final Decimal ONE = new Decimal(BigDecimal.ONE);

    /** The value BigDecimal.TWO, as a Decimal. */
    public static final Decimal TWO = new Decimal(BigDecimal.TWO);

    /** The value BigDecimal.TEN, as a Decimal. */
    public static final Decimal TEN = new Decimal(BigDecimal.TEN);

    /** @deprecated Use {@link RoundingMode#UP}. */
    @Deprecated
    public static final int ROUND_UP = BigDecimal.ROUND_UP;

    /** @deprecated Use {@link RoundingMode#DOWN}. */
    @Deprecated
    public static final int ROUND_DOWN = BigDecimal.ROUND_DOWN;

    /** @deprecated Use {@link RoundingMode#CEILING}. */
    @Deprecated
    public static final int ROUND_CEILING = BigDecimal.ROUND_CEILING;

    /** @deprecated Use {@link RoundingMode#FLOOR}. */
    @Deprecated
    public static final int ROUND_FLOOR = BigDecimal.ROUND_FLOOR;

    /** @deprecated Use {@link RoundingMode#HALF_UP}. */
    @Deprecated
    public static final int ROUND_HALF_UP = BigDecimal.ROUND_HALF_UP;

    /** @deprecated Use {@link RoundingMode#HALF_DOWN}. */
    @Deprecated
    public static final int ROUND_HALF_DOWN = BigDecimal.ROUND_HALF_DOWN;

    /** @deprecated Use {@link RoundingMode#HALF_EVEN}. */
    @Deprecated
    public static final int ROUND_HALF_EVEN = BigDecimal.ROUND_HALF_EVEN;

    /** @deprecated Use {@link RoundingMode#UNNECESSARY}. */
    @Deprecated
    public static final int ROUND_UNNECESSARY = BigDecimal.ROUND_UNNECESSARY;

    /**
     * Creates a value with {@link BigDecimal#BigDecimal(char[], int, int)}.
     */
    public Decimal(char[] value, int offset, int length) {
        this(new BigDecimal(value, offset, length));
    }

    /**
     * Creates a value with {@link BigDecimal#BigDecimal(char[], int, int, MathContext)}.
     */
    public Decimal(char[] value, int offset, int length, MathContext context) {
        this(new BigDecimal(value, offset, length, context));
    }

    /**
     * Creates a value with {@link BigDecimal#BigDecimal(char[])}.
     */
    public Decimal(char[] value) {
        this(new BigDecimal(value));
    }

    /**
     * Creates a value with {@link BigDecimal#BigDecimal(char[], MathContext)}.
     */
    public Decimal(char[] value, MathContext context) {
        this(new BigDecimal(value, context));
    }

    /**
     * Creates a value with {@link BigDecimal#BigDecimal(String)}.
     */
    public Decimal(String value) {
        this(new BigDecimal(value));
    }

    /**
     * Creates a value with {@link BigDecimal#BigDecimal(String, MathContext)}.
     */
    public Decimal(String value, MathContext context) {
        this(new BigDecimal(value, context));
    }

    /**
     * Creates a value with {@link BigDecimal#BigDecimal(double)}.
     */
    public Decimal(double value) {
        this(new BigDecimal(value));
    }

    /**
     * Creates a value with {@link BigDecimal#BigDecimal(double, MathContext)}.
     */
    public Decimal(double value, MathContext context) {
        this(new BigDecimal(value, context));
    }

    /**
     * Creates a value with {@link BigDecimal#BigDecimal(BigInteger)}.
     */
    public Decimal(BigInteger value) {
        this(new BigDecimal(value));
    }

    /**
     * Creates a value with {@link BigDecimal#BigDecimal(BigInteger, MathContext)}.
     */
    public Decimal(BigInteger value, MathContext context) {
        this(new BigDecimal(value, context));
    }

    /**
     * Creates a value with {@link BigDecimal#BigDecimal(BigInteger, int)}.
     */
    public Decimal(BigInteger value, int scale) {
        this(new BigDecimal(value, scale));
    }

    /**
     * Creates a value with {@link BigDecimal#BigDecimal(BigInteger, int, MathContext)}.
     */
    public Decimal(BigInteger value, int scale, MathContext context) {
        this(new BigDecimal(value, scale, context));
    }

    /**
     * Creates a value with {@link BigDecimal#BigDecimal(int)}.
     */
    public Decimal(int value) {
        this(new BigDecimal(value));
    }

    /**
     * Creates a value with {@link BigDecimal#BigDecimal(int, MathContext)}.
     */
    public Decimal(int value, MathContext context) {
        this(new BigDecimal(value, context));
    }

    /**
     * Creates a value with {@link BigDecimal#BigDecimal(long)}.
     */
    public Decimal(long value) {
        this(new BigDecimal(value));
    }

    /**
     * Creates a value with {@link BigDecimal#BigDecimal(long, MathContext)}.
     */
    public Decimal(long value, MathContext context) {
        this(new BigDecimal(value, context));
    }

    /**
     * Delegates to {@link BigDecimal#valueOf(long, int)}. Decimal results retain this value type.
     */
    public static Decimal valueOf(long value, int scale) {
        return new Decimal(BigDecimal.valueOf(value, scale));
    }

    /**
     * Delegates to {@link BigDecimal#valueOf(long)}. Decimal results retain this value type.
     */
    public static Decimal valueOf(long value) {
        return new Decimal(BigDecimal.valueOf(value));
    }

    /**
     * Delegates to {@link BigDecimal#valueOf(double)}. Decimal results retain this value type.
     */
    public static Decimal valueOf(double value) {
        return new Decimal(BigDecimal.valueOf(value));
    }

    /**
     * Delegates to {@link BigDecimal#add(BigDecimal)}. Decimal results retain this value type.
     */
    public Decimal add(BigDecimal other) {
        return new Decimal(value.add(other));
    }

    /**
     * Delegates to {@link BigDecimal#add(BigDecimal)}. Decimal results retain this value type.
     */
    public Decimal add(Decimal other) {
        return new Decimal(value.add(other.value));
    }

    /**
     * Delegates to {@link BigDecimal#add(BigDecimal, MathContext)}. Decimal results retain this value type.
     */
    public Decimal add(BigDecimal other, MathContext context) {
        return new Decimal(value.add(other, context));
    }

    /**
     * Delegates to {@link BigDecimal#add(BigDecimal, MathContext)}. Decimal results retain this value type.
     */
    public Decimal add(Decimal other, MathContext context) {
        return new Decimal(value.add(other.value, context));
    }

    /**
     * Delegates to {@link BigDecimal#subtract(BigDecimal)}. Decimal results retain this value type.
     */
    public Decimal subtract(BigDecimal other) {
        return new Decimal(value.subtract(other));
    }

    /**
     * Delegates to {@link BigDecimal#subtract(BigDecimal)}. Decimal results retain this value type.
     */
    public Decimal subtract(Decimal other) {
        return new Decimal(value.subtract(other.value));
    }

    /**
     * Delegates to {@link BigDecimal#subtract(BigDecimal, MathContext)}. Decimal results retain this value type.
     */
    public Decimal subtract(BigDecimal other, MathContext context) {
        return new Decimal(value.subtract(other, context));
    }

    /**
     * Delegates to {@link BigDecimal#subtract(BigDecimal, MathContext)}. Decimal results retain this value type.
     */
    public Decimal subtract(Decimal other, MathContext context) {
        return new Decimal(value.subtract(other.value, context));
    }

    /**
     * Delegates to {@link BigDecimal#multiply(BigDecimal)}. Decimal results retain this value type.
     */
    public Decimal multiply(BigDecimal other) {
        return new Decimal(value.multiply(other));
    }

    /**
     * Delegates to {@link BigDecimal#multiply(BigDecimal)}. Decimal results retain this value type.
     */
    public Decimal multiply(Decimal other) {
        return new Decimal(value.multiply(other.value));
    }

    /**
     * Delegates to {@link BigDecimal#multiply(BigDecimal, MathContext)}. Decimal results retain this value type.
     */
    public Decimal multiply(BigDecimal other, MathContext context) {
        return new Decimal(value.multiply(other, context));
    }

    /**
     * Delegates to {@link BigDecimal#multiply(BigDecimal, MathContext)}. Decimal results retain this value type.
     */
    public Decimal multiply(Decimal other, MathContext context) {
        return new Decimal(value.multiply(other.value, context));
    }

    /**
     * Delegates to {@link BigDecimal#divide(BigDecimal, int, int)}. Decimal results retain this value type.
     * @deprecated Use the corresponding RoundingMode overload.
     */
    @Deprecated
    public Decimal divide(BigDecimal other, int scale, int roundingMode) {
        return new Decimal(value.divide(other, scale, roundingMode));
    }

    /**
     * Delegates to {@link BigDecimal#divide(BigDecimal, int, int)}. Decimal results retain this value type.
     * @deprecated Use the corresponding RoundingMode overload.
     */
    @Deprecated
    public Decimal divide(Decimal other, int scale, int roundingMode) {
        return new Decimal(value.divide(other.value, scale, roundingMode));
    }

    /**
     * Delegates to {@link BigDecimal#divide(BigDecimal, int, RoundingMode)}. Decimal results retain this value type.
     */
    public Decimal divide(BigDecimal other, int scale, RoundingMode roundingMode) {
        return new Decimal(value.divide(other, scale, roundingMode));
    }

    /**
     * Delegates to {@link BigDecimal#divide(BigDecimal, int, RoundingMode)}. Decimal results retain this value type.
     */
    public Decimal divide(Decimal other, int scale, RoundingMode roundingMode) {
        return new Decimal(value.divide(other.value, scale, roundingMode));
    }

    /**
     * Delegates to {@link BigDecimal#divide(BigDecimal, int)}. Decimal results retain this value type.
     * @deprecated Use the corresponding RoundingMode overload.
     */
    @Deprecated
    public Decimal divide(BigDecimal other, int roundingMode) {
        return new Decimal(value.divide(other, roundingMode));
    }

    /**
     * Delegates to {@link BigDecimal#divide(BigDecimal, int)}. Decimal results retain this value type.
     * @deprecated Use the corresponding RoundingMode overload.
     */
    @Deprecated
    public Decimal divide(Decimal other, int roundingMode) {
        return new Decimal(value.divide(other.value, roundingMode));
    }

    /**
     * Delegates to {@link BigDecimal#divide(BigDecimal, RoundingMode)}. Decimal results retain this value type.
     */
    public Decimal divide(BigDecimal other, RoundingMode roundingMode) {
        return new Decimal(value.divide(other, roundingMode));
    }

    /**
     * Delegates to {@link BigDecimal#divide(BigDecimal, RoundingMode)}. Decimal results retain this value type.
     */
    public Decimal divide(Decimal other, RoundingMode roundingMode) {
        return new Decimal(value.divide(other.value, roundingMode));
    }

    /**
     * Delegates to {@link BigDecimal#divide(BigDecimal)}. Decimal results retain this value type.
     */
    public Decimal divide(BigDecimal other) {
        return new Decimal(value.divide(other));
    }

    /**
     * Delegates to {@link BigDecimal#divide(BigDecimal)}. Decimal results retain this value type.
     */
    public Decimal divide(Decimal other) {
        return new Decimal(value.divide(other.value));
    }

    /**
     * Delegates to {@link BigDecimal#divide(BigDecimal, MathContext)}. Decimal results retain this value type.
     */
    public Decimal divide(BigDecimal other, MathContext context) {
        return new Decimal(value.divide(other, context));
    }

    /**
     * Delegates to {@link BigDecimal#divide(BigDecimal, MathContext)}. Decimal results retain this value type.
     */
    public Decimal divide(Decimal other, MathContext context) {
        return new Decimal(value.divide(other.value, context));
    }

    /**
     * Delegates to {@link BigDecimal#divideToIntegralValue(BigDecimal)}. Decimal results retain this value type.
     */
    public Decimal divideToIntegralValue(BigDecimal other) {
        return new Decimal(value.divideToIntegralValue(other));
    }

    /**
     * Delegates to {@link BigDecimal#divideToIntegralValue(BigDecimal)}. Decimal results retain this value type.
     */
    public Decimal divideToIntegralValue(Decimal other) {
        return new Decimal(value.divideToIntegralValue(other.value));
    }

    /**
     * Delegates to {@link BigDecimal#divideToIntegralValue(BigDecimal, MathContext)}. Decimal results retain this value type.
     */
    public Decimal divideToIntegralValue(BigDecimal other, MathContext context) {
        return new Decimal(value.divideToIntegralValue(other, context));
    }

    /**
     * Delegates to {@link BigDecimal#divideToIntegralValue(BigDecimal, MathContext)}. Decimal results retain this value type.
     */
    public Decimal divideToIntegralValue(Decimal other, MathContext context) {
        return new Decimal(value.divideToIntegralValue(other.value, context));
    }

    /**
     * Delegates to {@link BigDecimal#remainder(BigDecimal)}. Decimal results retain this value type.
     */
    public Decimal remainder(BigDecimal other) {
        return new Decimal(value.remainder(other));
    }

    /**
     * Delegates to {@link BigDecimal#remainder(BigDecimal)}. Decimal results retain this value type.
     */
    public Decimal remainder(Decimal other) {
        return new Decimal(value.remainder(other.value));
    }

    /**
     * Delegates to {@link BigDecimal#remainder(BigDecimal, MathContext)}. Decimal results retain this value type.
     */
    public Decimal remainder(BigDecimal other, MathContext context) {
        return new Decimal(value.remainder(other, context));
    }

    /**
     * Delegates to {@link BigDecimal#remainder(BigDecimal, MathContext)}. Decimal results retain this value type.
     */
    public Decimal remainder(Decimal other, MathContext context) {
        return new Decimal(value.remainder(other.value, context));
    }

    /**
     * Delegates to {@link BigDecimal#divideAndRemainder(BigDecimal)}. Decimal results retain this value type.
     */
    public Decimal[] divideAndRemainder(BigDecimal other) {
        return Arrays.stream(value.divideAndRemainder(other)).map(Decimal::new).toArray(Decimal[]::new);
    }

    /**
     * Delegates to {@link BigDecimal#divideAndRemainder(BigDecimal)}. Decimal results retain this value type.
     */
    public Decimal[] divideAndRemainder(Decimal other) {
        return Arrays.stream(value.divideAndRemainder(other.value)).map(Decimal::new).toArray(Decimal[]::new);
    }

    /**
     * Delegates to {@link BigDecimal#divideAndRemainder(BigDecimal, MathContext)}. Decimal results retain this value type.
     */
    public Decimal[] divideAndRemainder(BigDecimal other, MathContext context) {
        return Arrays.stream(value.divideAndRemainder(other, context)).map(Decimal::new).toArray(Decimal[]::new);
    }

    /**
     * Delegates to {@link BigDecimal#divideAndRemainder(BigDecimal, MathContext)}. Decimal results retain this value type.
     */
    public Decimal[] divideAndRemainder(Decimal other, MathContext context) {
        return Arrays.stream(value.divideAndRemainder(other.value, context)).map(Decimal::new).toArray(Decimal[]::new);
    }

    /**
     * Delegates to {@link BigDecimal#sqrt(MathContext)}. Decimal results retain this value type.
     */
    public Decimal sqrt(MathContext context) {
        return new Decimal(value.sqrt(context));
    }

    /**
     * Delegates to {@link BigDecimal#pow(int)}. Decimal results retain this value type.
     */
    public Decimal pow(int exponent) {
        return new Decimal(value.pow(exponent));
    }

    /**
     * Delegates to {@link BigDecimal#pow(int, MathContext)}. Decimal results retain this value type.
     */
    public Decimal pow(int exponent, MathContext context) {
        return new Decimal(value.pow(exponent, context));
    }

    /**
     * Delegates to {@link BigDecimal#abs()}. Decimal results retain this value type.
     */
    public Decimal abs() {
        return new Decimal(value.abs());
    }

    /**
     * Delegates to {@link BigDecimal#abs(MathContext)}. Decimal results retain this value type.
     */
    public Decimal abs(MathContext context) {
        return new Decimal(value.abs(context));
    }

    /**
     * Delegates to {@link BigDecimal#negate()}. Decimal results retain this value type.
     */
    public Decimal negate() {
        return new Decimal(value.negate());
    }

    /**
     * Delegates to {@link BigDecimal#negate(MathContext)}. Decimal results retain this value type.
     */
    public Decimal negate(MathContext context) {
        return new Decimal(value.negate(context));
    }

    /**
     * Delegates to {@link BigDecimal#plus()}. Decimal results retain this value type.
     */
    public Decimal plus() {
        return new Decimal(value.plus());
    }

    /**
     * Delegates to {@link BigDecimal#plus(MathContext)}. Decimal results retain this value type.
     */
    public Decimal plus(MathContext context) {
        return new Decimal(value.plus(context));
    }

    /**
     * Delegates to {@link BigDecimal#signum()}.
     */
    public int signum() {
        return value.signum();
    }

    /**
     * Delegates to {@link BigDecimal#scale()}.
     */
    public int scale() {
        return value.scale();
    }

    /**
     * Delegates to {@link BigDecimal#precision()}.
     */
    public int precision() {
        return value.precision();
    }

    /**
     * Delegates to {@link BigDecimal#unscaledValue()}.
     */
    public BigInteger unscaledValue() {
        return value.unscaledValue();
    }

    /**
     * Delegates to {@link BigDecimal#round(MathContext)}. Decimal results retain this value type.
     */
    public Decimal round(MathContext context) {
        return new Decimal(value.round(context));
    }

    /**
     * Delegates to {@link BigDecimal#setScale(int, RoundingMode)}. Decimal results retain this value type.
     */
    public Decimal setScale(int scale, RoundingMode roundingMode) {
        return new Decimal(value.setScale(scale, roundingMode));
    }

    /**
     * Delegates to {@link BigDecimal#setScale(int, int)}. Decimal results retain this value type.
     * @deprecated Use the corresponding RoundingMode overload.
     */
    @Deprecated
    public Decimal setScale(int scale, int roundingMode) {
        return new Decimal(value.setScale(scale, roundingMode));
    }

    /**
     * Delegates to {@link BigDecimal#setScale(int)}. Decimal results retain this value type.
     */
    public Decimal setScale(int scale) {
        return new Decimal(value.setScale(scale));
    }

    /**
     * Delegates to {@link BigDecimal#movePointLeft(int)}. Decimal results retain this value type.
     */
    public Decimal movePointLeft(int distance) {
        return new Decimal(value.movePointLeft(distance));
    }

    /**
     * Delegates to {@link BigDecimal#movePointRight(int)}. Decimal results retain this value type.
     */
    public Decimal movePointRight(int distance) {
        return new Decimal(value.movePointRight(distance));
    }

    /**
     * Delegates to {@link BigDecimal#scaleByPowerOfTen(int)}. Decimal results retain this value type.
     */
    public Decimal scaleByPowerOfTen(int exponent) {
        return new Decimal(value.scaleByPowerOfTen(exponent));
    }

    /**
     * Delegates to {@link BigDecimal#stripTrailingZeros()}. Decimal results retain this value type.
     */
    public Decimal stripTrailingZeros() {
        return new Decimal(value.stripTrailingZeros());
    }

    /**
     * Delegates to {@link BigDecimal#compareTo(BigDecimal)}.
     */
    public int compareTo(BigDecimal other) {
        return value.compareTo(other);
    }

    /**
     * Delegates to {@link BigDecimal#compareTo(BigDecimal)}.
     */
    public int compareTo(Decimal other) {
        return value.compareTo(other.value);
    }

    /**
     * Delegates to {@link BigDecimal#min(BigDecimal)}. Decimal results retain this value type.
     */
    public Decimal min(BigDecimal other) {
        return new Decimal(value.min(other));
    }

    /**
     * Delegates to {@link BigDecimal#min(BigDecimal)}. Decimal results retain this value type.
     */
    public Decimal min(Decimal other) {
        return new Decimal(value.min(other.value));
    }

    /**
     * Delegates to {@link BigDecimal#max(BigDecimal)}. Decimal results retain this value type.
     */
    public Decimal max(BigDecimal other) {
        return new Decimal(value.max(other));
    }

    /**
     * Delegates to {@link BigDecimal#max(BigDecimal)}. Decimal results retain this value type.
     */
    public Decimal max(Decimal other) {
        return new Decimal(value.max(other.value));
    }

    /**
     * Delegates to {@link BigDecimal#toEngineeringString()}.
     */
    public String toEngineeringString() {
        return value.toEngineeringString();
    }

    /**
     * Delegates to {@link BigDecimal#toPlainString()}.
     */
    public String toPlainString() {
        return value.toPlainString();
    }

    /**
     * Delegates to {@link BigDecimal#toBigInteger()}.
     */
    public BigInteger toBigInteger() {
        return value.toBigInteger();
    }

    /**
     * Delegates to {@link BigDecimal#toBigIntegerExact()}.
     */
    public BigInteger toBigIntegerExact() {
        return value.toBigIntegerExact();
    }

    /**
     * Delegates to {@link BigDecimal#longValueExact()}.
     */
    public long longValueExact() {
        return value.longValueExact();
    }

    /**
     * Delegates to {@link BigDecimal#intValueExact()}.
     */
    public int intValueExact() {
        return value.intValueExact();
    }

    /**
     * Delegates to {@link BigDecimal#shortValueExact()}.
     */
    public short shortValueExact() {
        return value.shortValueExact();
    }

    /**
     * Delegates to {@link BigDecimal#byteValueExact()}.
     */
    public byte byteValueExact() {
        return value.byteValueExact();
    }

    /**
     * Delegates to {@link BigDecimal#ulp()}. Decimal results retain this value type.
     */
    public Decimal ulp() {
        return new Decimal(value.ulp());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Decimal decimal && value.compareTo(decimal.value) == 0;
    }

    @Override
    public int hashCode() {
        try {
            return value.stripTrailingZeros().hashCode();
        } catch (ArithmeticException overflow) {
            // A valid coefficient may still have trailing zeros at the minimum scale. Canonicalize at that
            // representable boundary rather than requiring a scale smaller than Integer.MIN_VALUE.
            return value.setScale(Integer.MIN_VALUE).hashCode();
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
