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
 * {@code UPDATE table SET value = value + ? WHERE key = ?}: the row of a table generator (§11.1.52) moves forward by the
 * first parameter, in place, so that concurrent generators never read the same value; the second parameter selects
 * the row.
 */
public record Increment(Table table, Identifier value, Identifier key) implements Statement {

    public Increment {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(key, "key");
    }
}
