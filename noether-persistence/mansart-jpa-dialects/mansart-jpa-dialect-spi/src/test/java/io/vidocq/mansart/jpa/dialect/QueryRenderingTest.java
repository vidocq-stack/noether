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

import static org.assertj.core.api.Assertions.assertThat;

import io.vidocq.mansart.jpa.dialect.sql.Expression;
import io.vidocq.mansart.jpa.dialect.sql.Expression.Binary;
import io.vidocq.mansart.jpa.dialect.sql.Expression.Column;
import io.vidocq.mansart.jpa.dialect.sql.Expression.Literal;
import io.vidocq.mansart.jpa.dialect.sql.Expression.Operator;
import io.vidocq.mansart.jpa.dialect.sql.Expression.Parameter;
import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import io.vidocq.mansart.jpa.dialect.sql.Query;
import io.vidocq.mansart.jpa.dialect.sql.Query.From;
import io.vidocq.mansart.jpa.dialect.sql.Query.Join;
import io.vidocq.mansart.jpa.dialect.sql.Query.Order;
import io.vidocq.mansart.jpa.dialect.sql.Table;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The ANSI rendering of queries; their parameters are listed in the order the SQL holds them. */
class QueryRenderingTest {

    private final Dialect ansi = new StandardDialect() {
        @Override
        public String name() {
            return "ansi";
        }
    };

    private static Column column(String alias, String name) {
        return new Column(alias, Identifier.of(name));
    }

    private static Query.Builder from(String table, String alias) {
        return Query.from(new From(Table.of(table), alias, List.of()));
    }

    @Test
    void aSelectNamesItsColumnsByTheAliasOfTheirTable() {
        Query query = from("EMP", "e").select(column("e", "ID"), column("e", "NAME")).build();
        assertThat(ansi.render(query)).isEqualTo("SELECT e.ID, e.NAME FROM EMP e");
    }

    @Test
    void predicatesAreParenthesisedByPrecedenceOnly() {
        Parameter name = new Parameter("name");
        Expression where = new Binary(new Binary(new Binary(column("e", "NAME"), Operator.EQ, name), Operator.OR,
            new Expression.IsNull(column("e", "NAME"), false)), Operator.AND,
            new Expression.Not(new Binary(column("e", "AGE"), Operator.GT, new Literal(30))));
        Query query = from("EMP", "e").select(column("e", "ID")).where(where).build();
        assertThat(ansi.render(query))
            .isEqualTo("SELECT e.ID FROM EMP e WHERE (e.NAME = ? OR e.NAME IS NULL) AND NOT e.AGE > 30");
        assertThat(query.parameters()).containsExactly(name);
    }

    @Test
    void literalsAreRenderedInlineAndStringsEscaped() {
        Expression where = new Binary(new Binary(column("e", "NAME"), Operator.EQ, new Literal("O'Hara")), Operator.AND,
            new Binary(column("e", "ACTIVE"), Operator.EQ, new Literal(true)));
        assertThat(ansi.render(from("EMP", "e").select(column("e", "ID")).where(where).build()))
            .isEqualTo("SELECT e.ID FROM EMP e WHERE e.NAME = 'O''Hara' AND e.ACTIVE = TRUE");
    }

    @Test
    void joinsDistinctOrderingAndPaging() {
        Query query = Query.from(new From(Table.of("EMP"), "e", List.of(
                new Join(Join.Kind.INNER, Table.of("DEPT"), "d", new Binary(column("e", "DEPT_ID"), Operator.EQ, column("d", "ID"))),
                new Join(Join.Kind.LEFT, Table.of("EMP"), "m", new Binary(column("e", "MGR"), Operator.EQ, column("m", "ID"))))))
            .distinct().select(column("d", "NAME")).orderBy(new Order(column("d", "NAME"), true)).offset(10).limit(5).build();
        assertThat(ansi.render(query)).isEqualTo("SELECT DISTINCT d.NAME FROM EMP e INNER JOIN DEPT d ON e.DEPT_ID = d.ID "
            + "LEFT JOIN EMP m ON e.MGR = m.ID ORDER BY d.NAME DESC OFFSET 10 ROWS FETCH FIRST 5 ROWS ONLY");
    }

    @Test
    void predicatesOfTheQueryLanguage() {
        Parameter low = new Parameter(1);
        Parameter high = new Parameter(2);
        Parameter pattern = new Parameter(3);
        Expression where = new Binary(new Binary(
                new Expression.Between(column("e", "AGE"), low, high, true), Operator.AND,
                new Expression.Like(column("e", "NAME"), pattern, new Literal("\\\\"), false)), Operator.AND,
            new Expression.In(column("e", "ID"), List.of(new Literal(1), new Literal(2)), false));
        Query query = from("EMP", "e").select(new Expression.Aggregate("COUNT", false, null)).where(where).build();
        assertThat(ansi.render(query)).isEqualTo("SELECT COUNT(*) FROM EMP e WHERE e.AGE NOT BETWEEN ? AND ? "
            + "AND e.NAME LIKE ? ESCAPE '\\\\' AND e.ID IN (1, 2)");
        assertThat(query.parameters()).containsExactly(low, high, pattern);
    }

    @Test
    void aggregatesAndGrouping() {
        Query query = from("EMP", "e").select(column("e", "DEPT_ID"), new Expression.Aggregate("SUM", true, column("e", "SALARY")))
            .groupBy(column("e", "DEPT_ID")).having(new Binary(new Expression.Aggregate("COUNT", false, column("e", "ID")),
                Operator.GT, new Literal(1))).build();
        assertThat(ansi.render(query)).isEqualTo("SELECT e.DEPT_ID, SUM(DISTINCT e.SALARY) FROM EMP e GROUP BY e.DEPT_ID "
            + "HAVING COUNT(e.ID) > 1");
    }

    @Test
    void arithmeticKeepsTheGroupingOfItsOperands() { // a - (b - c), (a + b) * c
        Expression minus = new Binary(column("e", "A"), Operator.MINUS, new Binary(column("e", "B"), Operator.MINUS, column("e", "C")));
        Expression times = new Binary(new Binary(column("e", "A"), Operator.PLUS, column("e", "B")), Operator.TIMES, column("e", "C"));
        assertThat(ansi.render(from("T", "e").select(minus, times).build())).isEqualTo("SELECT e.A - (e.B - e.C), (e.A + e.B) * e.C FROM T e");
    }
}
