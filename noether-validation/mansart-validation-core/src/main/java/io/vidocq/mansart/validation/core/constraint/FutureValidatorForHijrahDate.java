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
import jakarta.validation.constraints.Future;
import java.time.Clock;
import java.time.chrono.HijrahDate;

/**
 * Checks that a {@code HijrahDate} is after the current time; {@code null} is valid.
 *
 * <p>Implements the {@link Future} constraint for values of type {@link HijrahDate} as defined by the Jakarta Validation 3.1 specification, chapter "Built-in Constraint definitions". The reference "now" comes from {@code context.getClockProvider().getClock()}; the value is compared with {@code HijrahDate.now(clock)}, i.e. in the clock time zone.
 */
public final class FutureValidatorForHijrahDate implements ConstraintValidator<Future, HijrahDate> {
    @Override
    public boolean isValid(HijrahDate value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        Clock clock = context.getClockProvider().getClock();
        return value.compareTo(HijrahDate.now(clock)) > 0;
    }
}
