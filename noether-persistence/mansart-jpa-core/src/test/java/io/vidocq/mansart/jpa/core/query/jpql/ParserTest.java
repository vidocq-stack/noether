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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.jpa.core.query.jpql.Ast.Aggregate;
import io.vidocq.mansart.jpa.core.query.jpql.Ast.Between;
import io.vidocq.mansart.jpa.core.query.jpql.Ast.Binary;
import io.vidocq.mansart.jpa.core.query.jpql.Ast.In;
import io.vidocq.mansart.jpa.core.query.jpql.Ast.IsNull;
import io.vidocq.mansart.jpa.core.query.jpql.Ast.Join;
import io.vidocq.mansart.jpa.core.query.jpql.Ast.Like;
import io.vidocq.mansart.jpa.core.query.jpql.Ast.Literal;
import io.vidocq.mansart.jpa.core.query.jpql.Ast.Not;
import io.vidocq.mansart.jpa.core.query.jpql.Ast.Op;
import io.vidocq.mansart.jpa.core.query.jpql.Ast.Parameter;
import io.vidocq.mansart.jpa.core.query.jpql.Ast.Path;
import io.vidocq.mansart.jpa.core.query.jpql.Ast.Select;

import java.util.List;
import org.junit.jupiter.api.Test;

/** §4: the grammar of the Jakarta Persistence query language, parsed to its syntax tree. */
class ParserTest {

    private static Select select(String jpql) {
        return (Select) Parser.parse(jpql);
    }

    @Test
    void aSelectOfAnIdentificationVariable() { // §4.2, keywords in any case
        Select select = select("select e from Emp e");
        assertThat(select.items()).singleElement().satisfies(i -> assertThat(i.expression()).isEqualTo(new Path(List.of("e"))));
        assertThat(select.from()).singleElement().satisfies(r -> {
            assertThat(r.entity()).isEqualTo("Emp");
            assertThat(r.variable()).isEqualTo("e");
        });
        assertThat(select.where()).isNull();
    }

    @Test
    void pathsAliasesDistinctAndOrdering() {
        Select select = select("SELECT DISTINCT e.dept.name AS dn, e.name FROM Emp AS e ORDER BY e.name DESC, dn");
        assertThat(select.distinct()).isTrue();
        assertThat(select.items()).extracting(Ast.Item::alias).containsExactly("dn", null);
        assertThat(select.items().getFirst().expression()).isEqualTo(new Path(List.of("e", "dept", "name")));
        assertThat(select.orderBy()).extracting(Ast.OrderItem::descending).containsExactly(true, false);
        assertThat(select.orderBy().get(1).expression()).isEqualTo(new Path(List.of("dn")));
    }

    @Test
    void joins() { // §4.4.5
        Select select = select("SELECT d FROM Emp e JOIN e.dept d LEFT OUTER JOIN e.manager m INNER JOIN FETCH e.desk "
            + "LEFT JOIN e.projects p ON p.name = 'x'");
        List<Join> joins = select.from().getFirst().joins();
        assertThat(joins).extracting(Join::kind).containsExactly(Join.Kind.INNER, Join.Kind.LEFT, Join.Kind.INNER, Join.Kind.LEFT);
        assertThat(joins).extracting(Join::variable).containsExactly("d", "m", null, "p");
        assertThat(joins.get(2).fetch()).isTrue();
        assertThat(joins.get(3).on()).isInstanceOf(Binary.class);
    }

    @Test
    void conditionsFollowThePrecedenceOfTheLanguage() { // §4.6.1: NOT > AND > OR
        Select select = select("SELECT e FROM Emp e WHERE e.name = :name OR NOT e.age > 30 AND e.dept IS NOT NULL");
        assertThat(select.where()).isEqualTo(new Binary(
            new Binary(new Path(List.of("e", "name")), Op.EQ, new Parameter("name", 0)), Op.OR,
            new Binary(new Not(new Binary(new Path(List.of("e", "age")), Op.GT, new Literal(30))), Op.AND,
                new IsNull(new Path(List.of("e", "dept")), true))));
    }

    @Test
    void thePredicatesOfTheLanguage() {
        Select select = select("SELECT e FROM Emp e WHERE e.age NOT BETWEEN ?1 AND ?2 AND e.name LIKE 'V%' ESCAPE '\\\\' "
            + "AND e.id IN (1, 2, 3) AND e.code NOT IN :codes");
        Binary where = (Binary) select.where();
        Binary first = (Binary) ((Binary) where.left()).left();
        assertThat(first.left()).isEqualTo(new Between(new Path(List.of("e", "age")), new Parameter(null, 1), new Parameter(null, 2), true));
        assertThat(first.right()).isEqualTo(new Like(new Path(List.of("e", "name")), new Literal("V%"), new Literal("\\\\"), false));
        assertThat(((Binary) where.left()).right()).isEqualTo(new In(new Path(List.of("e", "id")),
            List.of(new Literal(1), new Literal(2), new Literal(3)), false));
        assertThat(where.right()).isEqualTo(new In(new Path(List.of("e", "code")), List.of(new Parameter("codes", 0)), true));
    }

    @Test
    void literals() { // §4.6.1: strings with doubled quotes, numbers by their suffix, booleans
        Select select = select("SELECT e FROM Emp e WHERE e.a = 'O''Hara' AND e.b = 10L AND e.c = 1.5 AND e.d = 2.5F AND e.e = TRUE "
            + "AND e.f = 3000000000");
        List<Object> values = new java.util.ArrayList<>();
        collect(select.where(), values);
        assertThat(values).containsExactly("O'Hara", 10L, 1.5d, 2.5f, true, 3000000000L);
    }

    private static void collect(Ast.Expr expr, List<Object> values) {
        if (expr instanceof Binary binary) {
            collect(binary.left(), values);
            collect(binary.right(), values);
        } else if (expr instanceof Literal literal) {
            values.add(literal.value());
        }
    }

    @Test
    void arithmeticAndAggregates() {
        Select select = select("SELECT COUNT(DISTINCT e.dept), AVG(e.salary * 1.1 - 3), COUNT(e) FROM Emp e GROUP BY e.dept "
            + "HAVING COUNT(e) > 1");
        assertThat(select.items().getFirst().expression()).isEqualTo(new Aggregate("COUNT", true, new Path(List.of("e", "dept"))));
        assertThat(select.items().get(1).expression()).isEqualTo(new Aggregate("AVG", false, new Binary(
            new Binary(new Path(List.of("e", "salary")), Op.TIMES, new Literal(1.1d)), Op.MINUS, new Literal(3))));
        assertThat(select.groupBy()).containsExactly(new Path(List.of("e", "dept")));
        assertThat(select.having()).isInstanceOf(Binary.class);
    }

    @Test
    void aSyntaxErrorNamesWhereItIs() { // §3.11.? IllegalArgumentException at createQuery
        assertThatThrownBy(() -> Parser.parse("SELECT e FROM Emp e WHERE")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("end of the query");
        assertThatThrownBy(() -> Parser.parse("SELECT e FROM Emp e WHERE e.name = 'open")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("string");
        assertThatThrownBy(() -> Parser.parse("SELEC e FROM Emp e")).isInstanceOf(IllegalArgumentException.class);
    }
}
