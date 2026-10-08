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

import io.vidocq.mansart.jpa.dialect.sql.Delete;
import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import io.vidocq.mansart.jpa.dialect.sql.Increment;
import io.vidocq.mansart.jpa.dialect.sql.Insert;
import io.vidocq.mansart.jpa.dialect.sql.NextValue;
import io.vidocq.mansart.jpa.dialect.sql.Select;
import io.vidocq.mansart.jpa.dialect.sql.Statement;
import io.vidocq.mansart.jpa.dialect.sql.Table;
import io.vidocq.mansart.jpa.dialect.sql.Update;
import java.util.Collections;
import java.util.List;

/**
 * The ANSI SQL rendering that dialects extend, overriding only what their database does differently. Quoted names are
 * delimited with double quotes, embedded quotes doubled.
 */
public abstract class StandardDialect implements Dialect {

    protected StandardDialect() {
    }

    @Override
    public String render(Statement statement) {
        return switch (statement) {
            case Insert insert -> insert(insert);
            case Update update -> update(update);
            case Delete delete -> "DELETE FROM " + table(delete.table()) + where(delete.conditions());
            case Select select -> "SELECT " + list(select.columns()) + " FROM " + table(select.table()) + where(select.conditions())
                + lock(select);
            case NextValue next -> nextValue(next);
            case Increment increment -> "UPDATE " + table(increment.table()) + " SET " + name(increment.value()) + " = "
                + name(increment.value()) + " + ? WHERE " + name(increment.key()) + " = ?";
        };
    }

    /** The row lock of a select: {@code FOR UPDATE}, {@code FOR SHARE}, and {@code NOWAIT} when it must not wait. */
    protected String lock(Select select) {
        String lock = switch (select.lock()) {
            case NONE -> "";
            case SHARED -> " FOR SHARE";
            case EXCLUSIVE -> " FOR UPDATE";
        };
        return lock.isEmpty() || !select.noWait() ? lock : lock + " NOWAIT";
    }

    /** SQL:2003 {@code NEXT VALUE FOR}, as a one-row query. */
    protected String nextValue(NextValue next) {
        return "VALUES NEXT VALUE FOR " + table(next.table());
    }

    protected String insert(Insert insert) {
        if (insert.columns().isEmpty()) {
            return "INSERT INTO " + table(insert.table()) + " DEFAULT VALUES";
        }
        return "INSERT INTO " + table(insert.table()) + " (" + list(insert.columns()) + ") VALUES ("
            + String.join(", ", Collections.nCopies(insert.columns().size(), "?")) + ")";
    }

    protected String update(Update update) {
        StringBuilder sql = new StringBuilder("UPDATE ").append(table(update.table())).append(" SET ");
        for (int i = 0; i < update.assignments().size(); i++) {
            sql.append(i == 0 ? "" : ", ").append(name(update.assignments().get(i))).append(" = ?");
        }
        return sql.append(where(update.conditions())).toString();
    }

    protected String where(List<Identifier> conditions) {
        StringBuilder sql = new StringBuilder(" WHERE ");
        for (int i = 0; i < conditions.size(); i++) {
            sql.append(i == 0 ? "" : " AND ").append(name(conditions.get(i))).append(" = ?");
        }
        return sql.toString();
    }

    protected String table(Table table) {
        StringBuilder sql = new StringBuilder();
        if (table.catalog() != null) {
            sql.append(name(table.catalog())).append('.');
        }
        if (table.schema() != null) {
            sql.append(name(table.schema())).append('.');
        }
        return sql.append(name(table.name())).toString();
    }

    protected String list(List<Identifier> columns) {
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append(name(columns.get(i)));
        }
        return sql.toString();
    }

    /** A name, delimited when quoted. */
    protected String name(Identifier identifier) {
        return identifier.quoted() ? '"' + identifier.name().replace("\"", "\"\"") + '"' : identifier.name();
    }
}
