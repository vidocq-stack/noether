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
package io.vidocq.mansart.validation.core.engine;

import io.vidocq.mansart.validation.core.constraint.BuiltInConstraints;
import io.vidocq.mansart.validation.core.metadata.ConstraintDef;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.UnexpectedTypeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Picks the validator of a constraint for the declared type of the validated element (spec 6.? "validator
 * resolution"): the validators whose type accepts the declared type, then the most specific one.
 */
final class ValidatorChoice {

    private static final Map<Class<?>, Class<?>> BOXES = Map.of(
        boolean.class, Boolean.class, byte.class, Byte.class, char.class, Character.class, short.class, Short.class,
        int.class, Integer.class, long.class, Long.class, float.class, Float.class, double.class, Double.class,
        void.class, Void.class);

    private ValidatorChoice() {
    }

    static Class<?> box(Class<?> type) {
        return BOXES.getOrDefault(type, type);
    }

    /** The candidate validators of a constraint: the ones it declares, then the built-in ones. */
    static List<Class<? extends ConstraintValidator<?, ?>>> candidates(ConstraintDef constraint) {
        List<Class<? extends ConstraintValidator<?, ?>>> all = new ArrayList<>();
        for (Class<?> declared : constraint.getConstraintValidatorClasses()) {
            @SuppressWarnings("unchecked")
            Class<? extends ConstraintValidator<?, ?>> validator = (Class<? extends ConstraintValidator<?, ?>>) declared;
            all.add(validator);
        }
        all.addAll(BuiltInConstraints.validatorsFor(constraint.annotationType()));
        return all;
    }

    /** {@code null} when the constraint has no validator at all (it is then made of composing constraints only). */
    static Class<? extends ConstraintValidator<?, ?>> choose(ConstraintDef constraint, Class<?> declaredType) {
        List<Class<? extends ConstraintValidator<?, ?>>> candidates = candidates(constraint);
        if (candidates.isEmpty()) {
            return null;
        }
        Class<?> boxed = box(declaredType);
        List<Class<? extends ConstraintValidator<?, ?>>> applicable = new ArrayList<>();
        for (Class<? extends ConstraintValidator<?, ?>> candidate : candidates) {
            if (box(ValidatorTypes.validatedType(candidate)).isAssignableFrom(boxed)) {
                applicable.add(candidate);
            }
        }
        List<Class<? extends ConstraintValidator<?, ?>>> mostSpecific = new ArrayList<>();
        for (Class<? extends ConstraintValidator<?, ?>> candidate : applicable) {
            Class<?> type = box(ValidatorTypes.validatedType(candidate));
            boolean beaten = applicable.stream().map(c -> box(ValidatorTypes.validatedType(c)))
                .anyMatch(other -> other != type && type.isAssignableFrom(other));
            if (!beaten) {
                mostSpecific.add(candidate);
            }
        }
        if (mostSpecific.isEmpty()) {
            throw new UnexpectedTypeException("No validator could be found for constraint '" + constraint.annotationType().getName()
                + "' validating type '" + declaredType.getName() + "'. Check configuration for the validated element.");
        }
        if (mostSpecific.size() > 1) {
            throw new UnexpectedTypeException("More than one validator is applicable for constraint '"
                + constraint.annotationType().getName() + "' validating type '" + declaredType.getName() + "': " + mostSpecific);
        }
        return mostSpecific.get(0);
    }
}
