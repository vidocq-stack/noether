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
package io.vidocq.mansart.jpa.dialect.postgresql;

import io.vidocq.mansart.jpa.dialect.StandardDialect;
import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import io.vidocq.mansart.jpa.dialect.sql.NextValue;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** The PostgreSQL dialect: ANSI rendering, overridden where PostgreSQL differs. */
public final class PostgreSQLDialect extends StandardDialect {

    @Override
    protected String truncate(io.vidocq.mansart.jpa.dialect.sql.TruncateTables truncate) {
        return "TRUNCATE TABLE " + String.join(", ", truncate.tables().stream().map(this::table).toList());
    }

    @Override
    protected String schemaType(io.vidocq.mansart.jpa.dialect.sql.SchemaStatement.Column column) {
        return switch (column.type()) {
            case BINARY, VARBINARY, BLOB -> "BYTEA";
            case CLOB -> "TEXT";
            case TINYINT -> "SMALLINT";
            case DOUBLE -> "DOUBLE PRECISION";
            default -> super.schemaType(column);
        };
    }

    PostgreSQLDialect() {
    }

    /** PostgreSQL folds unquoted names to lower case. */
    @Override
    public String generatedKeyName(Identifier column) {
        return column.quoted() ? column.name() : column.name().toLowerCase(Locale.ROOT);
    }

    /** {@code nextval} takes the sequence as a string, resolved like a name: quoted parts keep their quotes. */
    @Override
    protected String nextValue(NextValue next) {
        return "SELECT nextval('" + table(next.table()).replace("'", "''") + "')";
    }

    /**
     * PostgreSQL has no {@code LOCATE} with a start position: the position of the search in the string from that
     * start, shifted back to the whole string, 0 when not found.
     */
    @Override
    protected String function(String name, List<String> arguments) {
        if (name.equals("LOCATE") && arguments.size() == 3) {
            String found = "POSITION(" + arguments.get(0) + " IN SUBSTRING(" + arguments.get(1) + " FROM " + arguments.get(2) + "))";
            return "CASE WHEN " + found + " = 0 THEN 0 ELSE " + found + " + " + arguments.get(2) + " - 1 END";
        }
        return super.function(name, arguments);
    }

    /** {@code SET LOCAL}: the timeout ends with the transaction, never left on a pooled connection. */
    @Override
    public String lockTimeout(int milliseconds) {
        return "SET LOCAL lock_timeout = '" + milliseconds + "ms'";
    }

    /** PostgreSQL: 55P03 lock_not_available is a timeout; 40P01 deadlock_detected and 40001 are pessimistic. */
    @Override
    public LockFailure lockFailure(SQLException failure) {
        for (SQLException current = failure; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if ("55P03".equals(state)) {
                return LockFailure.TIMEOUT;
            }
            if ("40P01".equals(state) || "40001".equals(state)) {
                return LockFailure.PESSIMISTIC;
            }
        }
        return LockFailure.NONE;
    }

    /**
     * An untyped literal ({@code Types.OTHER}): PostgreSQL types it from where it is used, a {@code uuid} column or a
     * character one, which a {@code uuid} parameter would not compare with ({@code character varying = uuid}).
     */
    @Override
    public void bindUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        statement.setObject(index, value.toString(), Types.OTHER);
    }

    @Override
    public String renderRefCursorFetch(String cursorName) {
        if (cursorName == null || cursorName.isBlank()) {
            throw new IllegalArgumentException("A PostgreSQL REF_CURSOR needs a cursor name");
        }
        return "FETCH ALL IN \"" + cursorName.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String name() {
        return "postgresql";
    }
}
