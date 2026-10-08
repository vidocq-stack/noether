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
package io.vidocq.mansart.jpa.dialect;

import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import io.vidocq.mansart.jpa.dialect.sql.Statement;
import java.sql.SQLException;

/**
 * Renders the statements of Mansart JPA for one database. A dialect is immutable and shared by every entity manager
 * of a factory; Mansart renders each statement of an entity once, at bootstrap or at first use.
 */
public interface Dialect {

    /** The name of the dialect, as the property {@code io.vidocq.mansart.jpa.dialect} selects it. */
    String name();

    /**
     * The SQL of {@code statement}, with one {@code ?} per parameter, in the order the statement documents. Pure
     * computation, no I/O and no call back into Mansart: the provider renders inside a concurrent cache.
     */
    String render(Statement statement);

    /**
     * Whether the driver returns the keys the database generated for every row of a batch of inserts
     * ({@code getGeneratedKeys} after {@code executeBatch}). If not, inserts with a generated key run one by one.
     */
    default boolean batchesGeneratedKeys() {
        return false;
    }

    /**
     * The name under which the driver returns a generated key column ({@code prepareStatement(sql, columnNames)}): a
     * quoted name as written, an unquoted one as the database folds it.
     */
    default String generatedKeyName(Identifier column) {
        return column.name();
    }

    /** Whether {@code failure} reports a duplicate key: the identity of an insert already exists (§3.2.2). */
    default boolean isDuplicateKey(SQLException failure) {
        for (SQLException current = failure; current != null; current = current.getNextException()) {
            if ("23505".equals(current.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
