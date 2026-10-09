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
import jakarta.persistence.LockModeType;
import java.sql.Connection;
import java.util.function.Function;

/** What a query needs from the entity manager that created it. */
public interface QueryRuntime {

    /** Whether the entity manager is open: the methods of its queries need it (§3.11). */
    boolean isOpen();

    /** The mapped persistence unit. */
    MappedUnit mapping();

    /** Checks the unit and root context of a fetch/load graph hint before execution. */
    default void validateGraph(Object graph, Class<?> root) {
        if (!(graph instanceof jakarta.persistence.EntityGraph<?>)) {
            throw new IllegalArgumentException("Fetch/load hint must be an EntityGraph");
        }
    }

    /**
     * Runs {@code work} on the connection of the transaction, or on one of its own outside a transaction; first flushes
     * the persistence context when {@code flushMode} is {@code AUTO} and a transaction is active (§3.10.8). A failure
     * marks the transaction for rollback (§3.12).
     */
    <T> T read(FlushModeType flushMode, Function<Connection, T> work);

    /**
     * Runs a bulk statement's {@code work} on the connection of the active transaction —
     * {@link jakarta.persistence.TransactionRequiredException} without one — after a flush unless {@code flushMode} is
     * {@code COMMIT} (§3.11.6, §4.10). A failure marks the transaction for rollback (§3.12).
     */
    <T> T write(FlushModeType flushMode, Function<Connection, T> work);

    /** Whether a transaction is active: a query with a lock mode needs one (§3.11). */
    boolean inTransaction();

    /** Records that the managed {@code entity}, which a query returned, holds the lock {@code mode} (§3.5). */
    void locked(Object entity, LockModeType mode);

    /** The dialect of the database behind {@code connection}. */
    Dialect dialect(Connection connection);

    /** The managed instance of {@code type} with identity {@code id}: the persistence context's, else loaded; null if none. */
    Object find(MappedEntity type, Object id, Connection connection);
}
