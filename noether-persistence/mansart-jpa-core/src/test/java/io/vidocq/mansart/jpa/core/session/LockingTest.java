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
package io.vidocq.mansart.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.jpa.core.model.build.fixtures.Crew;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PessimisticLockException;
import jakarta.persistence.RollbackException;
import jakarta.persistence.TransactionRequiredException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §3.5: optimistic and pessimistic locking. */
class LockingTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:locking-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table Crew (id bigint primary key, name varchar(50), version int)");
            ddl.execute("insert into Crew values (1, 'Brigade', 0)");
        }
        emf = new PersistenceConfiguration("locking").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Crew.class).property(PersistenceConfiguration.JDBC_URL, url).property(PersistenceConfiguration.JDBC_USER, "sa")
            .createEntityManagerFactory();
        em = emf.createEntityManager();
    }

    @AfterEach
    void close() throws SQLException {
        emf.close();
        database.close();
    }

    private int version() throws SQLException {
        try (Statement query = database.createStatement(); ResultSet row = query.executeQuery("select version from Crew where id = 1")) {
            row.next();
            return row.getInt(1);
        }
    }

    @Test
    void onlyAManagedInstanceIsLockedAndOnlyInATransaction() { // §3.5.4
        Crew crew = em.find(Crew.class, 1L);
        assertThatThrownBy(() -> em.lock(crew, LockModeType.OPTIMISTIC)).isInstanceOf(TransactionRequiredException.class);
        em.getTransaction().begin();
        assertThatThrownBy(() -> em.lock(new Crew(2, "new"), LockModeType.OPTIMISTIC)).isInstanceOf(IllegalArgumentException.class);
        em.getTransaction().rollback();
    }

    @Test
    void anOptimisticLockChecksTheVersionAtCommit() throws SQLException { // §3.5.5 OPTIMISTIC
        em.getTransaction().begin();
        Crew crew = em.find(Crew.class, 1L);
        em.lock(crew, LockModeType.OPTIMISTIC);
        assertThat(em.getLockMode(crew)).isEqualTo(LockModeType.OPTIMISTIC);
        try (Statement other = database.createStatement()) {
            other.executeUpdate("update Crew set version = 7 where id = 1");
        }
        assertThatThrownBy(() -> em.getTransaction().commit()).isInstanceOf(RollbackException.class)
            .hasCauseInstanceOf(OptimisticLockException.class);
    }

    @Test
    void aForcedIncrementWritesANewVersionWithoutAChange() throws SQLException { // OPTIMISTIC_FORCE_INCREMENT
        em.getTransaction().begin();
        Crew crew = em.find(Crew.class, 1L, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        em.getTransaction().commit();
        assertThat(version()).isEqualTo(1);
        assertThat(crew.version()).isEqualTo(1);
    }

    @Test
    void aPessimisticLockKeepsOthersFromTheRowUntilCommit() throws SQLException { // §3.5.6
        em.getTransaction().begin();
        em.find(Crew.class, 1L, LockModeType.PESSIMISTIC_WRITE);
        EntityManager other = emf.createEntityManager();
        other.getTransaction().begin();
        assertThatThrownBy(() -> other.find(Crew.class, 1L, LockModeType.PESSIMISTIC_WRITE,
            Map.of("jakarta.persistence.lock.timeout", 100)))
            .isInstanceOfAny(LockTimeoutException.class, PessimisticLockException.class);
        em.getTransaction().commit();
        other.getTransaction().rollback();
    }

    @Test
    void aLockTimeoutLeavesTheTransactionAlive() { // §3.12: LockTimeoutException does not mark the transaction
        em.getTransaction().begin();
        em.find(Crew.class, 1L, LockModeType.PESSIMISTIC_WRITE);
        EntityManager other = emf.createEntityManager();
        other.getTransaction().begin();
        assertThatThrownBy(() -> other.find(Crew.class, 1L, LockModeType.PESSIMISTIC_WRITE,
            Map.of("jakarta.persistence.lock.timeout", 100))).isInstanceOf(LockTimeoutException.class);
        assertThat(other.getTransaction().getRollbackOnly()).isFalse();
        em.getTransaction().commit();
        other.getTransaction().rollback();
    }

    @Test
    void aPessimisticForcedIncrementWritesANewVersion() throws SQLException {
        em.getTransaction().begin();
        Crew crew = em.find(Crew.class, 1L);
        em.lock(crew, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        em.getTransaction().commit();
        assertThat(version()).isEqualTo(1);
    }

    @Test
    void aStaleInstanceCannotBeLockedPessimistically() throws SQLException {
        em.getTransaction().begin();
        Crew crew = em.find(Crew.class, 1L);
        try (Statement other = database.createStatement()) {
            other.executeUpdate("update Crew set version = 7 where id = 1");
        }
        assertThatThrownBy(() -> em.lock(crew, LockModeType.PESSIMISTIC_WRITE)).isInstanceOf(OptimisticLockException.class);
        em.getTransaction().rollback();
    }
}
