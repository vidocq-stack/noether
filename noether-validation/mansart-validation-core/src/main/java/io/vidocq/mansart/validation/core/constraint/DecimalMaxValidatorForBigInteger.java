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
import jakarta.validation.constraints.DecimalMax;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Checks that a {@code BigInteger} is lower than (or equal to, when {@code inclusive}) the annotation value; {@code null} is valid.
 *
 * <p>Implements the {@link DecimalMax} constraint for values of type {@link BigInteger} as defined by the Jakarta Validation 3.1 specification, chapter "Built-in Constraint definitions".
 */
public final class DecimalMaxValidatorForBigInteger implements ConstraintValidator<DecimalMax, BigInteger> {
    private BigDecimal limit;
    private boolean inclusive;

    @Override
    public void initialize(DecimalMax constraintAnnotation) {
        this.limit = Numbers.parseBound(constraintAnnotation.value());
        this.inclusive = constraintAnnotation.inclusive();
    }

    @Override
    public boolean isValid(BigInteger value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        int comparison = new BigDecimal(value).compareTo(limit);
        return inclusive ? comparison <= 0 : comparison < 0;
    }
}
