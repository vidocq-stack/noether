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
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Checks that the size of a {@code Map<?, ?>} lies within {@code [min, max]}; {@code null} is valid.
 *
 * <p>Implements the {@link Size} constraint for values of type {@link Map} as defined by the Jakarta Validation 3.1 specification, chapter "Built-in Constraint definitions".
 */
public final class SizeValidatorForMap implements ConstraintValidator<Size, Map<?, ?>> {
    private int min;
    private int max;

    @Override
    public void initialize(Size constraintAnnotation) {
        this.min = constraintAnnotation.min();
        this.max = constraintAnnotation.max();
        Numbers.checkSizeBounds(min, max);
    }

    @Override
    public boolean isValid(Map<?, ?> value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        int length = value.size();
        return length >= min && length <= max;
    }
}
