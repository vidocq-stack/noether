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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of the validators of the built-in constraints (all constraint annotations of the
 * {@code jakarta.validation.constraints} package), as defined by the Jakarta Validation 3.1 specification, chapter "Built-in Constraint definitions".
 *
 * <p>The table is built from class literals only: no reflection and no classpath scanning. The
 * {@code .List} containers are not registered: the validation engine unwraps them.
 */
public final class BuiltInConstraints {

    private static final Map<Class<? extends Annotation>, List<Class<? extends ConstraintValidator<?, ?>>>> VALIDATORS =
            new HashMap<>();

    static {
        VALIDATORS.put(AssertFalse.class, List.of(
                AssertFalseValidatorForBoolean.class));
        VALIDATORS.put(AssertTrue.class, List.of(
                AssertTrueValidatorForBoolean.class));
        VALIDATORS.put(DecimalMax.class, List.of(
                DecimalMaxValidatorForBigDecimal.class,
                DecimalMaxValidatorForBigInteger.class,
                DecimalMaxValidatorForByte.class,
                DecimalMaxValidatorForShort.class,
                DecimalMaxValidatorForInteger.class,
                DecimalMaxValidatorForLong.class,
                DecimalMaxValidatorForCharSequence.class));
        VALIDATORS.put(DecimalMin.class, List.of(
                DecimalMinValidatorForBigDecimal.class,
                DecimalMinValidatorForBigInteger.class,
                DecimalMinValidatorForByte.class,
                DecimalMinValidatorForShort.class,
                DecimalMinValidatorForInteger.class,
                DecimalMinValidatorForLong.class,
                DecimalMinValidatorForCharSequence.class));
        VALIDATORS.put(Digits.class, List.of(
                DigitsValidatorForBigDecimal.class,
                DigitsValidatorForBigInteger.class,
                DigitsValidatorForByte.class,
                DigitsValidatorForShort.class,
                DigitsValidatorForInteger.class,
                DigitsValidatorForLong.class,
                DigitsValidatorForCharSequence.class));
        VALIDATORS.put(Email.class, List.of(
                EmailValidatorForCharSequence.class));
        VALIDATORS.put(Future.class, List.of(
                FutureValidatorForDate.class,
                FutureValidatorForCalendar.class,
                FutureValidatorForInstant.class,
                FutureValidatorForLocalDate.class,
                FutureValidatorForLocalDateTime.class,
                FutureValidatorForLocalTime.class,
                FutureValidatorForMonthDay.class,
                FutureValidatorForOffsetDateTime.class,
                FutureValidatorForOffsetTime.class,
                FutureValidatorForYear.class,
                FutureValidatorForYearMonth.class,
                FutureValidatorForZonedDateTime.class,
                FutureValidatorForHijrahDate.class,
                FutureValidatorForJapaneseDate.class,
                FutureValidatorForMinguoDate.class,
                FutureValidatorForThaiBuddhistDate.class));
        VALIDATORS.put(FutureOrPresent.class, List.of(
                FutureOrPresentValidatorForDate.class,
                FutureOrPresentValidatorForCalendar.class,
                FutureOrPresentValidatorForInstant.class,
                FutureOrPresentValidatorForLocalDate.class,
                FutureOrPresentValidatorForLocalDateTime.class,
                FutureOrPresentValidatorForLocalTime.class,
                FutureOrPresentValidatorForMonthDay.class,
                FutureOrPresentValidatorForOffsetDateTime.class,
                FutureOrPresentValidatorForOffsetTime.class,
                FutureOrPresentValidatorForYear.class,
                FutureOrPresentValidatorForYearMonth.class,
                FutureOrPresentValidatorForZonedDateTime.class,
                FutureOrPresentValidatorForHijrahDate.class,
                FutureOrPresentValidatorForJapaneseDate.class,
                FutureOrPresentValidatorForMinguoDate.class,
                FutureOrPresentValidatorForThaiBuddhistDate.class));
        VALIDATORS.put(Max.class, List.of(
                MaxValidatorForBigDecimal.class,
                MaxValidatorForBigInteger.class,
                MaxValidatorForByte.class,
                MaxValidatorForShort.class,
                MaxValidatorForInteger.class,
                MaxValidatorForLong.class));
        VALIDATORS.put(Min.class, List.of(
                MinValidatorForBigDecimal.class,
                MinValidatorForBigInteger.class,
                MinValidatorForByte.class,
                MinValidatorForShort.class,
                MinValidatorForInteger.class,
                MinValidatorForLong.class));
        VALIDATORS.put(Negative.class, List.of(
                NegativeValidatorForBigDecimal.class,
                NegativeValidatorForBigInteger.class,
                NegativeValidatorForByte.class,
                NegativeValidatorForShort.class,
                NegativeValidatorForInteger.class,
                NegativeValidatorForLong.class,
                NegativeValidatorForFloat.class,
                NegativeValidatorForDouble.class));
        VALIDATORS.put(NegativeOrZero.class, List.of(
                NegativeOrZeroValidatorForBigDecimal.class,
                NegativeOrZeroValidatorForBigInteger.class,
                NegativeOrZeroValidatorForByte.class,
                NegativeOrZeroValidatorForShort.class,
                NegativeOrZeroValidatorForInteger.class,
                NegativeOrZeroValidatorForLong.class,
                NegativeOrZeroValidatorForFloat.class,
                NegativeOrZeroValidatorForDouble.class));
        VALIDATORS.put(NotBlank.class, List.of(
                NotBlankValidatorForCharSequence.class));
        VALIDATORS.put(NotEmpty.class, List.of(
                NotEmptyValidatorForCharSequence.class,
                NotEmptyValidatorForCollection.class,
                NotEmptyValidatorForMap.class,
                NotEmptyValidatorForObjectArray.class,
                NotEmptyValidatorForBooleanArray.class,
                NotEmptyValidatorForByteArray.class,
                NotEmptyValidatorForCharArray.class,
                NotEmptyValidatorForDoubleArray.class,
                NotEmptyValidatorForFloatArray.class,
                NotEmptyValidatorForIntArray.class,
                NotEmptyValidatorForLongArray.class,
                NotEmptyValidatorForShortArray.class));
        VALIDATORS.put(NotNull.class, List.of(
                NotNullValidatorForObject.class));
        VALIDATORS.put(Null.class, List.of(
                NullValidatorForObject.class));
        VALIDATORS.put(Past.class, List.of(
                PastValidatorForDate.class,
                PastValidatorForCalendar.class,
                PastValidatorForInstant.class,
                PastValidatorForLocalDate.class,
                PastValidatorForLocalDateTime.class,
                PastValidatorForLocalTime.class,
                PastValidatorForMonthDay.class,
                PastValidatorForOffsetDateTime.class,
                PastValidatorForOffsetTime.class,
                PastValidatorForYear.class,
                PastValidatorForYearMonth.class,
                PastValidatorForZonedDateTime.class,
                PastValidatorForHijrahDate.class,
                PastValidatorForJapaneseDate.class,
                PastValidatorForMinguoDate.class,
                PastValidatorForThaiBuddhistDate.class));
        VALIDATORS.put(PastOrPresent.class, List.of(
                PastOrPresentValidatorForDate.class,
                PastOrPresentValidatorForCalendar.class,
                PastOrPresentValidatorForInstant.class,
                PastOrPresentValidatorForLocalDate.class,
                PastOrPresentValidatorForLocalDateTime.class,
                PastOrPresentValidatorForLocalTime.class,
                PastOrPresentValidatorForMonthDay.class,
                PastOrPresentValidatorForOffsetDateTime.class,
                PastOrPresentValidatorForOffsetTime.class,
                PastOrPresentValidatorForYear.class,
                PastOrPresentValidatorForYearMonth.class,
                PastOrPresentValidatorForZonedDateTime.class,
                PastOrPresentValidatorForHijrahDate.class,
                PastOrPresentValidatorForJapaneseDate.class,
                PastOrPresentValidatorForMinguoDate.class,
                PastOrPresentValidatorForThaiBuddhistDate.class));
        VALIDATORS.put(Pattern.class, List.of(
                PatternValidatorForCharSequence.class));
        VALIDATORS.put(Positive.class, List.of(
                PositiveValidatorForBigDecimal.class,
                PositiveValidatorForBigInteger.class,
                PositiveValidatorForByte.class,
                PositiveValidatorForShort.class,
                PositiveValidatorForInteger.class,
                PositiveValidatorForLong.class,
                PositiveValidatorForFloat.class,
                PositiveValidatorForDouble.class));
        VALIDATORS.put(PositiveOrZero.class, List.of(
                PositiveOrZeroValidatorForBigDecimal.class,
                PositiveOrZeroValidatorForBigInteger.class,
                PositiveOrZeroValidatorForByte.class,
                PositiveOrZeroValidatorForShort.class,
                PositiveOrZeroValidatorForInteger.class,
                PositiveOrZeroValidatorForLong.class,
                PositiveOrZeroValidatorForFloat.class,
                PositiveOrZeroValidatorForDouble.class));
        VALIDATORS.put(Size.class, List.of(
                SizeValidatorForCharSequence.class,
                SizeValidatorForCollection.class,
                SizeValidatorForMap.class,
                SizeValidatorForObjectArray.class,
                SizeValidatorForBooleanArray.class,
                SizeValidatorForByteArray.class,
                SizeValidatorForCharArray.class,
                SizeValidatorForDoubleArray.class,
                SizeValidatorForFloatArray.class,
                SizeValidatorForIntArray.class,
                SizeValidatorForLongArray.class,
                SizeValidatorForShortArray.class));
    }

    private BuiltInConstraints() {
    }

    /**
     * Returns the validator classes of a built-in constraint.
     *
     * @param constraintType the constraint annotation type
     * @return an unmodifiable list, in a fixed order (one entry per supported type), or an empty
     *         list when {@code constraintType} is not a built-in constraint
     */
    public static List<Class<? extends ConstraintValidator<?, ?>>> validatorsFor(Class<? extends Annotation> constraintType) {
        return VALIDATORS.getOrDefault(constraintType, List.of());
    }
}
