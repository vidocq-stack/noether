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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;

/** Tests for Min, Max, DecimalMin, DecimalMax and Digits (Jakarta Validation 3.1, section 6.1). */
class NumericRangeTest {

    static class F {
        @Min(5) Object min;
        @Max(5) Object max;
        @DecimalMin("1.5") Object dminIncl;
        @DecimalMin(value = "1.5", inclusive = false) Object dminExcl;
        @DecimalMax("1.5") Object dmaxIncl;
        @DecimalMax(value = "1.5", inclusive = false) Object dmaxExcl;
        @DecimalMin("abc") Object dminBad;
        @Digits(integer = 3, fraction = 2) Object digits;
        @Digits(integer = 2, fraction = 0) Object digits0;
    }

    // values: below, boundary, above, null
    @Test
    void min() {
        var a = annotation(F.class, "min", Min.class);
        assertThat(pattern(new MinValidatorForBigDecimal(), a, new BigDecimal("4.99"), new BigDecimal("5.00"), new BigDecimal("5.01"), null)).isEqualTo("FTTT");
        assertThat(pattern(new MinValidatorForBigInteger(), a, BigInteger.valueOf(4), BigInteger.valueOf(5), BigInteger.valueOf(6), null)).isEqualTo("FTTT");
        assertThat(pattern(new MinValidatorForByte(), a, (byte) 4, (byte) 5, (byte) 6, null)).isEqualTo("FTTT");
        assertThat(pattern(new MinValidatorForShort(), a, (short) 4, (short) 5, (short) 6, null)).isEqualTo("FTTT");
        assertThat(pattern(new MinValidatorForInteger(), a, 4, 5, 6, null)).isEqualTo("FTTT");
        assertThat(pattern(new MinValidatorForLong(), a, 4L, 5L, Long.MAX_VALUE, Long.MIN_VALUE, null)).isEqualTo("FTTFT");
    }

    @Test
    void max() {
        var a = annotation(F.class, "max", Max.class);
        assertThat(pattern(new MaxValidatorForBigDecimal(), a, new BigDecimal("4.99"), new BigDecimal("5.00"), new BigDecimal("5.01"), null)).isEqualTo("TTFT");
        assertThat(pattern(new MaxValidatorForBigInteger(), a, BigInteger.valueOf(4), BigInteger.valueOf(5), BigInteger.valueOf(6), null)).isEqualTo("TTFT");
        assertThat(pattern(new MaxValidatorForByte(), a, (byte) 4, (byte) 5, (byte) 6, null)).isEqualTo("TTFT");
        assertThat(pattern(new MaxValidatorForShort(), a, (short) 4, (short) 5, (short) 6, null)).isEqualTo("TTFT");
        assertThat(pattern(new MaxValidatorForInteger(), a, 4, 5, 6, null)).isEqualTo("TTFT");
        assertThat(pattern(new MaxValidatorForLong(), a, 4L, 5L, Long.MAX_VALUE, Long.MIN_VALUE, null)).isEqualTo("TTFTT");
    }

    @Test
    void decimalMin() {
        var in = annotation(F.class, "dminIncl", DecimalMin.class);
        var ex = annotation(F.class, "dminExcl", DecimalMin.class);
        assertThat(pattern(new DecimalMinValidatorForBigDecimal(), in, new BigDecimal("1.49"), new BigDecimal("1.50"), new BigDecimal("1.51"), null)).isEqualTo("FTTT");
        assertThat(pattern(new DecimalMinValidatorForBigDecimal(), ex, new BigDecimal("1.49"), new BigDecimal("1.50"), new BigDecimal("1.51"), null)).isEqualTo("FFTT");
        assertThat(pattern(new DecimalMinValidatorForBigInteger(), in, BigInteger.ONE, BigInteger.TWO, null)).isEqualTo("FTT");
        assertThat(pattern(new DecimalMinValidatorForByte(), in, (byte) 1, (byte) 2, null)).isEqualTo("FTT");
        assertThat(pattern(new DecimalMinValidatorForShort(), ex, (short) 1, (short) 2, null)).isEqualTo("FTT");
        assertThat(pattern(new DecimalMinValidatorForInteger(), in, 1, 2, null)).isEqualTo("FTT");
        assertThat(pattern(new DecimalMinValidatorForLong(), ex, 1L, 2L, null)).isEqualTo("FTT");
        assertThat(pattern(new DecimalMinValidatorForCharSequence(), in, "1.4", "1.5", "1.50", "2", new StringBuilder("0"), "not a number", null)).isEqualTo("FTTTFFT");
        assertThat(pattern(new DecimalMinValidatorForCharSequence(), ex, "1.5", "1.51", null)).isEqualTo("FTT");
    }

    @Test
    void decimalMax() {
        var in = annotation(F.class, "dmaxIncl", DecimalMax.class);
        var ex = annotation(F.class, "dmaxExcl", DecimalMax.class);
        assertThat(pattern(new DecimalMaxValidatorForBigDecimal(), in, new BigDecimal("1.49"), new BigDecimal("1.50"), new BigDecimal("1.51"), null)).isEqualTo("TTFT");
        assertThat(pattern(new DecimalMaxValidatorForBigDecimal(), ex, new BigDecimal("1.49"), new BigDecimal("1.50"), new BigDecimal("1.51"), null)).isEqualTo("TFFT");
        assertThat(pattern(new DecimalMaxValidatorForBigInteger(), in, BigInteger.ONE, BigInteger.TWO, null)).isEqualTo("TFT");
        assertThat(pattern(new DecimalMaxValidatorForByte(), in, (byte) 1, (byte) 2, null)).isEqualTo("TFT");
        assertThat(pattern(new DecimalMaxValidatorForShort(), ex, (short) 1, (short) 2, null)).isEqualTo("TFT");
        assertThat(pattern(new DecimalMaxValidatorForInteger(), in, 1, 2, null)).isEqualTo("TFT");
        assertThat(pattern(new DecimalMaxValidatorForLong(), ex, 1L, 2L, null)).isEqualTo("TFT");
        assertThat(pattern(new DecimalMaxValidatorForCharSequence(), in, "1.4", "1.5", "1.50", "2", "not a number", null)).isEqualTo("TTTFFT");
        assertThat(pattern(new DecimalMaxValidatorForCharSequence(), ex, "1.5", "1.49", null)).isEqualTo("FTT");
    }

    @Test
    void decimalBoundsRejectMalformedAnnotationValue() {
        var bad = annotation(F.class, "dminBad", DecimalMin.class);
        assertThatThrownBy(() -> new DecimalMinValidatorForInteger().initialize(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecimalMinValidatorForCharSequence().initialize(bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void digits() {
        var a = annotation(F.class, "digits", Digits.class);
        // integer=3, fraction=2
        assertThat(pattern(new DigitsValidatorForBigDecimal(), a, new BigDecimal("123.45"), new BigDecimal("1234.5"), new BigDecimal("1.234"), new BigDecimal("-12.3"), new BigDecimal("0"), new BigDecimal("0.05"), new BigDecimal("1E+2"), new BigDecimal("1E+3"), null)).isEqualTo("TFFTTTTFT");
        assertThat(pattern(new DigitsValidatorForBigInteger(), a, BigInteger.valueOf(999), BigInteger.valueOf(-999), BigInteger.valueOf(1000), null)).isEqualTo("TTFT");
        assertThat(pattern(new DigitsValidatorForByte(), a, (byte) 127, (byte) -128, null)).isEqualTo("TTT");
        assertThat(pattern(new DigitsValidatorForShort(), a, (short) 999, (short) 1000, null)).isEqualTo("TFT");
        assertThat(pattern(new DigitsValidatorForInteger(), a, 999, 1000, null)).isEqualTo("TFT");
        assertThat(pattern(new DigitsValidatorForLong(), a, 999L, -1000L, null)).isEqualTo("TFT");
        assertThat(pattern(new DigitsValidatorForCharSequence(), a, "123.45", "1234.5", "1.234", "-12.3", "abc", "", new StringBuilder("7"), null)).isEqualTo("TFFTFFTT");
        var zero = annotation(F.class, "digits0", Digits.class);
        assertThat(pattern(new DigitsValidatorForBigDecimal(), zero, new BigDecimal("99"), new BigDecimal("99.1"), new BigDecimal("100"))).isEqualTo("TFF");
    }
}
