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

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** Tests for the {@link BuiltInConstraints} registry. */
class BuiltInConstraintsTest {

    private static final List<Class<? extends Annotation>> ALL = List.of(
            AssertFalse.class, AssertTrue.class, DecimalMax.class, DecimalMin.class, Digits.class, Email.class,
            Future.class, FutureOrPresent.class, Max.class, Min.class, Negative.class, NegativeOrZero.class,
            NotBlank.class, NotEmpty.class, NotNull.class, Null.class, Past.class, PastOrPresent.class,
            Pattern.class, Positive.class, PositiveOrZero.class, Size.class);

    @Test
    void everyBuiltInConstraintHasValidatorsDeclaredForItsOwnAnnotation() {
        for (Class<? extends Annotation> type : ALL) {
            var validators = BuiltInConstraints.validatorsFor(type);
            assertThat(validators).as(type.getSimpleName()).isNotEmpty().doesNotHaveDuplicates();
            for (var validator : validators) {
                assertThat(declaredAnnotation(validator)).as(validator.getSimpleName()).isEqualTo(type);
            }
        }
    }

    @Test
    void validatorCountsMatchTheSupportedTypes() {
        assertThat(BuiltInConstraints.validatorsFor(Size.class)).hasSize(12);
        assertThat(BuiltInConstraints.validatorsFor(NotEmpty.class)).hasSize(12);
        assertThat(BuiltInConstraints.validatorsFor(Past.class)).hasSize(16);
        assertThat(BuiltInConstraints.validatorsFor(FutureOrPresent.class)).hasSize(16);
        assertThat(BuiltInConstraints.validatorsFor(Min.class)).hasSize(6);
        assertThat(BuiltInConstraints.validatorsFor(DecimalMax.class)).hasSize(7);
        assertThat(BuiltInConstraints.validatorsFor(Digits.class)).hasSize(7);
        assertThat(BuiltInConstraints.validatorsFor(Positive.class)).hasSize(8);
        assertThat(BuiltInConstraints.validatorsFor(Email.class)).hasSize(1);
    }

    @Test
    void orderIsDeterministic() {
        assertThat(BuiltInConstraints.validatorsFor(Size.class)).isEqualTo(BuiltInConstraints.validatorsFor(Size.class));
    }

    @Test
    void validatorsHaveAPublicNoArgConstructor() throws ReflectiveOperationException {
        for (Class<? extends Annotation> type : ALL) {
            for (var validator : BuiltInConstraints.validatorsFor(type)) {
                assertThat(validator.getDeclaredConstructor().newInstance()).isInstanceOf(ConstraintValidator.class);
            }
        }
    }

    @Test
    void unknownAnnotationsYieldAnEmptyList() {
        assertThat(BuiltInConstraints.validatorsFor(Constraint.class)).isEmpty();
        assertThat(BuiltInConstraints.validatorsFor(Deprecated.class)).isEmpty();
    }

    @Test
    void returnedListIsUnmodifiable() {
        var list = BuiltInConstraints.validatorsFor(Size.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> list.add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void allValidatorClassesAreDistinct() {
        Set<?> all = ALL.stream().flatMap(t -> BuiltInConstraints.validatorsFor(t).stream()).collect(Collectors.toSet());
        long total = ALL.stream().mapToLong(t -> BuiltInConstraints.validatorsFor(t).size()).sum();
        assertThat(all).hasSize((int) total);
    }

    private static Class<?> declaredAnnotation(Class<?> validator) {
        for (var type : validator.getGenericInterfaces()) {
            if (type instanceof ParameterizedType p && p.getRawType() == ConstraintValidator.class) {
                return (Class<?>) p.getActualTypeArguments()[0];
            }
        }
        throw new AssertionError(validator);
    }
}
