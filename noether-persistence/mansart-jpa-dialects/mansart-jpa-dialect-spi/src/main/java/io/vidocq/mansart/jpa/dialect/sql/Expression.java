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

import java.util.List;
import java.util.Objects;

/**
 * An expression of a {@link Query}: what a select item, a join condition, a {@code WHERE} or a sort key is made of.
 * Its parameters are {@link Parameter}s whose slots the provider binds, in the order {@link Query#parameters()} lists.
 */
public sealed interface Expression {

    /** The column {@code name} of the table whose alias in the query is {@code alias}. */
    record Column(String alias, Identifier name) implements Expression {
        public Column {
            Objects.requireNonNull(alias, "alias");
            Objects.requireNonNull(name, "name");
        }
    }

    /** A {@code ?}, bound by the provider with what {@code slot} stands for (a name, a position, a value…). */
    record Parameter(Object slot) implements Expression {
    }

    /** A value written in the SQL: {@code NULL}, a boolean, a number, or a string (quotes doubled). */
    record Literal(Object value) implements Expression {
        public Literal {
            if (value != null && !(value instanceof Boolean || value instanceof Number || value instanceof String)) {
                throw new IllegalArgumentException("A literal is a boolean, a number or a string, not " + value.getClass().getName());
            }
        }
    }

    /** The binary operators, from the loosest binding to the tightest. */
    enum Operator {
        OR("OR", 1), AND("AND", 2),
        EQ("=", 4), NE("<>", 4), LT("<", 4), LE("<=", 4), GT(">", 4), GE(">=", 4),
        PLUS("+", 5), MINUS("-", 5), CONCAT("||", 5), TIMES("*", 6), DIVIDE("/", 6);

        private final String symbol;
        private final int precedence;

        Operator(String symbol, int precedence) {
            this.symbol = symbol;
            this.precedence = precedence;
        }

        public String symbol() {
            return symbol;
        }

        /** How tightly it binds: an operand that binds more loosely is parenthesised. */
        public int precedence() {
            return precedence;
        }
    }

    /** {@code left operator right}. */
    record Binary(Expression left, Operator operator, Expression right) implements Expression {
    }

    /** {@code CAST(expression AS type)}. */
    record Cast(Expression expression, Type type) implements Expression {
        public enum Type {
            VARCHAR, INTEGER, BIGINT, REAL, DOUBLE_PRECISION, DECIMAL
        }
    }

    /** {@code NOT operand}. */
    record Not(Expression operand) implements Expression {
    }

    /** {@code operand IS [NOT] NULL}. */
    record IsNull(Expression operand, boolean negated) implements Expression {
    }

    /** {@code operand [NOT] BETWEEN low AND high}. */
    record Between(Expression operand, Expression low, Expression high, boolean negated) implements Expression {
    }

    /** {@code operand [NOT] LIKE pattern [ESCAPE escape]}; {@code escape} may be {@code null}. */
    record Like(Expression operand, Expression pattern, Expression escape, boolean negated) implements Expression {
    }

    /** {@code operand [NOT] IN (values)}. */
    record In(Expression operand, List<Expression> values, boolean negated) implements Expression {
        public In {
            values = List.copyOf(values);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("IN needs at least one value");
            }
        }
    }

    /** An aggregate function — {@code COUNT}, {@code SUM}, {@code AVG}, {@code MIN}, {@code MAX} — of {@code argument}, {@code *} if null. */
    record Aggregate(String function, boolean distinct, Expression argument) implements Expression {
    }

    /**
     * A function of the query language by its name there (§4.6.17.2: {@code SUBSTRING}, {@code TRIM}, {@code LOCATE},
     * {@code EXTRACT}, {@code CURRENT_DATE}, {@code COALESCE}…), which the dialect writes in the SQL of its database.
     * The first argument of {@code TRIM} (the trim specification) and of {@code EXTRACT} (the field) is a keyword
     * literal, written as the word; the second of {@code TRIM}, the character, is a {@code NULL} literal when absent.
     */
    record Function(String name, List<Expression> arguments) implements Expression {
        public Function {
            Objects.requireNonNull(name, "name");
            arguments = List.copyOf(arguments);
        }

        /** The indexes of the arguments that are keywords, written as words rather than literals. */
        public java.util.Set<Integer> keywords() {
            return name.equals("TRIM") || name.equals("EXTRACT") ? java.util.Set.of(0) : java.util.Set.of();
        }
    }

    /** {@code CASE [operand] WHEN … THEN … [ELSE otherwise] END}; {@code operand} null for a searched case. */
    record Case(Expression operand, List<When> whens, Expression otherwise) implements Expression {
        public Case {
            whens = List.copyOf(whens);
        }
    }

    /** {@code WHEN when THEN then}. */
    record When(Expression when, Expression then) {
    }

    /** A scalar subquery. */
    record Subquery(SelectStatement query) implements Expression {
    }

    /** {@code [NOT] EXISTS (query)}. */
    record Exists(SelectStatement query, boolean negated) implements Expression {
    }

    /** {@code operand [NOT] IN (query)}. */
    record InQuery(Expression operand, SelectStatement query, boolean negated) implements Expression {
    }

    /** {@code ALL (query)} or {@code ANY (query)}, the right operand of a comparison. */
    record Quantified(String quantifier, SelectStatement query) implements Expression {
    }
}
