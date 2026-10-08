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

import jakarta.validation.MessageInterpolator;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

/** Jakarta Validation 3.1, chapter 5.5 (ValidatorFactory) and 5.6 (ValidatorContext). */
class ValidatorFactoryTest {

    private static ValidatorFactory factory() {
        return Validation.byProvider(MansartValidationProvider.class).configure().buildValidatorFactory();
    }

    @Test
    void getValidatorReturnsAValidator() {
        try (ValidatorFactory f = factory()) {
            assertThat(f.getValidator()).isNotNull();
        }
    }

    @Test
    void closingTwiceIsHarmless() {
        ValidatorFactory f = factory();
        f.close();
        f.close();
    }

    @Test
    void aClosedFactoryRefusesNewValidators() {
        ValidatorFactory f = factory();
        f.close();
        assertThatThrownBy(f::getValidator).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(f::usingContext).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unwrapReturnsTheFactoryItself() {
        try (ValidatorFactory f = factory()) {
            assertThat(f.unwrap(MansartValidatorFactory.class)).isSameAs(f);
            assertThat(f.unwrap(ValidatorFactory.class)).isSameAs(f);
        }
    }

    @Test
    void unwrapRejectsAnUnsupportedType() {
        try (ValidatorFactory f = factory()) {
            assertThatThrownBy(() -> f.unwrap(String.class)).isInstanceOf(ValidationException.class);
        }
    }

    @Test
    void theValidatorUnwrapsToItselfAndRejectsOtherTypes() {
        try (ValidatorFactory f = factory()) {
            Validator v = f.getValidator();
            assertThat(v.unwrap(Validator.class)).isSameAs(v);
            assertThatThrownBy(() -> v.unwrap(String.class)).isInstanceOf(ValidationException.class);
        }
    }

    @Test
    void theValidatorContextOverridesOnlyWhatItIsGiven() {
        try (ValidatorFactory f = factory()) {
            MessageInterpolator custom = new ConfigurationBootstrapTest.XmlMessageInterpolator();
            Validator v = f.usingContext().messageInterpolator(custom).getValidator();
            assertThat(v).isNotNull();
            assertThat(f.getMessageInterpolator()).isNotSameAs(custom);
        }
    }

    @Test
    void theValidatorContextReturnsItselfFromEverySetter() {
        try (ValidatorFactory f = factory()) {
            var context = f.usingContext();
            assertThat(context.messageInterpolator(f.getMessageInterpolator())).isSameAs(context);
            assertThat(context.traversableResolver(f.getTraversableResolver())).isSameAs(context);
            assertThat(context.constraintValidatorFactory(f.getConstraintValidatorFactory())).isSameAs(context);
            assertThat(context.parameterNameProvider(f.getParameterNameProvider())).isSameAs(context);
            assertThat(context.clockProvider(f.getClockProvider())).isSameAs(context);
        }
    }
}
