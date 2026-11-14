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
 * https://joinup.ec.europa.eu/collection/eupl/eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.processorit;

import io.vidocq.noether.jpa.core.spi.PersistenceUnitDeclaration;
import io.vidocq.noether.jpa.core.spi.PersistenceUnitSource;
import io.vidocq.noether.jpa.processorit.closed.Balance;
import io.vidocq.noether.jpa.processorit.closed.Ledger;
import java.util.List;

/**
 * A runtime's source of persistence units, as the Vidocq keys declare them (Vidocq/vidocq#551): handed over by the
 * {@code provides} clause of this module, found by Noether on the module path.
 */
public final class ConfiguredUnits implements PersistenceUnitSource {

    @Override
    public List<PersistenceUnitDeclaration> units(ClassLoader loader) {
        return List.of(PersistenceUnitDeclaration.builder("processor-from-source", "the units of this module's source")
            .classes(List.of(Ledger.class.getName(), Balance.class.getName()))
            .property("jakarta.persistence.jdbc.url", "jdbc:h2:mem:processor-from-source;DB_CLOSE_DELAY=-1")
            .property("jakarta.persistence.schema-generation.database.action", "drop-and-create")
            .build());
    }
}
