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
import static org.assertj.core.api.Assertions.assertThatCode;

import jakarta.validation.Validation;
import jakarta.validation.spi.ValidationProvider;
import org.junit.jupiter.api.Test;

/**
 * Jakarta Validation 3.1, chapter 5 (bootstrapping): a provider is discovered through the service
 * loader. The module path is the primary path: these tests run inside the named module
 * {@code io.vidocq.mansart.validation.core} (surefire patches the test classes into it).
 */
class ProviderDiscoveryTest {

    @Test
    void testsRunInsideTheNamedModule() {
        assertThat(MansartValidationProvider.class.getModule().isNamed()).isTrue();
    }

    @Test
    void moduleDeclaresTheProvider() {
        var provides = MansartValidationProvider.class.getModule().getDescriptor().provides();
        assertThat(provides)
            .anyMatch(p -> p.service().equals(ValidationProvider.class.getName())
                && p.providers().contains(MansartValidationProvider.class.getName()));
    }

    @Test
    void bootstrapApiDiscoversTheProviderThroughTheModuleGraph() {
        // The lookup runs in jakarta.validation, which declares `uses ValidationProvider`.
        assertThatCode(() -> Validation.byProvider(MansartValidationProvider.class))
            .doesNotThrowAnyException();
    }
}
