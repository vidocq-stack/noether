/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.validation.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Jakarta Validation 3.1, 6.1.3: the type a validator validates, read through its generic supertypes. */
class ValidatorTypesTest {

    /** Leaves the validated type to its subclasses. */
    abstract static class BoundSize<T> implements ConstraintValidator<Size, T> {
    }

    /** Binds it through a second type variable. */
    abstract static class RenamedSize<U> extends BoundSize<U> {
    }

    static final class StringSize extends RenamedSize<String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return true;
        }
    }

    static final class ListSize extends BoundSize<List<String>> {
        @Override
        public boolean isValid(List<String> value, ConstraintValidatorContext context) {
            return true;
        }
    }

    @Test
    void theTypeVariablesOfTheSuperclassesAreResolvedDownToTheValidator() {
        assertThat(List.of(ValidatorTypes.validatedType(StringSize.class), ValidatorTypes.validatedType(ListSize.class)))
            .containsExactly(String.class, List.class);
    }
}
