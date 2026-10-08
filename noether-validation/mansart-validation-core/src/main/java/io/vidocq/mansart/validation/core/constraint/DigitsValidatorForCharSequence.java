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

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

/**
 * Checks that a {@code CharSequence} has at most {@code integer} integral digits and {@code fraction} fractional digits; {@code null} is valid.
 *
 * <p>Implements the {@link Digits} constraint for values of type {@link CharSequence} as defined by the Jakarta Validation 3.1 specification, chapter "Built-in Constraint definitions". A {@code CharSequence} that is not a decimal number is invalid. Trailing zeros of a {@code BigDecimal} count as fractional digits (same as Hibernate Validator).
 */
public final class DigitsValidatorForCharSequence implements ConstraintValidator<Digits, CharSequence> {
    private int maxInteger;
    private int maxFraction;

    @Override
    public void initialize(Digits constraintAnnotation) {
        this.maxInteger = constraintAnnotation.integer();
        this.maxFraction = constraintAnnotation.fraction();
        if (maxInteger < 0 || maxFraction < 0) {
            throw new IllegalArgumentException("@Digits integer and fraction must not be negative");
        }
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        BigDecimal number = Numbers.parseOrNull(value);
        return number != null && Numbers.fits(number, maxInteger, maxFraction);
    }
}
