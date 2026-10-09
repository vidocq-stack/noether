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

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Persistence;
import jakarta.persistence.RollbackException;
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

/** Jakarta Persistence 3.2, §7.5.2–7.5.4: resource-local transactions over JDBC. */
class ResourceLocalTransactionTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private String url;
    private CountingDataSource dataSource;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        url = "jdbc:h2:mem:tx-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("create table item (id int primary key)");
        }
        dataSource = new CountingDataSource(url);
        emf = Persistence.createEntityManagerFactory("h2", Map.of("jakarta.persistence.nonJtaDataSource", dataSource));
        em = emf.createEntityManager();
    }

    @AfterEach
    void close() {
        if (emf.isOpen()) {
            emf.close();
        }
    }

    private int rows() throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select count(*) from item")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** The transaction's connection, as a provider-specific escape hatch: Hibernate offers the same unwrap. */
    private void insert(int id) throws SQLException {
        try (Statement s = em.unwrap(Connection.class).createStatement()) {
            s.execute("insert into item values (" + id + ")");
        }
    }

    @Test
    void noConnectionIsTakenUntilATransactionBegins() {
        assertThat(dataSource.opened).hasValue(0);
        em.getTransaction();
        assertThat(dataSource.opened).hasValue(0);
    }

    @Test
    void commitMakesTheWorkDurableAndReleasesTheConnection() throws SQLException {
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        assertThat(tx.isActive()).isTrue();
        assertThat(dataSource.open).hasValue(1);
        insert(1);
        tx.commit();
        assertThat(tx.isActive()).isFalse();
        assertThat(rows()).isEqualTo(1);
        assertThat(dataSource.open).hasValue(0);
    }

    @Test
    void rollbackUndoesTheWorkAndReleasesTheConnection() throws SQLException {
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        insert(1);
        tx.rollback();
        assertThat(tx.isActive()).isFalse();
        assertThat(rows()).isZero();
        assertThat(dataSource.open).hasValue(0);
    }

    @Test
    void aTransactionMarkedForRollbackFailsToCommit() throws SQLException { // EntityTransaction.commit
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        insert(1);
        tx.setRollbackOnly();
        assertThat(tx.getRollbackOnly()).isTrue();
        assertThatThrownBy(tx::commit).isInstanceOf(RollbackException.class);
        assertThat(tx.isActive()).isFalse();
        assertThat(rows()).isZero();
        assertThat(dataSource.open).hasValue(0);
    }

    @Test
    void theRollbackOnlyMarkIsForgottenByTheNextTransaction() {
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        tx.setRollbackOnly();
        tx.rollback();
        tx.begin();
        assertThat(tx.getRollbackOnly()).isFalse();
        tx.commit();
    }

    @Test
    void theStateMachineIsEnforced() { // EntityTransaction: IllegalStateException on every misuse
        EntityTransaction tx = em.getTransaction();
        assertThat(tx.isActive()).isFalse();
        assertThatThrownBy(tx::commit).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(tx::rollback).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(tx::setRollbackOnly).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(tx::getRollbackOnly).isInstanceOf(IllegalStateException.class);
        tx.begin();
        assertThatThrownBy(tx::begin).isInstanceOf(IllegalStateException.class);
        tx.commit();
    }

    @Test
    void theTimeoutIsKept() { // EntityTransaction.setTimeout, 3.2
        EntityTransaction tx = em.getTransaction();
        assertThat(tx.getTimeout()).isNull();
        tx.setTimeout(5);
        assertThat(tx.getTimeout()).isEqualTo(5);
        tx.setTimeout(null);
        assertThat(tx.getTimeout()).isNull();
    }

    @Test
    void aTransactionStartedBeforeCloseCanStillComplete() throws SQLException { // EntityManager.close, §7.7
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        insert(7);
        em.close();
        tx.commit();
        assertThat(rows()).isEqualTo(1);
        assertThat(dataSource.open).hasValue(0);
    }

    @Test
    void closingTheFactoryRollsBackWhatIsStillActive() throws SQLException {
        em.getTransaction().begin();
        insert(9);
        emf.close();
        assertThat(rows()).isZero();
        assertThat(dataSource.open).hasValue(0);
    }

    @Test
    void aTransactionAbandonedByTheFactoryCannotBeCommittedAnyMore() throws SQLException {
        // the owner's commit after the factory rolled the transaction back: a clear refusal, never a partial commit
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        insert(5);
        emf.close();
        assertThat(tx.isActive()).isFalse();
        assertThatThrownBy(tx::commit).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(tx::rollback).isInstanceOf(IllegalStateException.class);
        assertThat(rows()).isZero();
        assertThat(dataSource.open).hasValue(0);
    }

    @Test
    void closingTheFactoryWhileATransactionEndsNeverBreaksAndNeverLeaks() throws Exception {
        for (int round = 0; round < 300; round++) {
            EntityManagerFactory factory = Persistence.createEntityManagerFactory("h2",
                Map.of("jakarta.persistence.nonJtaDataSource", dataSource));
            EntityManager owner = factory.createEntityManager();
            owner.getTransaction().begin();
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            Thread closer = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                factory.close();
            });
            start.countDown();
            try {
                owner.getTransaction().commit();
            } catch (IllegalStateException | RollbackException expected) {
                // the factory won: the transaction was rolled back, and the owner is told so
            }
            closer.join();
            assertThat(owner.getTransaction().isActive()).isFalse();
        }
        assertThat(dataSource.open).hasValue(0);
    }

    @Test
    void aTransactionCannotBeginOnceTheFactoryIsClosed() {
        EntityTransaction tx = em.getTransaction();
        emf.close();
        assertThatThrownBy(tx::begin).isInstanceOf(IllegalStateException.class);
        assertThat(tx.isActive()).isFalse();
        assertThat(dataSource.open).hasValue(0);
    }

    @Test
    void callInTransactionDoesNotReportSuccessWhenTheFactoryAbandonedTheWork() throws SQLException {
        assertThatThrownBy(() -> emf.callInTransaction(inner -> {
            try (Statement s = inner.unwrap(Connection.class).createStatement()) {
                s.execute("insert into item values (6)");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            emf.close();
            return "done";
        })).isInstanceOf(IllegalStateException.class);
        assertThat(rows()).isZero();
        assertThat(dataSource.open).hasValue(0);
    }

    @Test
    void workThatEndsItsOwnTransactionIsNotCommittedAgain() throws SQLException {
        String result = emf.callInTransaction(inner -> {
            inner.getTransaction().rollback();
            return "rolled back by the work";
        });
        assertThat(result).isEqualTo("rolled back by the work");
        assertThat(dataSource.open).hasValue(0);
    }

    @Test
    void runInTransactionCommits() throws SQLException { // EntityManagerFactory.runInTransaction, 3.2
        emf.runInTransaction(inner -> {
            try (Statement s = inner.unwrap(Connection.class).createStatement()) {
                s.execute("insert into item values (3)");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(rows()).isEqualTo(1);
        assertThat(dataSource.open).hasValue(0);
    }

    @Test
    void runInTransactionRollsBackOnFailureAndRethrows() throws SQLException {
        IllegalStateException failure = new IllegalStateException("boom");
        assertThatThrownBy(() -> emf.runInTransaction(inner -> {
            try (Statement s = inner.unwrap(Connection.class).createStatement()) {
                s.execute("insert into item values (4)");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            throw failure;
        })).isSameAs(failure);
        assertThat(rows()).isZero();
        assertThat(dataSource.open).hasValue(0);
    }

    @Test
    void callInTransactionReturnsTheResult() {
        String result = emf.callInTransaction(inner -> inner.isJoinedToTransaction() ? "joined" : "alone");
        assertThat(result).isEqualTo("joined");
    }

    @Test
    void aRuntimeExceptionOfAnEntityManagerMethodMarksTheTransactionForRollback() { // §3.12, EntityManager
        EntityTransaction tx = em.getTransaction();
        tx.begin();
        assertThatThrownBy(() -> em.unwrap(String.class)).isInstanceOf(jakarta.persistence.PersistenceException.class);
        assertThat(tx.getRollbackOnly()).isTrue();
        tx.rollback();

        tx.begin();
        assertThatThrownBy(() -> em.createStoredProcedureQuery(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(tx.getRollbackOnly()).isTrue();
        tx.rollback();

        tx.begin();
        assertThatThrownBy(() -> em.setProperty("jakarta.persistence.lock.timeout", "soon")).isInstanceOf(IllegalArgumentException.class);
        assertThat(tx.getRollbackOnly()).isTrue();
        tx.rollback();

        // Factory-backed metadata methods now succeed and must not mark rollback-only.
        tx.begin();
        assertThat(em.getCriteriaBuilder()).isNotNull();
        assertThat(em.getMetamodel()).isNotNull();
        assertThat(tx.getRollbackOnly()).isFalse();
        tx.rollback();
    }

    @Test
    void anExceptionWithoutTransactionMarksNothing() {
        assertThatThrownBy(() -> em.unwrap(String.class)).isInstanceOf(jakarta.persistence.PersistenceException.class);
        em.getTransaction().begin();
        assertThat(em.getTransaction().getRollbackOnly()).isFalse();
        em.getTransaction().rollback();
    }

    @Test
    void theConnectionIsOnlyReachableInsideATransaction() {
        assertThatThrownBy(() -> em.unwrap(Connection.class)).isInstanceOf(jakarta.persistence.TransactionRequiredException.class);
    }

    @Test
    void jdbcPropertiesAreUsedWhenNoDataSourceIsGiven() throws SQLException { // §8.2.1.9 jakarta.persistence.jdbc.*
        try (EntityManagerFactory byUrl = Persistence.createEntityManagerFactory("h2", Map.of("jakarta.persistence.jdbc.url", url))) {
            EntityManager other = byUrl.createEntityManager();
            other.getTransaction().begin();
            try (Statement s = other.unwrap(Connection.class).createStatement()) {
                s.execute("insert into item values (11)");
            }
            other.getTransaction().commit();
            other.close();
        }
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void anUnreachableDatabaseIsAPersistenceException() {
        try (EntityManagerFactory broken = Persistence.createEntityManagerFactory("h2",
                Map.of("jakarta.persistence.jdbc.url", "jdbc:h2:mem:x;IFEXISTS=TRUE;DB_CLOSE_DELAY=0", "jakarta.persistence.jdbc.user", "nobody"))) {
            EntityManager other = broken.createEntityManager();
            assertThatThrownBy(() -> other.getTransaction().begin()).isInstanceOf(jakarta.persistence.PersistenceException.class);
            assertThat(other.getTransaction().isActive()).isFalse();
        }
    }
}
