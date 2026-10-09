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
package io.vidocq.mansart.jpa.core.query;

import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import io.vidocq.mansart.jpa.core.mapping.MappedUnit;
import io.vidocq.mansart.jpa.dialect.Dialect;
import jakarta.persistence.FlushModeType;
import java.sql.Connection;
import java.util.function.Function;

/** What a query needs from the entity manager that created it. */
public interface QueryRuntime {

    /** The mapped persistence unit. */
    MappedUnit mapping();

    /**
     * Runs {@code work} on the connection of the transaction, or on one of its own outside a transaction; first flushes
     * the persistence context when {@code flushMode} is {@code AUTO} and a transaction is active (§3.10.8). A failure
     * marks the transaction for rollback (§3.12).
     */
    <T> T read(FlushModeType flushMode, Function<Connection, T> work);

    /** The dialect of the database behind {@code connection}. */
    Dialect dialect(Connection connection);

    /** The managed instance of {@code type} with identity {@code id}: the persistence context's, else loaded; null if none. */
    Object find(MappedEntity type, Object id, Connection connection);
}
