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

import java.math.BigDecimal;

/** Small parsing and checking helpers shared by the numeric validators of this package. */
final class Numbers {

    private Numbers() {
    }

    /**
     * Parses the {@code value} of a {@code @DecimalMin}/{@code @DecimalMax} annotation.
     *
     * @throws IllegalArgumentException if the value is not a valid decimal number
     */
    static BigDecimal parseBound(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(value + " does not represent a valid BigDecimal format", e);
        }
    }

    /** Parses a character sequence as a decimal number; returns {@code null} when it is not one. */
    static BigDecimal parseOrNull(CharSequence value) {
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Returns {@code true} when the number has at most {@code maxInteger} integral and {@code maxFraction} fractional digits. */
    static boolean fits(BigDecimal number, int maxInteger, int maxFraction) {
        int integerDigits = number.precision() - number.scale();
        int fractionDigits = Math.max(number.scale(), 0);
        return integerDigits <= maxInteger && fractionDigits <= maxFraction;
    }

    /** Validates the {@code min}/{@code max} parameters of {@code @Size}. */
    static void checkSizeBounds(int min, int max) {
        if (min < 0) {
            throw new IllegalArgumentException("The min parameter cannot be negative: " + min);
        }
        if (max < 0) {
            throw new IllegalArgumentException("The max parameter cannot be negative: " + max);
        }
        if (max < min) {
            throw new IllegalArgumentException("The length cannot be negative: max < min (" + max + " < " + min + ")");
        }
    }
}
