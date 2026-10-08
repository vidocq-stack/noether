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
package io.vidocq.mansart.jpa.processorit;

import static org.assertj.core.api.Assertions.assertThat;

import io.vidocq.mansart.jpa.processorit.closed.Ledger;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import org.junit.jupiter.api.Test;

/** Compiled with mansart-jpa-processor, an application needs no opens: the generated accesses are handed over. */
class GeneratedAccessModulePathTest {

    @Test
    void theEntityPackageIsNotOpenedToMansart() {
        Module mansart = ModuleLayer.boot().findModule("io.vidocq.mansart.jpa.core").orElseThrow();
        assertThat(getClass().getModule().getName()).isEqualTo("io.vidocq.mansart.jpa.processorit");
        assertThat(Ledger.class.getModule().isOpen(Ledger.class.getPackageName(), mansart)).isFalse();
        assertThat(Ledger.class.getModule().isExported(Ledger.class.getPackageName(), mansart)).isFalse();
    }

    @Test
    void theEntityIsMappedThroughItsGeneratedAccess() { // without it, Mansart would ask for an opens clause
        PersistenceConfiguration unit = new PersistenceConfiguration("processor-it")
            .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider").managedClass(Ledger.class)
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:processor-it");
        try (EntityManagerFactory emf = unit.createEntityManagerFactory()) {
            assertThat(emf.isOpen()).isTrue();
        }
    }
}
