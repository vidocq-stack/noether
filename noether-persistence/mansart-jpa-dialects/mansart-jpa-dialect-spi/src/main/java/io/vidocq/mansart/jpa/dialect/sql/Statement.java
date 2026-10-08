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

import java.util.List;

/**
 * A SQL statement of Mansart JPA, rendered by a {@code Dialect}. Statements carry no value: each column of their lists
 * is one JDBC parameter, bound in the order the statement documents. The hierarchy grows with the milestones (queries,
 * locks, DDL).
 */
public sealed interface Statement permits Insert, Update, Delete, Select {

    /** The table the statement works on. */
    Table table();

    static List<Identifier> copy(List<Identifier> columns, String what, boolean required) {
        List<Identifier> copy = List.copyOf(columns);
        if (required && copy.isEmpty()) {
            throw new IllegalArgumentException("A statement needs " + what);
        }
        return copy;
    }
}
