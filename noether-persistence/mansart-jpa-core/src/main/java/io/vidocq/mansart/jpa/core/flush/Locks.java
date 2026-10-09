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
package io.vidocq.mansart.jpa.core.flush;

import io.vidocq.mansart.jpa.core.context.ManagedEntity;
import io.vidocq.mansart.jpa.core.context.PersistenceContext;
import io.vidocq.mansart.jpa.core.jdbc.type.ValueBinder;
import io.vidocq.mansart.jpa.core.mapping.EntityStatements;
import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import io.vidocq.mansart.jpa.dialect.Dialect;
import io.vidocq.mansart.jpa.dialect.sql.Select;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PessimisticLockException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * The locks of §3.5 on managed instances: an optimistic mode is recorded and checked or forced at flush; a pessimistic
 * mode locks the row now ({@code FOR UPDATE} / {@code FOR SHARE}, rendered by the dialect), waiting at most the lock
 * timeout, and checks that the row still has the version the instance was read at.
 */
public final class Locks {

    private final FlushEngine engine;

    public Locks(FlushEngine engine) {
        this.engine = engine;
    }

    public static boolean pessimistic(LockModeType mode) {
        return mode == LockModeType.PESSIMISTIC_READ || mode == LockModeType.PESSIMISTIC_WRITE
            || mode == LockModeType.PESSIMISTIC_FORCE_INCREMENT;
    }

    /** Whether the mode increments the version at flush, changed or not (§3.5.5, §3.5.6). */
    public static boolean forcesIncrement(LockModeType mode) {
        return mode == LockModeType.OPTIMISTIC_FORCE_INCREMENT || mode == LockModeType.WRITE
            || mode == LockModeType.PESSIMISTIC_FORCE_INCREMENT;
    }

    /** The row lock of a pessimistic mode. */
    public static Select.Lock rowLock(LockModeType mode) {
        return mode == LockModeType.PESSIMISTIC_READ ? Select.Lock.SHARED : Select.Lock.EXCLUSIVE;
    }

    /**
     * Locks the managed instance of {@code entry} in mode {@code mode}.
     *
     * @param timeout the lock timeout in milliseconds, or {@code null} for the database's
     * @param checkVersion whether a row at another version than the instance is an {@link OptimisticLockException}
     *        (a refresh, which rereads the row anyway, does not check)
     */
    public void lock(MappedEntity type, ManagedEntity entry, LockModeType mode, Integer timeout, boolean checkVersion,
            Connection connection, PersistenceContext context) {
        if (mode == null || mode == LockModeType.NONE) {
            return;
        }
        boolean versioned = type.model().version().isPresent();
        if (!pessimistic(mode)) {
            if (!versioned) {
                throw new PersistenceException("The optimistic lock mode " + mode + " needs a versioned entity, and "
                    + type.model().entityName() + " has no @Version (§3.5.5)");
            }
        } else {
            Object version = lockRow(type, entry.key().id(), mode, timeout, connection, entry.instance());
            if (checkVersion && versioned) {
                Object expected = entry.snapshot()[type.model().attributes().indexOf(type.model().version().get())];
                if (!Objects.equals(expected, version)) {
                    throw new OptimisticLockException("The row of " + type.model().entityName() + " " + entry.key().id()
                        + " is at version " + version + ", the instance at version " + expected, null, entry.instance());
                }
            }
        }
        context.lock(entry, mode);
    }

    /** Locks the row of {@code id} and returns its version (or key); {@link EntityNotFoundException} if it is gone. */
    private Object lockRow(MappedEntity type, Object id, LockModeType mode, Integer timeout, Connection connection,
            Object instance) {
        EntityStatements statements = type.statements();
        Dialect dialect = engine.dialect();
        String sql = dialect.render(statements.versionSelect().locked(rowLock(mode), timeout != null && timeout == 0));
        try {
            timeout(dialect, timeout, connection);
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                bindKey(statements, id, select);
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next()) {
                        throw new EntityNotFoundException("The row of " + type.model().entityName() + " " + id + " no longer exists");
                    }
                    return versionBinder(statements).read(row, 1);
                }
            }
        } catch (SQLException e) {
            throw failure(dialect, e, instance);
        }
    }

    /** §3.5.5 OPTIMISTIC: the row must still have the version the instance was read at. */
    void checkVersion(MappedEntity type, ManagedEntity entry, Connection connection) {
        EntityStatements statements = type.statements();
        Object expected = entry.snapshot()[type.model().attributes().indexOf(type.model().version().orElseThrow())];
        try (PreparedStatement select = connection.prepareStatement(engine.dialect().render(statements.versionSelect()))) {
            bindKey(statements, entry.key().id(), select);
            try (ResultSet row = select.executeQuery()) {
                Object version = row.next() ? versionBinder(statements).read(row, 1) : null;
                if (!Objects.equals(expected, version)) {
                    throw new OptimisticLockException("The row of " + type.model().entityName() + " " + entry.key().id()
                        + " changed since it was read (version " + expected + ", now " + version + ")", null, entry.instance());
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("The version check of " + type.model().entityName() + " failed: " + e.getMessage(), e);
        }
    }

    /** Limits the wait for locks for the rest of the transaction, where the dialect can scope it so. */
    public static void timeout(Dialect dialect, Integer timeout, Connection connection) throws SQLException {
        if (timeout != null && timeout > 0) {
            String sql = dialect.lockTimeout(timeout);
            if (sql != null) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
        }
    }

    /** §3.12: a lock not obtained in time is a LockTimeoutException, a deadlock a PessimisticLockException. */
    public static PersistenceException failure(Dialect dialect, SQLException e, Object instance) {
        return switch (dialect.lockFailure(e)) {
            case TIMEOUT -> new LockTimeoutException("The lock was not obtained in time: " + e.getMessage(), e, instance);
            case PESSIMISTIC -> new PessimisticLockException("The lock failed: " + e.getMessage(), e, instance);
            case NONE -> new PersistenceException("The lock failed: " + e.getMessage(), e);
        };
    }

    private void bindKey(EntityStatements statements, Object id, PreparedStatement select) throws SQLException {
        Object[] key = statements.keyValues(id);
        List<EntityStatements.Parameter> parameters = statements.selectParameters();
        for (int i = 0; i < parameters.size(); i++) {
            statements.columns().get(parameters.get(i).column()).binder().bind(engine.dialect(), select, i + 1, key[i]);
        }
    }

    private static ValueBinder versionBinder(EntityStatements statements) {
        int column = statements.version().orElseGet(() -> statements.selectParameters().getFirst().column());
        return statements.columns().get(column).binder();
    }
}
