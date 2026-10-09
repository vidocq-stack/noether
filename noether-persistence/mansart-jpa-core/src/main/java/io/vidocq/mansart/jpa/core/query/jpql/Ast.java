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
package io.vidocq.mansart.jpa.core.query.jpql;

import java.util.List;

/**
 * The syntax tree of the Jakarta Persistence query language (§4), as {@link Parser} reads it: names as written, nothing
 * resolved against the entity model yet. Identification variables are case-insensitive (§4.4.2): they are compared
 * ignoring case when translated.
 */
public final class Ast {

    private Ast() {
    }

    /** A statement of the language. */
    public sealed interface Statement permits Select, Update, Delete {
    }

    /** The identification variable of a statement that declares none (3.2, §4.4.2). */
    public static final String IMPLICIT_VARIABLE = "this";

    /** {@code UPDATE entity [[AS] variable] SET path = value, … [WHERE …]} (§4.10). */
    public record Update(String entity, String variable, List<Assignment> assignments, Expr where) implements Statement {
        public Update {
            assignments = List.copyOf(assignments);
        }
    }

    /** {@code path = value} in a {@code SET} clause; {@code value} a {@code NULL} literal to clear it. */
    public record Assignment(Path path, Expr value) {
    }

    /** {@code DELETE FROM entity [[AS] variable] [WHERE …]} (§4.10). */
    public record Delete(String entity, String variable, Expr where) implements Statement {
    }

    /** {@code SELECT … FROM … [WHERE …] [GROUP BY … [HAVING …]] [ORDER BY …]} (§4.2), also a subquery. */
    public record Select(boolean distinct, List<Item> items, List<Range> from, Expr where, List<Expr> groupBy, Expr having,
            List<OrderItem> orderBy) implements Statement {
        public Select {
            items = List.copyOf(items);
            from = List.copyOf(from);
            groupBy = List.copyOf(groupBy);
            orderBy = List.copyOf(orderBy);
        }
    }

    /** A select item and its result variable, or {@code null}. */
    public record Item(Expr expression, String alias) {
    }

    /**
     * A declaration of the {@code FROM} clause: an entity and its identification variable (§4.4.3), or, when
     * {@code entity} is {@code null}, the members of a collection ({@code IN(path) var}, §4.4.6), with its joins.
     */
    public record Range(String entity, Path collection, String variable, List<Join> joins) {
        public Range {
            joins = List.copyOf(joins);
        }
    }

    /** {@code [LEFT] JOIN [FETCH] path [var] [ON condition]} (§4.4.5); {@code variable} is {@code null} for a fetch join without one. */
    public record Join(Kind kind, boolean fetch, Path path, String variable, Expr on) {
        public enum Kind {
            INNER, LEFT
        }
    }

    /** A sort key (§4.9); {@code nullsFirst} {@code null} when the query does not say where nulls go. */
    public record OrderItem(Expr expression, boolean descending, Boolean nullsFirst) {
    }

    /** An expression, a condition included. */
    public sealed interface Expr {
    }

    /** {@code var.attribute.…}, or a single name: a variable, a result variable, or the dotted name of an enum constant. */
    public record Path(List<String> segments) implements Expr {
        public Path {
            segments = List.copyOf(segments);
        }
    }

    /** {@code :name} ({@code position} 0) or {@code ?position} ({@code name} null). */
    public record Parameter(String name, int position) implements Expr {
    }

    /** A string, a number, a boolean, or {@code null}. */
    public record Literal(Object value) implements Expr {
    }

    /** The binary operators of the language. */
    public enum Op {
        OR, AND, EQ, NE, LT, LE, GT, GE, PLUS, MINUS, TIMES, DIVIDE, CONCAT
    }

    public record Binary(Expr left, Op op, Expr right) implements Expr {
    }

    public record Not(Expr operand) implements Expr {
    }

    /** Unary minus. */
    public record Negate(Expr operand) implements Expr {
    }

    public record IsNull(Expr operand, boolean negated) implements Expr {
    }

    /** {@code path IS [NOT] EMPTY} (§4.6.12). */
    public record IsEmpty(Expr collection, boolean negated) implements Expr {
    }

    /** {@code value [NOT] MEMBER [OF] path} (§4.6.13). */
    public record MemberOf(Expr value, Expr collection, boolean negated) implements Expr {
    }

    public record Between(Expr operand, Expr low, Expr high, boolean negated) implements Expr {
    }

    /** {@code operand [NOT] LIKE pattern [ESCAPE escape]}; {@code escape} may be {@code null}. */
    public record Like(Expr operand, Expr pattern, Expr escape, boolean negated) implements Expr {
    }

    /** {@code operand [NOT] IN (values)}: literals and parameters, a single collection-valued parameter, or a subquery. */
    public record In(Expr operand, List<Expr> values, boolean negated) implements Expr {
        public In {
            values = List.copyOf(values);
        }
    }

    /** {@code COUNT}, {@code SUM}, {@code AVG}, {@code MIN}, {@code MAX} (§4.8.5). */
    public record Aggregate(String function, boolean distinct, Expr argument) implements Expr {
    }

    /** A function call, its name upper-cased: {@code UPPER(e.name)}, {@code SIZE(e.projects)}… */
    public record Function(String name, List<Expr> arguments) implements Expr {
        public Function {
            arguments = List.copyOf(arguments);
        }
    }

    /** {@code CASE [operand] WHEN … THEN … [ELSE otherwise] END} (§4.6.17.4); {@code operand} {@code null} for a general case. */
    public record Case(Expr operand, List<When> whens, Expr otherwise) implements Expr {
        public Case {
            whens = List.copyOf(whens);
        }
    }

    /** {@code WHEN when THEN then}: a condition, or a value compared with the operand of a simple case. */
    public record When(Expr when, Expr then) {
    }

    /** {@code NEW className(arguments)} (§4.8.2). */
    public record Constructor(String className, List<Expr> arguments) implements Expr {
        public Constructor {
            arguments = List.copyOf(arguments);
        }
    }

    /** A subquery used as an expression (§4.5.10). */
    public record Subquery(Select select) implements Expr {
    }

    /** {@code [NOT] EXISTS (subquery)}. */
    public record Exists(Select select, boolean negated) implements Expr {
    }
}
