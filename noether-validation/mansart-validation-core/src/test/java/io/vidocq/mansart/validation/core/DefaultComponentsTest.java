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
package io.vidocq.mansart.validation.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ValidationException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Clock;
import org.junit.jupiter.api.Test;

/** Jakarta Validation 3.1, chapter 5: the default {@code ConstraintValidatorFactory}, {@code ClockProvider}, ... */
class DefaultComponentsTest {

    public static class PublicValidator implements ConstraintValidator<Deprecated, Object> {
        static boolean constructed;

        public PublicValidator() {
            constructed = true;
        }

        @Override
        public boolean isValid(Object value, ConstraintValidatorContext context) {
            return true;
        }
    }

    static class PackagePrivateValidator implements ConstraintValidator<Deprecated, Object> {
        @Override
        public boolean isValid(Object value, ConstraintValidatorContext context) {
            return true;
        }
    }

    public static class FailingValidator implements ConstraintValidator<Deprecated, Object> {
        public FailingValidator() {
            throw new IllegalStateException("boom");
        }

        @Override
        public boolean isValid(Object value, ConstraintValidatorContext context) {
            return true;
        }
    }

    public static class NoDefaultConstructorValidator implements ConstraintValidator<Deprecated, Object> {
        public NoDefaultConstructorValidator(String ignored) {
        }

        @Override
        public boolean isValid(Object value, ConstraintValidatorContext context) {
            return true;
        }
    }

    private final ConstraintValidatorFactory factory = new DefaultConstraintValidatorFactory();

    @Test
    void thePublicNoArgConstructorIsCalled() {
        PublicValidator.constructed = false;
        assertThat(factory.getInstance(PublicValidator.class)).isInstanceOf(PublicValidator.class);
        assertThat(PublicValidator.constructed).isTrue();
    }

    @Test
    void aNonPublicValidatorIsInstantiated() {
        assertThat(factory.getInstance(PackagePrivateValidator.class)).isInstanceOf(PackagePrivateValidator.class);
    }

    @Test
    void aRuntimeExceptionInTheConstructorIsWrapped() {
        assertThatThrownBy(() -> factory.getInstance(FailingValidator.class))
            .isInstanceOf(ValidationException.class)
            .hasRootCauseMessage("boom");
    }

    @Test
    void aValidatorWithoutNoArgConstructorIsRejected() {
        assertThatThrownBy(() -> factory.getInstance(NoDefaultConstructorValidator.class))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void releasingAnInstanceDoesNothing() {
        factory.releaseInstance(factory.getInstance(PublicValidator.class));
    }

    @Test
    void theDefaultClockIsTheSystemDefaultClock() {
        Clock clock = new DefaultClockProvider().getClock();
        assertThat(clock.getZone()).isEqualTo(Clock.systemDefaultZone().getZone());
    }

    void sample(String first, int second) {
    }

    @Test
    void theDefaultParameterNamesAreTheJdkOnes() throws Exception {
        Method method = DefaultComponentsTest.class.getDeclaredMethod("sample", String.class, int.class);
        assertThat(new DefaultParameterNameProvider().getParameterNames(method)).hasSize(2).doesNotContainNull();
        Constructor<?> constructor = PackagePrivateValidator.class.getDeclaredConstructors()[0];
        assertThat(new DefaultParameterNameProvider().getParameterNames(constructor)).isNotNull();
    }

    @Test
    void theDefaultTraversableResolverReachesAndCascadesEverything() {
        var resolver = new DefaultTraversableResolver();
        assertThat(resolver.isReachable(null, null, null, null, null)).isTrue();
        assertThat(resolver.isCascadable(null, null, null, null, null)).isTrue();
    }
}
