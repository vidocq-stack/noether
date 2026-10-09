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
    void treatsAPathAsAnEntitySubtypeInExpressionsAndJoins() { // §4.4.9
        Select select = select("SELECT s.name FROM LineItem l JOIN TREAT(l.product AS SoftwareProduct) s "
            + "WHERE TREAT(l.product AS SoftwareProduct).revisionNumber = 1");
        assertThat(select.from().getFirst().joins().getFirst().path())
            .isEqualTo(new Path(List.of("l", "product"), "SoftwareProduct", 2));
        assertThat(((Binary) select.where()).left())
            .isEqualTo(new Path(List.of("l", "product", "revisionNumber"), "SoftwareProduct", 2));
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
    void functionsWithTheirOwnSyntax() { // §4.6.17.2: TRIM, EXTRACT, SUBSTRING, LOCATE, current dates
        Select select = select("SELECT TRIM(LEADING 'x' FROM e.name), TRIM(e.name), EXTRACT(YEAR FROM e.hired), CURRENT_DATE, "
            + "LOCAL DATETIME, SUBSTRING(e.name, 1, 2), LOCATE('a', e.name, 3) FROM Emp e");
        List<Ast.Expr> items = select.items().stream().map(Ast.Item::expression).toList();
        assertThat(items.get(0)).isEqualTo(new Ast.Function("TRIM", List.of(new Literal("LEADING"), new Literal("x"),
            new Path(List.of("e", "name")))));
        assertThat(items.get(1)).isEqualTo(new Ast.Function("TRIM", List.of(new Literal("BOTH"), new Literal(null),
            new Path(List.of("e", "name")))));
        assertThat(items.get(2)).isEqualTo(new Ast.Function("EXTRACT", List.of(new Literal("YEAR"), new Path(List.of("e", "hired")))));
        assertThat(items.get(3)).isEqualTo(new Ast.Function("CURRENT_DATE", List.of()));
        assertThat(items.get(4)).isEqualTo(new Ast.Function("LOCAL_DATETIME", List.of()));
        assertThat(items.get(5)).isInstanceOfSatisfying(Ast.Function.class, f -> assertThat(f.arguments()).hasSize(3));
        assertThat(items.get(6)).isInstanceOfSatisfying(Ast.Function.class, f -> assertThat(f.name()).isEqualTo("LOCATE"));
    }

    @Test
    void setOperationsAndCast() { // Jakarta Persistence 3.2
        Ast.SetQuery set = (Ast.SetQuery) Parser.parse("SELECT e.name FROM Emp e UNION ALL SELECT d.name FROM Dept d "
            + "INTERSECT SELECT x.name FROM Other x EXCEPT SELECT z.name FROM Last z ORDER BY e.name DESC");
        assertThat(set.operands()).hasSize(4);
        assertThat(set.operations()).containsExactly(
            new Ast.SetOperation(Ast.SetOperator.UNION, true),
            new Ast.SetOperation(Ast.SetOperator.INTERSECT, false),
            new Ast.SetOperation(Ast.SetOperator.EXCEPT, false));
        assertThat(set.orderBy()).containsExactly(new Ast.OrderItem(new Path(List.of("e", "name")), true, null));
        assertThat(select("SELECT CAST(e.age AS LONG), e.name || '!' FROM Emp e").items().getFirst().expression())
            .isEqualTo(new Ast.Cast(new Path(List.of("e", "age")), Ast.CastType.LONG));
    }

    @Test
    void caseCoalesceNullifAndConstructors() { // §4.6.17.4, §4.8.2
        Select select = select("SELECT NEW com.example.Summary(e.name, CASE WHEN e.age > 30 THEN 'senior' ELSE 'junior' END), "
            + "CASE e.status WHEN 1 THEN 'on' WHEN 2 THEN 'off' END, COALESCE(e.nick, e.name), NULLIF(e.a, 0) FROM Emp e");
        assertThat(select.items().getFirst().expression()).isInstanceOfSatisfying(Ast.Constructor.class, c -> {
            assertThat(c.className()).isEqualTo("com.example.Summary");
            assertThat(c.arguments().get(1)).isInstanceOfSatisfying(Ast.Case.class, k -> {
                assertThat(k.operand()).isNull();
                assertThat(k.otherwise()).isEqualTo(new Literal("junior"));
            });
        });
        assertThat(select.items().get(1).expression()).isInstanceOfSatisfying(Ast.Case.class, k -> {
            assertThat(k.operand()).isEqualTo(new Path(List.of("e", "status")));
            assertThat(k.whens()).hasSize(2);
            assertThat(k.otherwise()).isNull();
        });
        assertThat(select.items().get(2).expression()).isEqualTo(new Ast.Function("COALESCE", List.of(new Path(List.of("e", "nick")),
            new Path(List.of("e", "name")))));
    }

    @Test
    void subqueriesAndCollections() { // §4.5.10, §4.6.12, §4.6.13, §4.6.16
        Select select = select("SELECT e FROM Emp e WHERE EXISTS (SELECT p FROM e.projects p WHERE p.budget > ALL (SELECT q.budget "
            + "FROM Project q)) AND e.projects IS NOT EMPTY AND :p MEMBER OF e.projects AND SIZE(e.projects) > 1");
        assertThat(select.where().toString()).contains("Exists", "IsEmpty", "MemberOf", "SIZE");
        Ast.Range correlated = ((Ast.Select) ((Ast.Exists) ((Binary) ((Binary) ((Binary) select.where()).left()).left()).left()).select())
            .from()
            .getFirst();
        assertThat(correlated.entity()).isNull();
        assertThat(correlated.collection()).isEqualTo(new Path(List.of("e", "projects")));
    }

    @Test
    void literalsOfDatesAndEnumsAndOrderingOfNulls() { // §4.6.1: JDBC escapes; 3.1: NULLS FIRST / LAST
        Select select = select("SELECT o FROM Order o WHERE o.placed > {d '2024-01-31'} AND o.state = com.example.State.OPEN "
            + "ORDER BY o.placed DESC NULLS LAST, o.id NULLS FIRST");
        assertThat(select.from().getFirst().entity()).isEqualTo("Order"); // a keyword names an entity after FROM
        Binary where = (Binary) select.where();
        assertThat(((Binary) where.left()).right()).isEqualTo(new Literal(java.sql.Date.valueOf("2024-01-31")));
        assertThat(((Binary) where.right()).right()).isEqualTo(new Path(List.of("com", "example", "State", "OPEN")));
        assertThat(select.orderBy()).extracting(Ast.OrderItem::nullsFirst).containsExactly(false, true);
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
