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
import io.vidocq.mansart.jpa.dialect.sql.Expression;
import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import io.vidocq.mansart.jpa.dialect.sql.Increment;
import io.vidocq.mansart.jpa.dialect.sql.Insert;
import io.vidocq.mansart.jpa.dialect.sql.NextValue;
import io.vidocq.mansart.jpa.dialect.sql.Query;
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
            case Query query -> query(query);
        };
    }

    // ---- queries ------------------------------------------------------------------------------------------

    /** A query, its parameters in the order of {@link Query#parameters()}. */
    protected String query(Query query) {
        StringBuilder sql = new StringBuilder("SELECT ");
        if (query.distinct()) {
            sql.append("DISTINCT ");
        }
        sql.append(expressions(query.select()));
        sql.append(" FROM ");
        for (int f = 0; f < query.from().size(); f++) {
            Query.From from = query.from().get(f);
            sql.append(f == 0 ? "" : ", ").append(table(from.table())).append(' ').append(from.alias());
            for (Query.Join join : from.joins()) {
                sql.append(join.kind() == Query.Join.Kind.LEFT ? " LEFT JOIN " : " INNER JOIN ").append(table(join.table())).append(' ')
                    .append(join.alias()).append(" ON ").append(expression(join.on()));
            }
        }
        if (query.where() != null) {
            sql.append(" WHERE ").append(expression(query.where()));
        }
        if (!query.groupBy().isEmpty()) {
            sql.append(" GROUP BY ").append(expressions(query.groupBy()));
        }
        if (query.having() != null) {
            sql.append(" HAVING ").append(expression(query.having()));
        }
        if (!query.orderBy().isEmpty()) {
            sql.append(" ORDER BY ");
            for (int o = 0; o < query.orderBy().size(); o++) {
                Query.Order order = query.orderBy().get(o);
                sql.append(o == 0 ? "" : ", ").append(expression(order.expression())).append(order.descending() ? " DESC" : "");
            }
        }
        return sql.append(paging(query)).toString();
    }

    /** SQL:2008 paging, which H2 and PostgreSQL understand: {@code OFFSET n ROWS FETCH FIRST m ROWS ONLY}. */
    protected String paging(Query query) {
        String paging = query.offset() == null || query.offset() == 0 ? "" : " OFFSET " + query.offset() + " ROWS";
        return query.limit() == null ? paging : paging + " FETCH FIRST " + query.limit() + " ROWS ONLY";
    }

    private String expressions(List<Expression> expressions) {
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < expressions.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append(expression(expressions.get(i)));
        }
        return sql.toString();
    }

    /** An expression standing alone. */
    protected String expression(Expression expression) {
        return expression(expression, 0);
    }

    /** The precedence of an expression: how tightly it binds (see {@link Expression.Operator#precedence()}). */
    private static int precedence(Expression expression) {
        return switch (expression) {
            case Expression.Binary binary -> binary.operator().precedence();
            case Expression.Not _ -> 3;
            case Expression.IsNull _, Expression.Between _, Expression.Like _, Expression.In _ -> 4;
            default -> 9;
        };
    }

    /** {@code expression}, parenthesised when it binds more loosely than {@code context}. */
    private String expression(Expression expression, int context) {
        String sql = switch (expression) {
            case Expression.Column column -> column.alias() + '.' + name(column.name());
            case Expression.Parameter _ -> "?";
            case Expression.Literal literal -> literal(literal.value());
            case Expression.Binary binary -> {
                int precedence = binary.operator().precedence();
                // a right operand of the same precedence is grouped as written: a - (b - c)
                yield expression(binary.left(), precedence) + ' ' + binary.operator().symbol() + ' '
                    + expression(binary.right(), precedence + 1);
            }
            case Expression.Not not -> "NOT " + expression(not.operand(), 4);
            case Expression.IsNull isNull -> expression(isNull.operand(), 5) + (isNull.negated() ? " IS NOT NULL" : " IS NULL");
            case Expression.Between between -> expression(between.operand(), 5) + (between.negated() ? " NOT" : "") + " BETWEEN "
                + expression(between.low(), 5) + " AND " + expression(between.high(), 5);
            case Expression.Like like -> expression(like.operand(), 5) + (like.negated() ? " NOT" : "") + " LIKE "
                + expression(like.pattern(), 5) + (like.escape() == null ? "" : " ESCAPE " + expression(like.escape(), 5));
            case Expression.In in -> expression(in.operand(), 5) + (in.negated() ? " NOT" : "") + " IN (" + expressions(in.values())
                + ")";
            case Expression.Aggregate aggregate -> aggregate.function() + '(' + (aggregate.distinct() ? "DISTINCT " : "")
                + (aggregate.argument() == null ? "*" : expression(aggregate.argument())) + ')';
        };
        return precedence(expression) < context ? '(' + sql + ')' : sql;
    }

    /** A literal: {@code NULL}, {@code TRUE} / {@code FALSE}, a number as written, a string quoted. */
    protected String literal(Object value) {
        return switch (value) {
            case null -> "NULL";
            case Boolean b -> b ? "TRUE" : "FALSE";
            case Number number -> number.toString();
            case String string -> '\'' + string.replace("'", "''") + '\'';
            default -> throw new IllegalArgumentException("Not a literal: " + value);
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
