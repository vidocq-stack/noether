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
package io.vidocq.mansart.jpa.dialect.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A query: {@code SELECT [DISTINCT] select FROM from [WHERE where] [GROUP BY groupBy [HAVING having]] [ORDER BY orderBy]}
 * then its paging. Its {@link #parameters()} are listed in their logical order — select items, join conditions,
 * {@code WHERE}, {@code GROUP BY}, {@code HAVING}, {@code ORDER BY}; the order to bind them is the one the dialect
 * renders ({@code Dialect.renderQuery}), which may move or repeat them.
 *
 * @param offset the rows skipped, or {@code null}
 * @param limit the most rows read, or {@code null}
 * @param lock the row lock the query takes (§3.5.6), {@link Select.Lock#NONE} for a plain read
 * @param noWait whether the lock must not wait
 */
public record Query(boolean distinct, List<Expression> select, List<From> from, Expression where, List<Expression> groupBy,
        Expression having, List<Order> orderBy, Integer offset, Integer limit, Select.Lock lock, boolean noWait) implements Statement {

    /** A table of the {@code FROM} clause, its alias, and the tables joined to it. */
    public record From(Table table, String alias, List<Join> joins) {
        public From {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(alias, "alias");
            joins = List.copyOf(joins);
        }
    }

    /** {@code [INNER|LEFT] JOIN table alias ON on}. */
    public record Join(Kind kind, Table table, String alias, Expression on) {
        public enum Kind {
            INNER, LEFT
        }
    }

    /** A sort key; {@code nullsFirst} null to leave nulls where the database puts them. */
    public record Order(Expression expression, boolean descending, Boolean nullsFirst) {
    }

    public Query {
        select = List.copyOf(select);
        from = List.copyOf(from);
        groupBy = List.copyOf(groupBy);
        orderBy = List.copyOf(orderBy);
        if (select.isEmpty() || from.isEmpty()) {
            throw new IllegalArgumentException("A query selects something from something");
        }
        lock = lock == null ? Select.Lock.NONE : lock;
    }

    /** A query that takes no lock. */
    public Query(boolean distinct, List<Expression> select, List<From> from, Expression where, List<Expression> groupBy,
            Expression having, List<Order> orderBy, Integer offset, Integer limit) {
        this(distinct, select, from, where, groupBy, having, orderBy, offset, limit, Select.Lock.NONE, false);
    }

    /** The same query, taking {@code lock} on the rows it reads. */
    public Query locked(Select.Lock lock, boolean noWait) {
        return new Query(distinct, select, from, where, groupBy, having, orderBy, offset, limit, lock, noWait);
    }

    /** The first table of the {@code FROM} clause. */
    @Override
    public Table table() {
        return from.getFirst().table();
    }

    /** The parameters, in the order of the rendered SQL. */
    public List<Expression.Parameter> parameters() {
        List<Expression.Parameter> parameters = new ArrayList<>();
        select.forEach(e -> collect(e, parameters));
        for (From source : from) {
            source.joins().forEach(j -> collect(j.on(), parameters));
        }
        collect(where, parameters);
        groupBy.forEach(e -> collect(e, parameters));
        collect(having, parameters);
        orderBy.forEach(o -> collect(o.expression(), parameters));
        return parameters;
    }

    /** The parameters of {@code expressions} ({@code null} ones skipped), in their order. */
    static List<Expression.Parameter> parametersOf(List<Expression> expressions) {
        List<Expression.Parameter> parameters = new ArrayList<>();
        expressions.forEach(e -> collect(e, parameters));
        return parameters;
    }

    private static void collect(Expression expression, List<Expression.Parameter> parameters) {
        switch (expression) {
            case null -> {
            }
            case Expression.Parameter parameter -> parameters.add(parameter);
            case Expression.Column _, Expression.Literal _ -> {
            }
            case Expression.Binary binary -> {
                collect(binary.left(), parameters);
                collect(binary.right(), parameters);
            }
            case Expression.Not not -> collect(not.operand(), parameters);
            case Expression.IsNull isNull -> collect(isNull.operand(), parameters);
            case Expression.Between between -> {
                collect(between.operand(), parameters);
                collect(between.low(), parameters);
                collect(between.high(), parameters);
            }
            case Expression.Like like -> {
                collect(like.operand(), parameters);
                collect(like.pattern(), parameters);
                collect(like.escape(), parameters);
            }
            case Expression.In in -> {
                collect(in.operand(), parameters);
                in.values().forEach(v -> collect(v, parameters));
            }
            case Expression.Aggregate aggregate -> collect(aggregate.argument(), parameters);
            case Expression.Function function -> function.arguments().forEach(a -> collect(a, parameters));
            case Expression.Case kase -> {
                collect(kase.operand(), parameters);
                for (Expression.When when : kase.whens()) {
                    collect(when.when(), parameters);
                    collect(when.then(), parameters);
                }
                collect(kase.otherwise(), parameters);
            }
            case Expression.Subquery subquery -> parameters.addAll(subquery.query().parameters());
            case Expression.Exists exists -> parameters.addAll(exists.query().parameters());
            case Expression.InQuery in -> {
                collect(in.operand(), parameters);
                parameters.addAll(in.query().parameters());
            }
            case Expression.Quantified quantified -> parameters.addAll(quantified.query().parameters());
        }
    }

    /** A query reading from {@code from}, to complete with {@link Builder}. */
    public static Builder from(From... from) {
        return new Builder(List.of(from));
    }

    /** Builds a {@link Query} clause by clause. */
    public static final class Builder {
        private final List<From> from;
        private boolean distinct;
        private List<Expression> select = List.of();
        private Expression where;
        private List<Expression> groupBy = List.of();
        private Expression having;
        private List<Order> orderBy = List.of();
        private Integer offset;
        private Integer limit;

        private Builder(List<From> from) {
            this.from = from;
        }

        public Builder distinct() {
            distinct = true;
            return this;
        }

        public Builder select(Expression... items) {
            select = List.of(items);
            return this;
        }

        public Builder where(Expression condition) {
            where = condition;
            return this;
        }

        public Builder groupBy(Expression... keys) {
            groupBy = List.of(keys);
            return this;
        }

        public Builder having(Expression condition) {
            having = condition;
            return this;
        }

        public Builder orderBy(Order... keys) {
            orderBy = List.of(keys);
            return this;
        }

        public Builder offset(int rows) {
            offset = rows;
            return this;
        }

        public Builder limit(int rows) {
            limit = rows;
            return this;
        }

        public Query build() {
            return new Query(distinct, select, from, where, groupBy, having, orderBy, offset, limit);
        }
    }
}
