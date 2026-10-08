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

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Beans and constraints used by the engine tests. */
public final class EngineFixtures {

    private EngineFixtures() {
    }

    public interface Strict {
    }

    public interface Stricter extends Strict {
    }

    public interface Other {
    }

    // ---- a constraint with an attribute, and its validators ------------------------------------------

    @Constraint(validatedBy = {StartsWithValidator.class})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface StartsWith {
        String value();
        String message() default "must start with {value}";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    public static class StartsWithValidator implements ConstraintValidator<StartsWith, CharSequence> {
        static int initialized;
        private String prefix;

        @Override
        public void initialize(StartsWith annotation) {
            initialized++;
            prefix = annotation.value();
        }

        @Override
        public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
            return value == null || value.toString().startsWith(prefix);
        }
    }

    /** A class-level constraint that reports its own violations under sub-nodes. */
    @Constraint(validatedBy = {FullNameValidator.class})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ConsistentName {
        String message() default "inconsistent name";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    public static class FullNameValidator implements ConstraintValidator<ConsistentName, Person> {
        @Override
        public boolean isValid(Person person, ConstraintValidatorContext context) {
            if (person == null || person.first == null || person.first.length() > 2) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("too short").addPropertyNode("first").addConstraintViolation();
            return false;
        }
    }

    /** Throws from isValid. */
    @Constraint(validatedBy = {BrokenValidator.class})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Broken {
        String message() default "broken";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    public static class BrokenValidator implements ConstraintValidator<Broken, Object> {
        @Override
        public boolean isValid(Object value, ConstraintValidatorContext context) {
            throw new IllegalStateException("kaboom");
        }
    }

    /** Disables the default violation and adds none. */
    @Constraint(validatedBy = {SilentValidator.class})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Silent {
        String message() default "silent";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    public static class SilentValidator implements ConstraintValidator<Silent, Object> {
        @Override
        public boolean isValid(Object value, ConstraintValidatorContext context) {
            context.disableDefaultConstraintViolation();
            return false;
        }
    }

    /** Two validators, for unrelated types: ambiguous for a type that is both. */
    @Constraint(validatedBy = {AmbiguousForRunnable.class, AmbiguousForComparable.class})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Ambiguous {
        String message() default "ambiguous";
        Class<?>[] groups() default {};
        Class<? extends Payload>[] payload() default {};
    }

    public static class AmbiguousForRunnable implements ConstraintValidator<Ambiguous, Runnable> {
        @Override
        public boolean isValid(Runnable value, ConstraintValidatorContext context) {
            return true;
        }
    }

    public static class AmbiguousForComparable implements ConstraintValidator<Ambiguous, Comparable<?>> {
        @Override
        public boolean isValid(Comparable<?> value, ConstraintValidatorContext context) {
            return true;
        }
    }

    public static class RunnableComparable implements Runnable, Comparable<RunnableComparable> {
        @Override
        public void run() {
        }

        @Override
        public int compareTo(RunnableComparable other) {
            return 0;
        }
    }

    // ---- beans ---------------------------------------------------------------------------------------

    public static class Address {
        @NotNull
        public String street;
        @Size(min = 5, groups = Strict.class)
        public String zip;

        public Address(String street, String zip) {
            this.street = street;
            this.zip = zip;
        }
    }

    @ConsistentName
    public static class Person {
        @NotNull
        public String first;
        @Min(18)
        public int age = 30;
        @StartsWith("Mr")
        private String title = "Mr X";
        @Valid
        public Address home;
        @Valid
        public List<Address> others = new ArrayList<>();
        @Valid
        public Map<String, Address> byName = new HashMap<>();
        @Valid
        public Set<Address> unordered = new HashSet<>();
        @Valid
        public Address[] array = {};
        @NotNull
        @Size(min = 3)
        private String nickname = "nick";

        public Person(String first) {
            this.first = first;
        }

        public void title(String title) {
            this.title = title;
        }

        public void nickname(String nickname) {
            this.nickname = nickname;
        }

        @NotNull
        public String getComputed() {
            return null;
        }
    }

    public interface Named {
        @NotNull
        String getName();
    }

    public static class Employee extends Person implements Named {
        @Size(max = 2)
        public String code = "toolong";

        public Employee() {
            super("Employee");
        }

        @Override
        public String getName() {
            return null;
        }
    }

    /** Cyclic graph. */
    public static class Node {
        @NotNull
        public String label;
        @Valid
        public Node next;
        @Valid
        public List<Node> children = new ArrayList<>();

        public Node(String label) {
            this.label = label;
        }
    }

    public static class Grouped {
        @NotNull(groups = Strict.class)
        public String strict;
        @NotNull(groups = Stricter.class)
        public String stricter;
        @NotNull(groups = Other.class)
        public String other;
        @NotNull
        public String byDefault;
    }

    public static class Composition {
        @Size(min = 3)
        @StartsWith("a")
        public String twoConstraints = "b";
        @Broken
        public String broken = "x";
        @Silent
        public String silent = "x";
        @Ambiguous
        public RunnableComparable ambiguous = new RunnableComparable();
        @Min(5)
        public Object noValidatorForObject = 3;
    }

    public static class BadGetter {
        @NotNull
        public String getValue() {
            throw new IllegalStateException("getter always throws");
        }
    }
}
