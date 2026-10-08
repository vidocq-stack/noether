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
import jakarta.validation.constraints.NotEmpty;

/**
 * Checks that a {@code char[]} is not {@code null} and not empty.
 *
 * <p>Implements the {@link NotEmpty} constraint for values of type {@link char[]} as defined by the Jakarta Validation 3.1 specification, chapter "Built-in Constraint definitions".
 */
public final class NotEmptyValidatorForCharArray implements ConstraintValidator<NotEmpty, char[]> {
    @Override
    public boolean isValid(char[] value, ConstraintValidatorContext context) {
        return value != null && value.length > 0;
    }
}
