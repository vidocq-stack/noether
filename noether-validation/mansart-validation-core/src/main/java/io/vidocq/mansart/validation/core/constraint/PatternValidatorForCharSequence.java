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
import jakarta.validation.constraints.Pattern;

/**
 * Checks that a {@code CharSequence} matches the {@code regexp} of {@link Pattern}, honouring its
 * {@code flags}; {@code null} is valid.
 *
 * <p>Implements the {@link Pattern} constraint as defined by the Jakarta Validation 3.1
 * specification, chapter "Built-in Constraint definitions". The whole value must match
 * ({@link java.util.regex.Matcher#matches()}), not merely a substring.
 */
public final class PatternValidatorForCharSequence implements ConstraintValidator<Pattern, CharSequence> {

    private java.util.regex.Pattern compiled;

    @Override
    public void initialize(Pattern constraintAnnotation) {
        int flags = 0;
        for (Pattern.Flag flag : constraintAnnotation.flags()) {
            flags |= flag.getValue();
        }
        try {
            this.compiled = java.util.regex.Pattern.compile(constraintAnnotation.regexp(), flags);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regular expression: " + constraintAnnotation.regexp(), e);
        }
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return value == null || compiled.matcher(value).matches();
    }
}
