/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.validation.core.constraint;

import static io.vidocq.mansart.validation.core.constraint.TestSupport.annotation;
import static io.vidocq.mansart.validation.core.constraint.TestSupport.pattern;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;

/** Tests for Positive, PositiveOrZero, Negative and NegativeOrZero (Jakarta Validation 3.1, section 6.1). */
class SignConstraintsTest {

    static class F {
        @Positive Object positive;
        @PositiveOrZero Object positiveOrZero;
        @Negative Object negative;
        @NegativeOrZero Object negativeOrZero;
    }

    @Test
    void positive() {
        var a = annotation(F.class, "positive", Positive.class);
        assertThat(pattern(new PositiveValidatorForBigDecimal(), a, new BigDecimal("-0.1"), new BigDecimal("0.00"), new BigDecimal("0.1"), null)).isEqualTo("FFTT");
        assertThat(pattern(new PositiveValidatorForBigInteger(), a, BigInteger.valueOf(-1), BigInteger.ZERO, BigInteger.ONE, null)).isEqualTo("FFTT");
        assertThat(pattern(new PositiveValidatorForByte(), a, Byte.valueOf((byte) -1), Byte.valueOf((byte) 0), Byte.valueOf((byte) 1), null)).isEqualTo("FFTT");
        assertThat(pattern(new PositiveValidatorForShort(), a, Short.valueOf((short) -1), Short.valueOf((short) 0), Short.valueOf((short) 1), null)).isEqualTo("FFTT");
        assertThat(pattern(new PositiveValidatorForInteger(), a, Integer.valueOf(-1), Integer.valueOf(0), Integer.valueOf(1), null)).isEqualTo("FFTT");
        assertThat(pattern(new PositiveValidatorForLong(), a, Long.valueOf(-1L), Long.valueOf(0L), Long.valueOf(1L), null)).isEqualTo("FFTT");
        assertThat(pattern(new PositiveValidatorForFloat(), a, Float.valueOf(-1f), Float.valueOf(0f), Float.valueOf(1f), null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)).isEqualTo("FFTTFTF");
        assertThat(pattern(new PositiveValidatorForDouble(), a, Double.valueOf(-1d), Double.valueOf(0d), Double.valueOf(1d), null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)).isEqualTo("FFTTFTF");
    }

    @Test
    void positiveOrZero() {
        var a = annotation(F.class, "positiveOrZero", PositiveOrZero.class);
        assertThat(pattern(new PositiveOrZeroValidatorForBigDecimal(), a, new BigDecimal("-0.1"), new BigDecimal("0.00"), new BigDecimal("0.1"), null)).isEqualTo("FTTT");
        assertThat(pattern(new PositiveOrZeroValidatorForBigInteger(), a, BigInteger.valueOf(-1), BigInteger.ZERO, BigInteger.ONE, null)).isEqualTo("FTTT");
        assertThat(pattern(new PositiveOrZeroValidatorForByte(), a, Byte.valueOf((byte) -1), Byte.valueOf((byte) 0), Byte.valueOf((byte) 1), null)).isEqualTo("FTTT");
        assertThat(pattern(new PositiveOrZeroValidatorForShort(), a, Short.valueOf((short) -1), Short.valueOf((short) 0), Short.valueOf((short) 1), null)).isEqualTo("FTTT");
        assertThat(pattern(new PositiveOrZeroValidatorForInteger(), a, Integer.valueOf(-1), Integer.valueOf(0), Integer.valueOf(1), null)).isEqualTo("FTTT");
        assertThat(pattern(new PositiveOrZeroValidatorForLong(), a, Long.valueOf(-1L), Long.valueOf(0L), Long.valueOf(1L), null)).isEqualTo("FTTT");
        assertThat(pattern(new PositiveOrZeroValidatorForFloat(), a, Float.valueOf(-1f), Float.valueOf(0f), Float.valueOf(1f), null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)).isEqualTo("FTTTFTF");
        assertThat(pattern(new PositiveOrZeroValidatorForDouble(), a, Double.valueOf(-1d), Double.valueOf(0d), Double.valueOf(1d), null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)).isEqualTo("FTTTFTF");
    }

    @Test
    void negative() {
        var a = annotation(F.class, "negative", Negative.class);
        assertThat(pattern(new NegativeValidatorForBigDecimal(), a, new BigDecimal("-0.1"), new BigDecimal("0.00"), new BigDecimal("0.1"), null)).isEqualTo("TFFT");
        assertThat(pattern(new NegativeValidatorForBigInteger(), a, BigInteger.valueOf(-1), BigInteger.ZERO, BigInteger.ONE, null)).isEqualTo("TFFT");
        assertThat(pattern(new NegativeValidatorForByte(), a, Byte.valueOf((byte) -1), Byte.valueOf((byte) 0), Byte.valueOf((byte) 1), null)).isEqualTo("TFFT");
        assertThat(pattern(new NegativeValidatorForShort(), a, Short.valueOf((short) -1), Short.valueOf((short) 0), Short.valueOf((short) 1), null)).isEqualTo("TFFT");
        assertThat(pattern(new NegativeValidatorForInteger(), a, Integer.valueOf(-1), Integer.valueOf(0), Integer.valueOf(1), null)).isEqualTo("TFFT");
        assertThat(pattern(new NegativeValidatorForLong(), a, Long.valueOf(-1L), Long.valueOf(0L), Long.valueOf(1L), null)).isEqualTo("TFFT");
        assertThat(pattern(new NegativeValidatorForFloat(), a, Float.valueOf(-1f), Float.valueOf(0f), Float.valueOf(1f), null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)).isEqualTo("TFFTFFT");
        assertThat(pattern(new NegativeValidatorForDouble(), a, Double.valueOf(-1d), Double.valueOf(0d), Double.valueOf(1d), null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)).isEqualTo("TFFTFFT");
    }

    @Test
    void negativeOrZero() {
        var a = annotation(F.class, "negativeOrZero", NegativeOrZero.class);
        assertThat(pattern(new NegativeOrZeroValidatorForBigDecimal(), a, new BigDecimal("-0.1"), new BigDecimal("0.00"), new BigDecimal("0.1"), null)).isEqualTo("TTFT");
        assertThat(pattern(new NegativeOrZeroValidatorForBigInteger(), a, BigInteger.valueOf(-1), BigInteger.ZERO, BigInteger.ONE, null)).isEqualTo("TTFT");
        assertThat(pattern(new NegativeOrZeroValidatorForByte(), a, Byte.valueOf((byte) -1), Byte.valueOf((byte) 0), Byte.valueOf((byte) 1), null)).isEqualTo("TTFT");
        assertThat(pattern(new NegativeOrZeroValidatorForShort(), a, Short.valueOf((short) -1), Short.valueOf((short) 0), Short.valueOf((short) 1), null)).isEqualTo("TTFT");
        assertThat(pattern(new NegativeOrZeroValidatorForInteger(), a, Integer.valueOf(-1), Integer.valueOf(0), Integer.valueOf(1), null)).isEqualTo("TTFT");
        assertThat(pattern(new NegativeOrZeroValidatorForLong(), a, Long.valueOf(-1L), Long.valueOf(0L), Long.valueOf(1L), null)).isEqualTo("TTFT");
        assertThat(pattern(new NegativeOrZeroValidatorForFloat(), a, Float.valueOf(-1f), Float.valueOf(0f), Float.valueOf(1f), null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)).isEqualTo("TTFTFFT");
        assertThat(pattern(new NegativeOrZeroValidatorForDouble(), a, Double.valueOf(-1d), Double.valueOf(0d), Double.valueOf(1d), null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)).isEqualTo("TTFTFFT");
    }
}
