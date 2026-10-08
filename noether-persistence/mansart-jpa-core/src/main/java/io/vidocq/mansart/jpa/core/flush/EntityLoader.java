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

import io.vidocq.mansart.jpa.core.context.PersistenceContext;
import io.vidocq.mansart.jpa.core.mapping.CompositeId;
import io.vidocq.mansart.jpa.core.mapping.EntityStatements;
import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * Builds managed instances from their rows (§3.2.8): the select by identifier of an entity, its columns read through
 * their binders, the state rebuilt (embeddables included) and written through the access, then the instance made
 * managed with its snapshot. Shares the SQL the flush engine rendered.
 */
public final class EntityLoader {

    private final FlushEngine engine;

    public EntityLoader(FlushEngine engine) {
        this.engine = engine;
    }

    /**
     * The managed instance of {@code type} with identity {@code id}, read through {@code connection}; {@code null} if no
     * row has it. If the context already manages that identity, its instance is returned.
     */
    public Object load(MappedEntity type, Object id, Connection connection, PersistenceContext context) {
        return load(type, id, connection, context, LockModeType.NONE, null);
    }

    /**
     * Like {@link #load(MappedEntity, Object, Connection, PersistenceContext)}, the primary row locked in a pessimistic
     * {@code mode} (§3.5.6), waiting at most {@code timeout} milliseconds ({@code null}: the database's timeout).
     */
    public Object load(MappedEntity type, Object id, Connection connection, PersistenceContext context, LockModeType mode,
            Integer timeout) {
        Row row = row(type, id, connection, mode, timeout);
        if (row == null) {
            return null;
        }
        Object instance = type.access().instantiate();
        Object[] state = state(type, instance, row);
        if (!(id instanceof CompositeId)) {
            // the identifier object the application found it with, as other providers keep it (equal to the one read)
            int index = type.idAttributes()[0];
            state[index] = id;
            type.access().set(instance, index, id);
        }
        Object managed = context.loaded(instance, type, type.state().snapshot(state)).instance();
        if (managed == instance) {
            type.callback("PostLoad", instance); // §3.6.3: once the state is loaded
        }
        return managed;
    }

    /** Whether a row of {@code type} has the identity {@code id}, without managing anything. */
    public boolean exists(MappedEntity type, Object id, Connection connection) {
        return row(type, id, connection, LockModeType.NONE, null) != null;
    }

    /**
     * §3.2.5: overwrites the state of the managed {@code instance} with its row, and returns the new snapshot;
     * {@code null} if the row is gone.
     */
    public Object[] refresh(MappedEntity type, Object id, Object instance, Connection connection) {
        Row row = row(type, id, connection, LockModeType.NONE, null);
        if (row == null) {
            return null;
        }
        Object[] snapshot = type.state().snapshot(state(type, instance, row));
        type.callback("PostLoad", instance); // §3.6.3: after a refresh too
        return snapshot;
    }

    /** The columns of a row, and which were SQL {@code NULL} (a primitive reads 0 from it). */
    private record Row(Object[] values, boolean[] nulls) {
    }

    private Row row(MappedEntity type, Object id, Connection connection, LockModeType mode, Integer timeout) {
        EntityStatements statements = type.statements();
        List<EntityStatements.Column> columns = statements.columns();
        Object[] key = statements.keyValues(id);
        Object[] values = new Object[columns.size()];
        boolean[] nulls = new boolean[values.length];
        Arrays.fill(nulls, true); // a missing secondary row reads as NULL columns
        FlushEngine.Sql sql = engine.sql(type);
        try {
            for (int t = 0; t < statements.tables().size(); t++) {
                EntityStatements.TableStatements table = statements.tables().get(t);
                String text = sql.tables().get(t).select();
                if (t == 0 && Locks.pessimistic(mode)) {
                    Locks.timeout(engine.dialect(), timeout, connection);
                    text = engine.dialect().render(table.select().locked(Locks.rowLock(mode), timeout != null && timeout == 0));
                }
                try (PreparedStatement select = connection.prepareStatement(text)) {
                    List<EntityStatements.Parameter> parameters = table.selectParameters();
                    for (int i = 0; i < parameters.size(); i++) {
                        columns.get(parameters.get(i).column()).binder().bind(engine.dialect(), select, i + 1, key[i]);
                    }
                    try (ResultSet row = select.executeQuery()) {
                        if (!row.next()) {
                            if (t == 0) {
                                return null;
                            }
                            continue;
                        }
                        int[] selected = table.selected();
                        for (int i = 0; i < selected.length; i++) {
                            values[selected[i]] = columns.get(selected[i]).binder().read(row, i + 1);
                            nulls[selected[i]] = row.wasNull(); // the binder's last read is this column's
                        }
                    }
                }
            }
            return new Row(values, nulls);
        } catch (SQLException e) {
            if (Locks.pessimistic(mode)) {
                throw Locks.failure(engine.dialect(), e, null);
            }
            throw new PersistenceException("The read of " + type.model().entityName() + " " + id + " failed: " + e.getMessage(), e);
        }
    }

    /** Writes the row into {@code instance}; what the row does not hold keeps its current value. */
    private static Object[] state(MappedEntity type, Object instance, Row row) {
        Object[] state = new Object[type.model().attributes().size()];
        type.access().read(instance, state);
        type.statements().hydrate(row.values(), row.nulls(), state);
        type.access().write(instance, state);
        return state;
    }
}
