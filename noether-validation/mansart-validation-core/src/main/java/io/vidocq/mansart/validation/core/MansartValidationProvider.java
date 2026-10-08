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

import jakarta.validation.Configuration;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.spi.BootstrapState;
import jakarta.validation.spi.ConfigurationState;
import jakarta.validation.spi.ValidationProvider;

/**
 * The Mansart {@link ValidationProvider}: bootstrap (configuration, {@code validation.xml}, factory).
 * The validation engine itself arrives with milestone V2.
 */
public final class MansartValidationProvider implements ValidationProvider<MansartConfiguration> {

    @Override
    public MansartConfiguration createSpecializedConfiguration(BootstrapState state) {
        return new MansartConfigurationImpl(state);
    }

    @Override
    public Configuration<?> createGenericConfiguration(BootstrapState state) {
        return new MansartConfigurationImpl(state);
    }

    @Override
    public ValidatorFactory buildValidatorFactory(ConfigurationState state) {
        return new MansartValidatorFactory(new Components(state.getMessageInterpolator(),
            state.getTraversableResolver(), state.getConstraintValidatorFactory(), state.getParameterNameProvider(),
            state.getClockProvider(), state.getValueExtractors()));
    }
}
