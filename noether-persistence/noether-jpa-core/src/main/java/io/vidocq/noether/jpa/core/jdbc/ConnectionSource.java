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
package io.vidocq.noether.jpa.core.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.OptionalInt;

/** Where the JDBC connections of a persistence unit come from. Implementations are thread-safe. */
public interface ConnectionSource {

    /** A connection; the caller closes it, which returns a pooled connection to its pool. */
    Connection acquire() throws SQLException;

    /**
     * The most connections the source hands out at once, when the unit sets it: the
     * {@value ConnectionSources#POOL_MAX_SIZE} of its Noether pool. Empty when Noether does not know it: one connection
     * per transaction, a pool left at its default size, or a {@code DataSource} of the application or the container.
     */
    default OptionalInt maxSize() {
        return OptionalInt.empty();
    }

    /** Releases what the source holds (the connections of its pool) when the factory closes. */
    default void close() {
    }
}
