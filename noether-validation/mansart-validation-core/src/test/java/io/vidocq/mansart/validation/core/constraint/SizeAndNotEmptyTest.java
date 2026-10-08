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

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Tests for Size and NotEmpty on every supported container kind (Jakarta Validation 3.1, section 6.1). */
class SizeAndNotEmptyTest {

    static class F {
        @Size(min = 2, max = 3) Object bounded;
        @Size(max = 1) Object maxOnly;
        @Size(min = 1) Object minOnly;
        @Size(min = -1) Object negativeMin;
        @Size(min = 3, max = 2) Object inverted;
        @NotEmpty Object notEmpty;
    }

    private static final Size BOUNDED = annotation(F.class, "bounded", Size.class);
    private static final NotEmpty NOT_EMPTY = annotation(F.class, "notEmpty", NotEmpty.class);

    // sizes: 1 (too small), 2, 3, 4 (too big), null
    @Test
    void sizeCharSequence() {
        assertThat(pattern(new SizeValidatorForCharSequence(), BOUNDED, "a", "ab", new StringBuilder("abc"), "abcd", null)).isEqualTo("FTTFT");
    }

    @Test
    void sizeCollection() {
        assertThat(pattern(new SizeValidatorForCollection(), BOUNDED, List.of(1), List.of(1, 2), Set.of(1, 2, 3), List.of(1, 2, 3, 4), null)).isEqualTo("FTTFT");
    }

    @Test
    void sizeMap() {
        Map<Integer, Integer> three = new HashMap<>(Map.of(1, 1, 2, 2, 3, 3));
        assertThat(pattern(new SizeValidatorForMap(), BOUNDED, Map.of(1, 1), Map.of(1, 1, 2, 2), three, null)).isEqualTo("FTTT");
    }

    @Test
    void sizeArrays() {
        assertThat(pattern(new SizeValidatorForObjectArray(), BOUNDED, new String[1], new String[2], new String[4], null)).isEqualTo("FTFT");
        assertThat(pattern(new SizeValidatorForBooleanArray(), BOUNDED, new boolean[1], new boolean[2], new boolean[4], null)).isEqualTo("FTFT");
        assertThat(pattern(new SizeValidatorForByteArray(), BOUNDED, new byte[1], new byte[3], new byte[4], null)).isEqualTo("FTFT");
        assertThat(pattern(new SizeValidatorForCharArray(), BOUNDED, new char[1], new char[2], new char[4], null)).isEqualTo("FTFT");
        assertThat(pattern(new SizeValidatorForDoubleArray(), BOUNDED, new double[1], new double[2], new double[4], null)).isEqualTo("FTFT");
        assertThat(pattern(new SizeValidatorForFloatArray(), BOUNDED, new float[1], new float[2], new float[4], null)).isEqualTo("FTFT");
        assertThat(pattern(new SizeValidatorForIntArray(), BOUNDED, new int[1], new int[2], new int[4], null)).isEqualTo("FTFT");
        assertThat(pattern(new SizeValidatorForLongArray(), BOUNDED, new long[1], new long[2], new long[4], null)).isEqualTo("FTFT");
        assertThat(pattern(new SizeValidatorForShortArray(), BOUNDED, new short[1], new short[2], new short[4], null)).isEqualTo("FTFT");
    }

    @Test
    void sizeDefaultsAndBoundaries() {
        assertThat(pattern(new SizeValidatorForCharSequence(), annotation(F.class, "maxOnly", Size.class), "", "a", "ab")).isEqualTo("TTF");
        assertThat(pattern(new SizeValidatorForCharSequence(), annotation(F.class, "minOnly", Size.class), "", "a", "a".repeat(1000))).isEqualTo("FTT");
    }

    @Test
    void sizeRejectsInvalidParameters() {
        var negative = annotation(F.class, "negativeMin", Size.class);
        var inverted = annotation(F.class, "inverted", Size.class);
        assertThatThrownBy(() -> new SizeValidatorForCharSequence().initialize(negative)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SizeValidatorForCollection().initialize(inverted)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void notEmptyContainers() {
        assertThat(pattern(new NotEmptyValidatorForCharSequence(), NOT_EMPTY, "", " ", "a", new StringBuilder(), null)).isEqualTo("FTTFF");
        assertThat(pattern(new NotEmptyValidatorForCollection(), NOT_EMPTY, List.of(), List.of(1), null)).isEqualTo("FTF");
        assertThat(pattern(new NotEmptyValidatorForMap(), NOT_EMPTY, Map.of(), Map.of(1, 1), null)).isEqualTo("FTF");
    }

    @Test
    void notEmptyArrays() {
        assertThat(pattern(new NotEmptyValidatorForObjectArray(), NOT_EMPTY, new String[0], new String[1], null)).isEqualTo("FTF");
        assertThat(pattern(new NotEmptyValidatorForBooleanArray(), NOT_EMPTY, new boolean[0], new boolean[1], null)).isEqualTo("FTF");
        assertThat(pattern(new NotEmptyValidatorForByteArray(), NOT_EMPTY, new byte[0], new byte[1], null)).isEqualTo("FTF");
        assertThat(pattern(new NotEmptyValidatorForCharArray(), NOT_EMPTY, new char[0], new char[1], null)).isEqualTo("FTF");
        assertThat(pattern(new NotEmptyValidatorForDoubleArray(), NOT_EMPTY, new double[0], new double[1], null)).isEqualTo("FTF");
        assertThat(pattern(new NotEmptyValidatorForFloatArray(), NOT_EMPTY, new float[0], new float[1], null)).isEqualTo("FTF");
        assertThat(pattern(new NotEmptyValidatorForIntArray(), NOT_EMPTY, new int[0], new int[1], null)).isEqualTo("FTF");
        assertThat(pattern(new NotEmptyValidatorForLongArray(), NOT_EMPTY, new long[0], new long[1], null)).isEqualTo("FTF");
        assertThat(pattern(new NotEmptyValidatorForShortArray(), NOT_EMPTY, new short[0], new short[1], null)).isEqualTo("FTF");
    }
}
