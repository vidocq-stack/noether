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

import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.lang.annotation.Annotation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Shared test helpers: a hand-written {@link ConstraintValidatorContext} stub with a fixed clock
 * and annotation lookup on fixture fields (reflection is acceptable in tests only).
 */
final class TestSupport {

    /** 2026-06-15T12:00:00Z, expressed in Asia/Tokyo (local 2026-06-15T21:00). */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneId.of("Asia/Tokyo"));

    static final ConstraintValidatorContext CONTEXT = new StubContext();

    private TestSupport() {
    }

    static <A extends Annotation> A annotation(Class<?> holder, String field, Class<A> type) {
        try {
            return holder.getDeclaredField(field).getAnnotation(type);
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }

    /** Initializes the validator with the annotation and validates the value against the stub context. */
    static <A extends Annotation, T> boolean valid(ConstraintValidator<A, T> validator, A annotation, T value) {
        validator.initialize(annotation);
        return validator.isValid(value, CONTEXT);
    }

    /** Returns one character per value: 'T' when valid, 'F' when invalid. */
    @SafeVarargs
    static <A extends Annotation, T> String pattern(ConstraintValidator<A, T> validator, A annotation, T... values) {
        validator.initialize(annotation);
        StringBuilder sb = new StringBuilder();
        for (T value : values) {
            sb.append(validator.isValid(value, CONTEXT) ? 'T' : 'F');
        }
        return sb.toString();
    }

    private static final class StubContext implements ConstraintValidatorContext {
        @Override
        public void disableDefaultConstraintViolation() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getDefaultConstraintMessageTemplate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClockProvider getClockProvider() {
            return () -> CLOCK;
        }

        @Override
        public ConstraintViolationBuilder buildConstraintViolationWithTemplate(String messageTemplate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            throw new UnsupportedOperationException();
        }
    }
}
