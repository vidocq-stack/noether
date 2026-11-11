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
 * https://joinup.ec.europa.eu/collection/eupl/eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.noether.jpa.core.mapping.MappedUnit;
import io.vidocq.noether.jpa.core.model.build.fixtures.rel.Dept;
import io.vidocq.noether.jpa.core.model.build.fixtures.rel.Desk;
import io.vidocq.noether.jpa.core.model.build.fixtures.rel.Emp;
import io.vidocq.noether.jpa.core.query.jpql.Ast;
import io.vidocq.noether.jpa.core.session.EntityManagerFactoryImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §3.11: a query string created again is the same query. Its parse tree and, when no parameter value shaped it, its
 * translation are kept by the factory and reused; what a query returns never depends on an earlier execution.
 */
class QueryPlansTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final String BY_NAME = "select d from Dept d where d.name = :name order by d.id";
    private static final String BY_NAMES = "select d from Dept d where d.name in :names order by d.id";

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:query-plans-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table Dept (id bigint primary key, name varchar(50))");
            ddl.execute("create table Desk (id bigint primary key, location varchar(50))");
            ddl.execute("create table Emp (id bigint primary key, name varchar(50), version int, dept_id bigint, MGR bigint, DESK_ID bigint)");
        }
        emf = new PersistenceConfiguration("query-plans").provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Dept.class).managedClass(Desk.class).managedClass(Emp.class).property(PersistenceConfiguration.JDBC_URL, url)
            .property(PersistenceConfiguration.JDBC_USER, "sa").createEntityManagerFactory();
        em = emf.createEntityManager();
        em.getTransaction().begin();
        List.of(new Dept(1, "Sales"), new Dept(2, "Research"), new Dept(3, "Sales")).forEach(em::persist);
        em.getTransaction().commit();
        em.clear();
    }

    @AfterEach
    void close() throws SQLException {
        emf.close();
        database.close();
    }

    @Test
    void aQueryStringIsParsedOnce() { // §3.11
        QueryPlans plans = new QueryPlans();

        assertThat(plans.parse(BY_NAME)).isSameAs(plans.parse(BY_NAME));
        assertThat(plans.parse(BY_NAME).statement()).isInstanceOf(Ast.Query.class);
    }

    @Test
    void anInvalidQueryStringFailsEachTime() { // §3.11: IllegalArgumentException if the query string is invalid
        QueryPlans plans = new QueryPlans();

        assertThatThrownBy(() -> plans.parse("select from")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plans.parse("select from")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTranslationNoParameterValueShapedIsReused() { // a single-valued parameter only binds
        QueryPlans.Parsed parsed = new QueryPlans().parse(BY_NAME);
        Function<Ast.Parameter, Object> first = _ -> "Sales";
        Function<Ast.Parameter, Object> second = _ -> "Research";

        QueryPlans.Plan plan = parsed.plan(unit(), first, true, false);

        assertThat(parsed.plan(unit(), second, true, false)).isSameAs(plan);
        assertThat(parsed.plan(unit(), second, false, false)).as("another translation mode").isNotSameAs(plan);
        assertThat(parsed.plan(unit(), null, true, false)).as("the validation at createQuery").isNotSameAs(plan);
    }

    @Test
    void aTranslationAParameterValueShapedIsNotReused() { // §4.6.9: IN expands the collection it is given
        QueryPlans.Parsed parsed = new QueryPlans().parse(BY_NAMES);

        QueryPlans.Plan two = parsed.plan(unit(), _ -> List.of("Sales", "Research"), true, false);
        QueryPlans.Plan one = parsed.plan(unit(), _ -> List.of("Sales"), true, false);

        assertThat(one).isNotSameAs(two);
        assertThat(one.compiled().slots()).hasSize(1);
        assertThat(two.compiled().slots()).hasSize(2);
    }

    @Test
    void aPlanRendersOncePerDialect() {
        QueryPlans.Plan plan = new QueryPlans().parse(BY_NAME).plan(unit(), _ -> "Sales", true, false);
        io.vidocq.noether.jpa.dialect.Dialect dialect = new io.vidocq.noether.jpa.dialect.StandardDialect() {
            @Override
            public String name() {
                return "ANSI";
            }
        };

        assertThat(plan.rendered(dialect)).isSameAs(plan.rendered(dialect));
        assertThat(plan.rendered(dialect).sql()).isEqualTo(dialect.renderQuery(plan.compiled().sql()).sql());
    }

    @Test
    void theSameQueryStringReturnsWhatEachExecutionBinds() { // §3.11
        assertThat(names(BY_NAME, "name", "Sales")).containsExactly(1L, 3L);
        assertThat(names(BY_NAME, "name", "Research")).containsExactly(2L);
        assertThat(names(BY_NAME, "name", "Nobody")).isEmpty();
        assertThat(names(BY_NAME, "name", "Sales")).containsExactly(1L, 3L);
    }

    @Test
    void theSameQueryStringExpandsEachCollectionItIsGiven() { // §4.6.9
        assertThat(names(BY_NAMES, "names", List.of("Sales", "Research"))).containsExactly(1L, 2L, 3L);
        assertThat(names(BY_NAMES, "names", List.of("Research"))).containsExactly(2L);
        assertThat(names(BY_NAMES, "names", List.of())).isEmpty();
        assertThat(names(BY_NAMES, "names", List.of("Sales", "Research"))).containsExactly(1L, 2L, 3L);
    }

    @Test
    void theSameQueryStringKeepsItsLockModeAndPagePerExecution() { // §3.11: setLockMode, setMaxResults
        assertThat(names(BY_NAME, "name", "Sales")).containsExactly(1L, 3L);
        em.getTransaction().begin();
        List<Dept> locked = em.createQuery(BY_NAME, Dept.class).setParameter("name", "Sales")
            .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        assertThat(locked).extracting(Dept::id).containsExactly(1L, 3L);
        assertThat(em.getLockMode(locked.getFirst())).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        em.getTransaction().commit();
        em.clear();

        assertThat(em.createQuery(BY_NAME, Dept.class).setParameter("name", "Sales").setMaxResults(1).getResultList())
            .extracting(Dept::id).containsExactly(1L);
        assertThat(em.createQuery(BY_NAME, Dept.class).setParameter("name", "Sales").setFirstResult(1).getResultList())
            .extracting(Dept::id).containsExactly(3L);
        assertThat(names(BY_NAME, "name", "Sales")).containsExactly(1L, 3L);
    }

    private List<Long> names(String jpql, String parameter, Object value) {
        em.clear();
        return em.createQuery(jpql, Dept.class).setParameter(parameter, value).getResultList().stream().map(Dept::id).toList();
    }

    private MappedUnit unit() {
        return emf.unwrap(EntityManagerFactoryImpl.class).mapping();
    }
}
