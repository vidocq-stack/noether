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
package io.vidocq.noether.jpa.core.spi;

import io.vidocq.noether.jpa.core.bootstrap.PersistenceUnits;
import jakarta.persistence.PersistenceException;
import java.util.List;

/**
 * The persistence units a deployment declares, read by the provider's own {@code persistence.xml} parser, for the
 * container integrations: they never parse a descriptor themselves.
 */
public final class PersistenceDescriptors {

    private PersistenceDescriptors() {
    }

    /**
     * The name of the only persistence unit declared by the {@code META-INF/persistence.xml} files of {@code loader}:
     * the unit a container uses when an injection point names none.
     *
     * @throws PersistenceException if the files declare no unit or several, or cannot be read
     */
    public static String defaultUnitName(ClassLoader loader) {
        List<String> units = PersistenceUnits.names(loader);
        if (units.size() != 1) {
            throw new PersistenceException("Default persistence injection requires exactly one named persistence unit; found "
                + units);
        }
        return units.getFirst();
    }
}
