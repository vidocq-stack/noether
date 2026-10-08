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

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/**
 * {@code @Min} and {@code @Max} on a declared {@link Number} or {@link CharSequence}: not in the list of the
 * API Javadoc, but required by the official TCK (validator resolution: a {@code Number} and a {@code String}
 * field carry the same {@code @Min}). A character sequence is read as a decimal number.
 */
class MinMaxForNumberAndCharSequenceTest {

    static class F {
        @Min(5) Object min;
        @Max(5) Object max;
    }

    // values: below, boundary, above, null
    @Test
    void minOnNumber() {
        var a = annotation(F.class, "min", Min.class);
        assertThat(pattern(new MinValidatorForNumber(), a, 4, 5L, new BigDecimal("5.01"), null)).isEqualTo("FTTT");
        assertThat(pattern(new MinValidatorForNumber(), a, new BigInteger("4"), 4.5d, 5.0f, 6.5d)).isEqualTo("FFTT");
    }

    @Test
    void maxOnNumber() {
        var a = annotation(F.class, "max", Max.class);
        assertThat(pattern(new MaxValidatorForNumber(), a, 4, 5L, new BigDecimal("5.01"), null)).isEqualTo("TTFT");
        assertThat(pattern(new MaxValidatorForNumber(), a, new BigInteger("6"), 4.5d, 5.0f, 6.5d)).isEqualTo("FTTF");
    }

    @Test
    void infinitiesAndNaNOnNumber() {
        var min = annotation(F.class, "min", Min.class);
        var max = annotation(F.class, "max", Max.class);
        assertThat(pattern(new MinValidatorForNumber(), min, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN))
            .isEqualTo("TFF");
        assertThat(pattern(new MaxValidatorForNumber(), max, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN))
            .isEqualTo("FTF");
    }

    @Test
    void minOnCharSequence() {
        var a = annotation(F.class, "min", Min.class);
        assertThat(pattern(new MinValidatorForCharSequence(), a, "4", "5", "5.5", null)).isEqualTo("FTTT");
        assertThat(pattern(new MinValidatorForCharSequence(), a, new StringBuilder("9"), "abc", "")).isEqualTo("TFF");
    }

    @Test
    void maxOnCharSequence() {
        var a = annotation(F.class, "max", Max.class);
        assertThat(pattern(new MaxValidatorForCharSequence(), a, "4", "5", "5.5", null)).isEqualTo("TTFT");
        assertThat(pattern(new MaxValidatorForCharSequence(), a, new StringBuilder("9"), "abc", "")).isEqualTo("FFF");
    }

    @Test
    void theRegistryKnowsThem() {
        assertThat(BuiltInConstraints.validatorsFor(Min.class)).contains(MinValidatorForNumber.class, MinValidatorForCharSequence.class);
        assertThat(BuiltInConstraints.validatorsFor(Max.class)).contains(MaxValidatorForNumber.class, MaxValidatorForCharSequence.class);
    }
}
