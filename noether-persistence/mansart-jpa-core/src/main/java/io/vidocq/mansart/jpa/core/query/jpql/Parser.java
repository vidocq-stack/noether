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

import io.vidocq.mansart.jpa.core.query.jpql.Ast.Expr;
import io.vidocq.mansart.jpa.core.query.jpql.Lexer.Kind;
import io.vidocq.mansart.jpa.core.query.jpql.Lexer.Token;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A recursive-descent parser of the Jakarta Persistence query language (§4.14 BNF), to its {@link Ast}. Keywords are
 * recognised in any case; a syntax error is an {@link IllegalArgumentException} naming where it is (§3.11, the
 * exception {@code createQuery} throws). Not thread-safe: one parser per query.
 */
public final class Parser {

    /** The reserved identifiers that cannot be variables (§4.4.1), as far as the parser needs to tell them. */
    private static final Set<String> RESERVED = Set.of("SELECT", "FROM", "WHERE", "GROUP", "BY", "HAVING", "ORDER", "ASC", "DESC",
        "AS", "JOIN", "LEFT", "INNER", "OUTER", "FETCH", "ON", "AND", "OR", "NOT", "BETWEEN", "LIKE", "IN", "IS", "NULL", "EMPTY",
        "MEMBER", "OF", "ESCAPE", "DISTINCT", "EXISTS", "ALL", "ANY", "SOME", "UNION", "INTERSECT", "EXCEPT", "NULLS", "CASE",
        "WHEN", "THEN", "ELSE", "END", "NEW", "OBJECT", "TRUE", "FALSE", "UPDATE", "DELETE", "SET");

    private static final Set<String> AGGREGATES = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX");

    private final String query;
    private final List<Token> tokens;
    private int next;

    private Parser(String query) {
        this.query = query;
        this.tokens = Lexer.tokens(query);
    }

    /** The syntax tree of {@code query}. */
    public static Ast.Statement parse(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("A query needs a statement");
        }
        Parser parser = new Parser(query);
        Ast.Statement statement;
        if (parser.peek().is("UPDATE")) {
            statement = parser.update();
        } else if (parser.peek().is("DELETE")) {
            statement = parser.delete();
        } else {
            statement = parser.select();
        }
        parser.expectEnd();
        return statement;
    }

    /** {@code UPDATE entity [[AS] variable] SET path = value, … [WHERE condition]} (§4.10). */
    private Ast.Update update() {
        expect("UPDATE");
        String entity = entityName();
        String variable = peek().is("SET") ? null : alias();
        expect("SET");
        List<Ast.Assignment> assignments = new ArrayList<>();
        do {
            Ast.Path path = path();
            expect("=");
            assignments.add(new Ast.Assignment(path, expression()));
        } while (accept(","));
        Expr where = accept("WHERE") ? condition() : null;
        return new Ast.Update(entity, variable == null ? Ast.IMPLICIT_VARIABLE : variable, assignments, where);
    }

    /** {@code DELETE FROM entity [[AS] variable] [WHERE condition]} (§4.10). */
    private Ast.Delete delete() {
        expect("DELETE");
        expect("FROM");
        String entity = entityName();
        String variable = alias();
        Expr where = accept("WHERE") ? condition() : null;
        return new Ast.Delete(entity, variable == null ? Ast.IMPLICIT_VARIABLE : variable, where);
    }

    /** An entity name; a keyword may name an entity ({@code FROM Order o}). */
    private String entityName() {
        if (peek().kind() != Kind.NAME) {
            throw error("an entity name");
        }
        return advance().text();
    }

    // ---- statements ---------------------------------------------------------------------------------------

    private Ast.Select select() {
        expect("SELECT");
        boolean distinct = accept("DISTINCT");
        List<Ast.Item> items = new ArrayList<>();
        do {
            items.add(item());
        } while (accept(","));
        expect("FROM");
        List<Ast.Range> from = new ArrayList<>();
        do {
            from.add(range());
        } while (accept(","));
        Expr where = accept("WHERE") ? condition() : null;
        List<Expr> groupBy = new ArrayList<>();
        if (accept("GROUP")) {
            expect("BY");
            do {
                groupBy.add(expression());
            } while (accept(","));
        }
        Expr having = accept("HAVING") ? condition() : null;
        List<Ast.OrderItem> orderBy = new ArrayList<>();
        if (accept("ORDER")) {
            expect("BY");
            do {
                Expr key = expression();
                boolean descending = accept("DESC");
                if (!descending) {
                    accept("ASC");
                }
                Boolean nullsFirst = null;
                if (accept("NULLS")) {
                    nullsFirst = accept("FIRST");
                    if (!nullsFirst) {
                        expect("LAST");
                    }
                }
                orderBy.add(new Ast.OrderItem(key, descending, nullsFirst));
            } while (accept(","));
        }
        return new Ast.Select(distinct, items, from, where, groupBy, having, orderBy);
    }

    private Ast.Item item() {
        Expr expression;
        if (peek().is("OBJECT") && peek(1).is("(")) { // §4.8: OBJECT(e) is e
            advance();
            advance();
            expression = new Ast.Path(List.of(name("an identification variable")));
            expect(")");
        } else {
            expression = expression();
        }
        return new Ast.Item(expression, alias());
    }

    /** An optional {@code [AS] name}. */
    private String alias() {
        if (accept("AS")) {
            return name("a variable");
        }
        Token token = peek();
        return token.kind() == Kind.NAME && !reserved(token) ? advance().text() : null;
    }

    private Ast.Range range() {
        if (accept("IN")) { // §4.4.6: IN(path) var, the members of a collection
            expect("(");
            Ast.Path collection = path();
            expect(")");
            String variable = alias();
            if (variable == null) {
                throw error("a variable for the collection members");
            }
            return new Ast.Range(null, collection, variable, joins());
        }
        if (peek().kind() == Kind.NAME && peek(1).is(".")) { // a subquery: FROM e.projects p (§4.5.10)
            Ast.Path collection = path();
            String variable = alias();
            if (variable == null) {
                throw error("a variable for " + String.join(".", collection.segments()));
            }
            return new Ast.Range(null, collection, variable, joins());
        }
        String entity = entityName();
        String variable = alias(); // 3.2: without one, the implicit variable this
        return new Ast.Range(entity, null, variable == null ? Ast.IMPLICIT_VARIABLE : variable, joins());
    }

    private List<Ast.Join> joins() {
        List<Ast.Join> joins = new ArrayList<>();
        while (true) {
            Ast.Join.Kind kind;
            if (accept("LEFT")) {
                accept("OUTER");
                kind = Ast.Join.Kind.LEFT;
            } else if (accept("INNER")) {
                kind = Ast.Join.Kind.INNER;
            } else if (peek().is("JOIN")) {
                kind = Ast.Join.Kind.INNER;
            } else {
                return joins;
            }
            expect("JOIN");
            boolean fetch = accept("FETCH");
            Ast.Path path = path();
            String variable = alias();
            Expr on = accept("ON") ? condition() : null;
            joins.add(new Ast.Join(kind, fetch, path, variable, on));
        }
    }

    // ---- conditions (§4.6) --------------------------------------------------------------------------------

    private Expr condition() {
        Expr left = conjunction();
        while (accept("OR")) {
            left = new Ast.Binary(left, Ast.Op.OR, conjunction());
        }
        return left;
    }

    private Expr conjunction() {
        Expr left = negation();
        while (accept("AND")) {
            left = new Ast.Binary(left, Ast.Op.AND, negation());
        }
        return left;
    }

    private Expr negation() {
        if (accept("NOT")) {
            return new Ast.Not(negation());
        }
        return predicate();
    }

    private Expr predicate() {
        if (peek().is("EXISTS")) {
            advance();
            return new Ast.Exists(subquery(), false);
        }
        Expr left = expression();
        boolean negated = peek().is("NOT") && (peek(1).is("BETWEEN") || peek(1).is("LIKE") || peek(1).is("IN") || peek(1).is("MEMBER"));
        if (negated) {
            advance();
        }
        if (accept("BETWEEN")) {
            Expr low = expression();
            expect("AND");
            return new Ast.Between(left, low, expression(), negated);
        }
        if (accept("LIKE")) {
            Expr pattern = expression();
            Expr escape = accept("ESCAPE") ? expression() : null;
            return new Ast.Like(left, pattern, escape, negated);
        }
        if (accept("IN")) {
            return in(left, negated);
        }
        if (accept("MEMBER")) {
            accept("OF");
            return new Ast.MemberOf(left, path(), negated);
        }
        if (accept("IS")) {
            boolean not = accept("NOT");
            if (accept("NULL")) {
                return new Ast.IsNull(left, not);
            }
            expect("EMPTY");
            return new Ast.IsEmpty(left, not);
        }
        Ast.Op comparison = comparison();
        if (comparison != null) {
            return new Ast.Binary(left, comparison, comparand());
        }
        return left;
    }

    /** The right operand of a comparison: an expression, or {@code ALL|ANY|SOME (subquery)} (§4.6.15). */
    private Expr comparand() {
        if ((peek().is("ALL") || peek().is("ANY") || peek().is("SOME")) && peek(1).is("(")) {
            String quantifier = advance().text().toUpperCase(Locale.ROOT);
            return new Ast.Function(quantifier.equals("SOME") ? "ANY" : quantifier, List.of(new Ast.Subquery(subquery())));
        }
        return expression();
    }

    private Expr in(Expr left, boolean negated) {
        if (peek().kind() == Kind.NAMED_PARAMETER || peek().kind() == Kind.POSITIONAL_PARAMETER) { // §4.6.9: IN :collection
            return new Ast.In(left, List.of(primary()), negated);
        }
        expect("(");
        if (peek().is("SELECT")) {
            Ast.Select subquery = select();
            expect(")");
            return new Ast.In(left, List.of(new Ast.Subquery(subquery)), negated);
        }
        List<Expr> values = new ArrayList<>();
        do {
            values.add(expression());
        } while (accept(","));
        expect(")");
        return new Ast.In(left, values, negated);
    }

    private Ast.Op comparison() {
        Token token = peek();
        if (token.kind() != Kind.SYMBOL) {
            return null;
        }
        Ast.Op op = switch (token.text()) {
            case "=" -> Ast.Op.EQ;
            case "<>", "!=" -> Ast.Op.NE;
            case "<" -> Ast.Op.LT;
            case "<=" -> Ast.Op.LE;
            case ">" -> Ast.Op.GT;
            case ">=" -> Ast.Op.GE;
            default -> null;
        };
        if (op != null) {
            advance();
        }
        return op;
    }

    private Ast.Select subquery() {
        expect("(");
        Ast.Select select = select();
        expect(")");
        return select;
    }

    // ---- expressions (§4.6.17) ----------------------------------------------------------------------------

    private Expr expression() {
        Expr left = term();
        while (true) {
            if (accept("+")) {
                left = new Ast.Binary(left, Ast.Op.PLUS, term());
            } else if (accept("-")) {
                left = new Ast.Binary(left, Ast.Op.MINUS, term());
            } else if (accept("||")) {
                left = new Ast.Binary(left, Ast.Op.CONCAT, term());
            } else {
                return left;
            }
        }
    }

    private Expr term() {
        Expr left = factor();
        while (true) {
            if (accept("*")) {
                left = new Ast.Binary(left, Ast.Op.TIMES, factor());
            } else if (accept("/")) {
                left = new Ast.Binary(left, Ast.Op.DIVIDE, factor());
            } else {
                return left;
            }
        }
    }

    private Expr factor() {
        if (accept("-")) {
            Expr operand = factor();
            return operand instanceof Ast.Literal(Number number) ? new Ast.Literal(negate(number)) : new Ast.Negate(operand);
        }
        accept("+");
        return primary();
    }

    private static Number negate(Number number) {
        return switch (number) {
            case Integer i -> -i;
            case Long l -> -l;
            case Float f -> -f;
            case Double d -> -d;
            case java.math.BigDecimal d -> d.negate();
            case java.math.BigInteger i -> i.negate();
            default -> throw new IllegalArgumentException("Not a numeric literal: " + number);
        };
    }

    private Expr primary() {
        Token token = peek();
        switch (token.kind()) {
            case STRING, NUMBER -> {
                advance();
                return new Ast.Literal(token.value());
            }
            case NAMED_PARAMETER -> {
                advance();
                return new Ast.Parameter((String) token.value(), 0);
            }
            case POSITIONAL_PARAMETER -> {
                advance();
                return new Ast.Parameter(null, (Integer) token.value());
            }
            case END -> throw error("an expression");
            default -> {
            }
        }
        if (accept("(")) {
            if (peek().is("SELECT")) {
                Ast.Select subquery = select();
                expect(")");
                return new Ast.Subquery(subquery);
            }
            Expr inner = condition();
            expect(")");
            return inner;
        }
        if (accept("{")) {
            return escape();
        }
        if (token.kind() != Kind.NAME) {
            throw error("an expression");
        }
        String word = token.text().toUpperCase(Locale.ROOT);
        switch (word) {
            case "TRUE", "FALSE" -> {
                advance();
                return new Ast.Literal(word.equals("TRUE"));
            }
            case "NULL" -> {
                advance();
                return new Ast.Literal(null);
            }
            case "CASE" -> {
                advance();
                return caseExpression();
            }
            case "NEW" -> {
                advance();
                return constructor();
            }
            case "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP" -> {
                if (!peek(1).is("(")) {
                    advance();
                    return new Ast.Function(word, List.of());
                }
            }
            case "LOCAL" -> { // 3.1: LOCAL DATE, LOCAL TIME, LOCAL DATETIME
                Token kind = peek(1);
                if (kind.is("DATE") || kind.is("TIME") || kind.is("DATETIME")) {
                    advance();
                    advance();
                    return new Ast.Function("LOCAL_" + kind.text().toUpperCase(Locale.ROOT), List.of());
                }
            }
            default -> {
            }
        }
        if (peek(1).is("(")) {
            advance();
            advance();
            if (word.equals("TRIM")) {
                return trim();
            }
            if (word.equals("EXTRACT")) {
                String field = name("a date or time field").toUpperCase(Locale.ROOT);
                expect("FROM");
                Expr from = expression();
                expect(")");
                return new Ast.Function("EXTRACT", List.of(new Ast.Literal(field), from));
            }
            if (AGGREGATES.contains(word)) {
                boolean distinct = accept("DISTINCT");
                Expr argument = word.equals("COUNT") && accept("*") ? null : expression();
                expect(")");
                return new Ast.Aggregate(word, distinct, argument);
            }
            List<Expr> arguments = new ArrayList<>();
            if (!accept(")")) {
                do {
                    arguments.add(condition());
                } while (accept(","));
                expect(")");
            }
            return new Ast.Function(word, arguments);
        }
        return path();
    }

    /** {@code TRIM([[LEADING|TRAILING|BOTH] [character] FROM] string)}: TRIM(specification, character or null, string). */
    private Expr trim() {
        String specification = "BOTH";
        Expr character = new Ast.Literal(null);
        boolean declared = false;
        for (String word : List.of("LEADING", "TRAILING", "BOTH")) {
            if (accept(word)) {
                specification = word;
                declared = true;
            }
        }
        Expr first = peek().is("FROM") ? null : expression();
        if (accept("FROM")) {
            if (first != null) {
                character = first;
            }
            first = expression();
        } else if (declared) {
            throw error("FROM");
        }
        expect(")");
        return new Ast.Function("TRIM", List.of(new Ast.Literal(specification), character, first));
    }

    private Expr caseExpression() {
        Expr operand = peek().is("WHEN") ? null : expression();
        List<Ast.When> whens = new ArrayList<>();
        while (accept("WHEN")) {
            Expr when = operand == null ? condition() : expression();
            expect("THEN");
            whens.add(new Ast.When(when, expression()));
        }
        if (whens.isEmpty()) {
            throw error("WHEN");
        }
        Expr otherwise = accept("ELSE") ? expression() : null;
        expect("END");
        return new Ast.Case(operand, whens, otherwise);
    }

    private Expr constructor() {
        StringBuilder className = new StringBuilder(name("a class name"));
        while (accept(".")) {
            className.append('.').append(advance().text());
        }
        expect("(");
        List<Expr> arguments = new ArrayList<>();
        do {
            arguments.add(expression());
        } while (accept(","));
        expect(")");
        return new Ast.Constructor(className.toString(), arguments);
    }

    /** A JDBC escape (§4.6.1): {@code {d 'yyyy-mm-dd'}}, {@code {t 'hh:mm:ss'}}, {@code {ts 'yyyy-mm-dd hh:mm:ss[.f…]'}}. */
    private Expr escape() {
        String kind = name("d, t or ts").toLowerCase(Locale.ROOT);
        Token value = advance();
        if (value.kind() != Kind.STRING) {
            throw error("a quoted date or time");
        }
        expect("}");
        String text = (String) value.value();
        try {
            return new Ast.Literal(switch (kind) {
                case "d" -> java.sql.Date.valueOf(text);
                case "t" -> java.sql.Time.valueOf(text);
                case "ts" -> java.sql.Timestamp.valueOf(text);
                default -> throw error("d, t or ts");
            });
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The literal {" + kind + " '" + text + "'} is not a valid date or time: " + query, e);
        }
    }

    private Ast.Path path() {
        List<String> segments = new ArrayList<>();
        segments.add(name("a path"));
        while (peek().is(".") && peek(1).kind() == Kind.NAME) {
            advance();
            segments.add(advance().text());
        }
        return new Ast.Path(segments);
    }

    // ---- tokens -------------------------------------------------------------------------------------------

    private Token peek() {
        return tokens.get(next);
    }

    private Token peek(int ahead) {
        return tokens.get(Math.min(next + ahead, tokens.size() - 1));
    }

    private Token advance() {
        Token token = tokens.get(next);
        if (token.kind() != Kind.END) {
            next++;
        }
        return token;
    }

    private boolean accept(String keywordOrSymbol) {
        if (peek().is(keywordOrSymbol)) {
            advance();
            return true;
        }
        return false;
    }

    private void expect(String keywordOrSymbol) {
        if (!accept(keywordOrSymbol)) {
            throw error(keywordOrSymbol);
        }
    }

    private String name(String what) {
        Token token = peek();
        if (token.kind() != Kind.NAME || reserved(token)) {
            throw error(what);
        }
        return advance().text();
    }

    private static boolean reserved(Token token) {
        return RESERVED.contains(token.text().toUpperCase(Locale.ROOT));
    }

    private void expectEnd() {
        if (peek().kind() != Kind.END) {
            throw error("the end of the query");
        }
    }

    private IllegalArgumentException error(String expected) {
        Token token = peek();
        String found = token.kind() == Kind.END ? "the end of the query" : "'" + token.text() + "' at " + token.position();
        return new IllegalArgumentException("Expected " + expected + " but found " + found + " in: " + query);
    }
}
