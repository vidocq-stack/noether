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
package io.vidocq.mansart.validation.core.metadata;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Constraint annotations of every shape the metadata reader must understand. */
public final class ConstraintFixtures {

    private ConstraintFixtures() {
    }

    public static class RequiredValidator implements ConstraintValidator<Required, Object> {
        @Override
        public boolean isValid(Object value, ConstraintValidatorContext context) {
            return value != null;
        }
    }

    @Documented
    @Constraint(validatedBy = RequiredValidator.class)
    @Retention(RetentionPolicy.RUNTIME)
    @Repeatable(Required.List.class)
    public @interface Required {
        String message() default "required!";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
        int level() default 1;

        @Documented
        @Retention(RetentionPolicy.RUNTIME)
        @interface List {
            Required[] value();
        }
    }

    /** Composed of {@link NotNull} and {@link Size}; reports one violation. */
    @Constraint(validatedBy = {})
    @NotNull
    @Size(min = 2)
    @ReportAsSingleViolation
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Composed {
        String message() default "composed!";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    /** Not a constraint: no {@code @Constraint}. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Plain {
    }

    /** Invalid: no {@code message}. */
    @Constraint(validatedBy = {})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface NoMessage {
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    /** Invalid: a member name starting with "valid" is reserved. */
    @Constraint(validatedBy = {})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ReservedName {
        String message() default "";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
        String validFoo() default "";
    }

    /** A container that is not public: its instances cannot be generated, its content is all that is needed. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @interface HiddenList {
        Required[] value();
    }

    public interface Group {
    }

    public static class Holder {
        @Required
        public String plain;
        @Required(message = "custom", groups = Group.class, level = 5)
        public String custom;
        @Required(level = 2)
        @Required(level = 3)
        public String repeated;
        @Composed
        public String composed;
        @Plain
        public String notAConstraint;
        @NoMessage
        public String noMessage;
        @ReservedName
        public String reserved;
        @NotNull
        @Size(min = 1, max = 3)
        public String builtIns;
        @HiddenList({@Required(level = 7), @Required(level = 8)})
        public String hiddenContainer;
    }
}
