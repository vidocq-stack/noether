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
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;

/**
 * Checks that a {@code Number} is >= the annotation value; {@code null} is valid.
 *
 * <p>Not among the types the API Javadoc lists for {@link Min}, but required by the Jakarta Validation 3.1 TCK
 * (validator resolution). Floating point values are compared exactly; NaN is invalid.
 */
public final class MinValidatorForNumber implements ConstraintValidator<Min, Number> {
    private long limit;

    @Override
    public void initialize(Min constraintAnnotation) {
        this.limit = constraintAnnotation.value();
    }

    @Override
    public boolean isValid(Number value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        Integer sign = Numbers.compareToLimit(value, limit);
        return sign != null && sign >= 0;
    }
}
