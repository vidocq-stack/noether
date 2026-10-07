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
 * The Mansart {@link ValidationProvider}. Skeleton only: it is discoverable, every bootstrap method
 * fails explicitly until milestone V1 of {@code ROADMAP.md}.
 */
public final class MansartValidationProvider implements ValidationProvider<MansartConfiguration> {

    @Override
    public MansartConfiguration createSpecializedConfiguration(BootstrapState state) {
        throw notYetImplemented();
    }

    @Override
    public Configuration<?> createGenericConfiguration(BootstrapState state) {
        throw notYetImplemented();
    }

    @Override
    public ValidatorFactory buildValidatorFactory(ConfigurationState configurationState) {
        throw notYetImplemented();
    }

    private static UnsupportedOperationException notYetImplemented() {
        return new UnsupportedOperationException("Mansart Validation bootstrap is not implemented yet (milestone V1)");
    }
}
