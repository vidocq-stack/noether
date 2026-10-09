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
package io.vidocq.mansart.jpa.core.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Dept;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Desk;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Emp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQueryReference;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §3.11.12 (3.2: §3.11.x), §10.4: named queries — declared by annotations, added at run time, referenced. */
class NamedQueryTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:named-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table Dept (id bigint primary key, name varchar(50))");
            ddl.execute("create table Desk (id bigint primary key, location varchar(50))");
            ddl.execute("create table Emp (id bigint primary key, name varchar(50), version int, dept_id bigint, MGR bigint, DESK_ID bigint)");
        }
        emf = new PersistenceConfiguration("named").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Dept.class).managedClass(Desk.class).managedClass(Emp.class).property(PersistenceConfiguration.JDBC_URL, url)
            .property(PersistenceConfiguration.JDBC_USER, "sa").createEntityManagerFactory();
        em = emf.createEntityManager();
        Dept sales = new Dept(1, "Sales");
        em.getTransaction().begin();
        List.of(sales, new Dept(2, "Audit"), new Emp(1, "Chief", sales), new Emp(2, "Clerk", sales)).forEach(em::persist);
        em.getTransaction().commit();
        em.clear();
    }

    @AfterEach
    void close() throws SQLException {
        emf.close();
        database.close();
    }

    @Test
    void anAnnotatedQueryRunsWithItsHints() { // §10.4: @NamedQuery
        var query = em.createNamedQuery("Dept.byName", Dept.class);
        assertThat(query.setParameter("name", "Sales").getSingleResult().id()).isEqualTo(1);
        assertThat(query.getHints()).containsEntry("jakarta.persistence.query.timeout", "5000");
        assertThat(em.createNamedQuery("Emp.count").getSingleResult()).isEqualTo(2L);
        assertThat(em.createNamedQuery("Emp.forUpdate").getLockMode()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void unknownNamesAndWrongTypesAreRefused() { // §3.11: IllegalArgumentException
        assertThatThrownBy(() -> em.createNamedQuery("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> em.createNamedQuery("Emp.count", String.class)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNamedNativeQueryUpdates() throws SQLException { // §10.4: @NamedNativeQuery
        em.getTransaction().begin();
        assertThat(em.createNamedQuery("Emp.rename").setParameter(1, "Boss").setParameter(2, 1L).executeUpdate()).isEqualTo(1);
        em.getTransaction().commit();
        try (Statement query = database.createStatement(); ResultSet row = query.executeQuery("select name from Emp where id = 1")) {
            row.next();
            assertThat(row.getString(1)).isEqualTo("Boss");
        }
    }

    @Test
    void aQueryAddedToTheFactoryKeepsItsSettings() { // §7.? EntityManagerFactory.addNamedQuery
        Query query = em.createQuery("SELECT e.name FROM Emp e ORDER BY e.id").setMaxResults(1);
        emf.addNamedQuery("firstName", query);
        query.setMaxResults(5); // later changes of the query do not reach the named query
        assertThat(em.createNamedQuery("firstName").getMaxResults()).isEqualTo(1);
        assertThat(em.createNamedQuery("firstName").getResultList()).containsExactly("Chief");
        assertThat(em.createNamedQuery("firstName", String.class).getResultList()).containsExactly("Chief");
    }

    @Test
    void namedQueriesAreReferencedByTheirResultType() { // 3.2: TypedQueryReference
        Map<String, TypedQueryReference<Dept>> depts = emf.getNamedQueries(Dept.class);
        assertThat(depts).containsKey("Dept.byName").doesNotContainKey("Emp.count");
        TypedQueryReference<Long> count = emf.getNamedQueries(Long.class).get("Emp.count");
        assertThat(count.getResultType()).isEqualTo(Long.class);
        assertThat(em.createQuery(count).getSingleResult()).isEqualTo(2L);
        assertThat(em.createQuery(depts.get("Dept.byName")).setParameter("name", "Audit").getSingleResult().id()).isEqualTo(2);
    }
}
