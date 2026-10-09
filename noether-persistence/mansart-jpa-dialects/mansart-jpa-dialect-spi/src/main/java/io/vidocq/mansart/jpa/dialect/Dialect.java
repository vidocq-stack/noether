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

import io.vidocq.mansart.jpa.dialect.sql.Expression;
import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import io.vidocq.mansart.jpa.dialect.sql.DeleteQuery;
import io.vidocq.mansart.jpa.dialect.sql.Query;
import io.vidocq.mansart.jpa.dialect.sql.SelectStatement;
import io.vidocq.mansart.jpa.dialect.sql.UpdateQuery;
import io.vidocq.mansart.jpa.dialect.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

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

    /** A query as SQL, and its parameters in the order the SQL holds them — repeated if the SQL repeats them. */
    record Rendered(String sql, List<Expression.Parameter> parameters) {
        public Rendered {
            parameters = List.copyOf(parameters);
        }
    }

    /**
     * Renders a statement of the query language — a {@link SelectStatement}, an {@link UpdateQuery}, a {@link DeleteQuery} — and
     * tells the order of its parameters, which only the rendering knows (§4.6.17).
     */
    default Rendered renderQuery(Statement statement) {
        return new Rendered(render(statement), switch (statement) {
            case SelectStatement query -> query.parameters();
            case UpdateQuery update -> update.parameters();
            case DeleteQuery delete -> delete.parameters();
            default -> List.of();
        });
    }

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

    /** How a failure of a locking statement counts (§3.12). */
    enum LockFailure {
        /** Not a lock failure. */
        NONE,
        /** The lock was not obtained in time; only the statement is rolled back: {@code LockTimeoutException}. */
        TIMEOUT,
        /** A deadlock or a serialization failure; the transaction is lost: {@code PessimisticLockException}. */
        PESSIMISTIC
    }

    /** Classifies {@code failure}: the SQLSTATE of a serialization failure (40001) is pessimistic, by default. */
    default LockFailure lockFailure(SQLException failure) {
        for (SQLException current = failure; current != null; current = current.getNextException()) {
            if ("40001".equals(current.getSQLState())) {
                return LockFailure.PESSIMISTIC;
            }
        }
        return LockFailure.NONE;
    }

    /**
     * The statement that limits, for the current transaction only, how long a lock is waited for; {@code null} when
     * the database cannot scope it to the transaction (the database's own timeout then applies). A timeout of 0 is a
     * {@code noWait} select instead.
     */
    default String lockTimeout(int milliseconds) {
        return null;
    }

    /**
     * Binds a {@link UUID} (§2.8) to the parameter {@code index}: by default as a JDBC object, which a driver maps to
     * its native type. A database whose native type does not compare with the character column an application may
     * store it in overrides this.
     */
    default void bindUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        statement.setObject(index, value);
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
