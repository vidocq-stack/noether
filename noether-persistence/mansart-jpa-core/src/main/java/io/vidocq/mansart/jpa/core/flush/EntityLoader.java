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
import io.vidocq.mansart.jpa.core.mapping.EntityStatements;
import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
        Row row = row(type, id, connection);
        if (row == null) {
            return null;
        }
        Object instance = type.access().instantiate();
        Object[] state = state(type, instance, row);
        return context.loaded(instance, type, type.state().snapshot(state)).instance();
    }

    /** Whether a row of {@code type} has the identity {@code id}, without managing anything. */
    public boolean exists(MappedEntity type, Object id, Connection connection) {
        return row(type, id, connection) != null;
    }

    /**
     * §3.2.5: overwrites the state of the managed {@code instance} with its row, and returns the new snapshot;
     * {@code null} if the row is gone.
     */
    public Object[] refresh(MappedEntity type, Object id, Object instance, Connection connection) {
        Row row = row(type, id, connection);
        return row == null ? null : type.state().snapshot(state(type, instance, row));
    }

    /** The columns of a row, and which were SQL {@code NULL} (a primitive reads 0 from it). */
    private record Row(Object[] values, boolean[] nulls) {
    }

    private Row row(MappedEntity type, Object id, Connection connection) {
        EntityStatements statements = type.statements();
        List<EntityStatements.Column> columns = statements.columns();
        try (PreparedStatement select = connection.prepareStatement(engine.sql(type).select())) {
            Object[] key = statements.keyValues(id);
            List<EntityStatements.Parameter> parameters = statements.selectParameters();
            for (int i = 0; i < parameters.size(); i++) {
                columns.get(parameters.get(i).column()).binder().bind(select, i + 1, key[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                Object[] values = new Object[columns.size()];
                boolean[] nulls = new boolean[values.length];
                for (int c = 0; c < values.length; c++) {
                    values[c] = columns.get(c).binder().read(row, c + 1);
                    nulls[c] = row.wasNull(); // the binder's last read is this column's
                }
                return new Row(values, nulls);
            }
        } catch (SQLException e) {
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
