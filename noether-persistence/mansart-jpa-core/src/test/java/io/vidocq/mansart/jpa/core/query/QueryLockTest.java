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
import jakarta.persistence.TransactionRequiredException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §3.11.? Query.setLockMode, §3.5: the entities a locking query returns hold its lock. */
class QueryLockTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:query-lock-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table Dept (id bigint primary key, name varchar(50))");
            ddl.execute("create table Desk (id bigint primary key, location varchar(50))");
            ddl.execute("create table Emp (id bigint primary key, name varchar(50), version int, dept_id bigint, MGR bigint, DESK_ID bigint)");
        }
        emf = new PersistenceConfiguration("query-lock").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Dept.class).managedClass(Desk.class).managedClass(Emp.class).property(PersistenceConfiguration.JDBC_URL, url)
            .property(PersistenceConfiguration.JDBC_USER, "sa").createEntityManagerFactory();
        em = emf.createEntityManager();
        em.getTransaction().begin();
        List.of(new Emp(1, "Chief", null), new Emp(2, "Clerk", null)).forEach(em::persist);
        em.getTransaction().commit();
        em.clear();
    }

    @AfterEach
    void close() throws SQLException {
        emf.close();
        database.close();
    }

    private String scalar(String sql) throws SQLException {
        try (Statement query = database.createStatement(); ResultSet row = query.executeQuery(sql)) {
            return row.next() ? row.getString(1) : null;
        }
    }

    @Test
    void aPessimisticQueryLocksTheEntitiesItReturns() { // §3.5.6
        em.getTransaction().begin();
        List<Emp> locked = em.createQuery("SELECT e FROM Emp e ORDER BY e.id", Emp.class).setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList();
        assertThat(locked).hasSize(2).allSatisfy(e -> assertThat(em.getLockMode(e)).isEqualTo(LockModeType.PESSIMISTIC_WRITE));
        em.getTransaction().commit();
    }

    @Test
    void aForcedIncrementQueryVersionsItsEntities() throws SQLException { // §3.5.5 OPTIMISTIC_FORCE_INCREMENT
        em.getTransaction().begin();
        em.createQuery("SELECT e FROM Emp e WHERE e.id = 1", Emp.class).setLockMode(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
            .getSingleResult();
        em.getTransaction().commit();
        assertThat(scalar("select version from Emp where id = 1")).isEqualTo("1");
        assertThat(scalar("select version from Emp where id = 2")).isEqualTo("0");
    }

    @Test
    void aLockModeNeedsATransactionAndASelect() { // §3.11: TransactionRequiredException, IllegalStateException
        assertThatThrownBy(() -> em.createQuery("SELECT e FROM Emp e").setLockMode(LockModeType.PESSIMISTIC_READ).getResultList())
            .isInstanceOf(TransactionRequiredException.class);
        assertThatThrownBy(() -> em.createQuery("DELETE FROM Emp e").setLockMode(LockModeType.PESSIMISTIC_READ))
            .isInstanceOf(IllegalStateException.class);
        assertThat(em.createQuery("SELECT e FROM Emp e").setLockMode(LockModeType.NONE).getResultList()).hasSize(2);
    }
}
