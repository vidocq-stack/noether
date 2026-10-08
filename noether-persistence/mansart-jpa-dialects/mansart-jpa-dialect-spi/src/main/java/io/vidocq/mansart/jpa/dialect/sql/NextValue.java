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
package io.vidocq.mansart.jpa.dialect.sql;

import java.util.Objects;

/**
 * The next value of a sequence (identifier generation, §11.1.51): one row, one column, no parameter.
 *
 * @param schema the schema, or {@code null}
 * @param catalog the catalog, or {@code null}
 */
public record NextValue(Identifier name, Identifier schema, Identifier catalog) implements Statement {

    public NextValue {
        Objects.requireNonNull(name, "name");
    }

    /** The sequence, qualified as a table is. */
    @Override
    public Table table() {
        return new Table(name, schema, catalog);
    }
}
