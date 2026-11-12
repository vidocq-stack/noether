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
package io.vidocq.noether.jpa.jta;

import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import io.vidocq.noether.jpa.core.spi.TransactionIntegration;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.SynchronizationType;
import jakarta.persistence.TransactionRequiredException;
import jakarta.persistence.spi.PersistenceProvider;
import jakarta.persistence.spi.PersistenceProviderResolverHolder;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Transaction;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A JTA unit on a Jakarta EE container (§7.5, §9.6): the container's data source enlists the connections, the
 * container's transaction manager completes them, and the provider only flushes before completion and detaches
 * after it, through the standard {@code TransactionSynchronizationRegistry} (JTA 2.0 §3.4.4).
 */
class SynchronizationRegistryIntegrationTest {
    private final MansartTransactionManager manager = new MansartTransactionManager();
    private final RegistryOverTransactionManager registry = new RegistryOverTransactionManager(manager);

    @AfterEach
    void forgetNaming() {
        JndiRegistry.bound = null;
        System.clearProperty("java.naming.factory.initial");
    }

    private static String url(String name) {
        return "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1";
    }

    private EntityManagerFactory factory(String name, boolean explicitBridge) {
        Map<String, Object> properties = new HashMap<>();
        if (explicitBridge) properties.put("io.vidocq.noether.jpa.transaction-integration", new SynchronizationRegistryIntegration(registry));
        return noether().createContainerEntityManagerFactory(
            new ContainerUnitInfo(name, new EnlistingDataSource(url(name), manager)), properties);
    }

    /** As a container does: the provider named by the unit, among those the resolver lists. */
    private static PersistenceProvider noether() {
        Class<?> noether;
        try { // its package is not exported: the class is found by name, as a container finds the unit's provider
            noether = Class.forName("io.vidocq.noether.jpa.core.NoetherPersistenceProvider");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        return PersistenceProviderResolverHolder.getPersistenceProviderResolver().getPersistenceProviders().stream()
            .filter(noether::isInstance).findFirst().orElseThrow();
    }

    private static long rows(String name) throws Exception {
        try (var connection = DriverManager.getConnection(url(name), "sa", "");
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM jta_ledger")) {
            result.next();
            return result.getLong(1);
        }
    }

    @Test
    void theContainerCommitWritesWhatTheProviderFlushedBeforeCompletion() throws Exception { // §7.9.1
        try (var factory = factory("registry-commit", true)) {
            manager.begin();
            try (var em = factory.createEntityManager()) { // created in the transaction: associated with it
                em.persist(new Ledger(1, "credit"));
                assertThat(em.isJoinedToTransaction()).isTrue();
                assertThat(registry.interposed).isEqualTo(1);
            }
            manager.commit();

            assertThat(rows("registry-commit")).isEqualTo(1);
            try (var reader = factory.createEntityManager()) {
                assertThat(reader.find(Ledger.class, 1).entry).isEqualTo("credit"); // outside a transaction
            }
        }
    }

    @Test
    void theProviderNeverCompletesTheEnlistedConnectionItself() throws Exception {
        try (var factory = factory("registry-no-commit", true); var em = factory.createEntityManager()) {
            manager.begin();
            em.persist(new Ledger(1, "credit"));
            em.flush();
            assertThat(rows("registry-no-commit")).as("flushed, not committed").isZero();
            manager.commit();
            assertThat(rows("registry-no-commit")).isEqualTo(1);
        }
    }

    /** Each unit of work takes a handle; the data source hands out the connection the transaction enlisted each time. */
    @Test
    void everyUnitOfWorkOfATransactionRunsOnTheConnectionItEnlisted() throws Exception {
        try (var factory = factory("registry-shared", true); var em = factory.createEntityManager()) {
            manager.begin();
            em.persist(new Ledger(1, "credit"));
            em.flush();
            Object first = em.createNativeQuery("SELECT SESSION_ID()").getSingleResult();
            Object second = em.createNativeQuery("SELECT SESSION_ID()").getSingleResult();
            Object flushed = em.createNativeQuery("SELECT COUNT(*) FROM jta_ledger").getSingleResult();
            manager.commit();

            assertThat(second).isEqualTo(first);
            assertThat(((Number) flushed).longValue()).as("the flushed insert, not yet committed").isEqualTo(1);
        }
    }

    @Test
    void theContainerRollbackUndoesAFlushedInsertAndDetaches() throws Exception { // §3.3.3
        try (var factory = factory("registry-rollback", true); var em = factory.createEntityManager()) {
            manager.begin();
            Ledger ledger = new Ledger(1, "doomed");
            em.persist(ledger);
            em.flush();
            manager.rollback();

            assertThat(em.contains(ledger)).isFalse();
            assertThat(rows("registry-rollback")).isZero();
        }
    }

    @Test
    void aPersistenceExceptionMarksTheContainerTransactionForRollback() throws Exception { // §3.3.2
        try (var factory = factory("registry-rollback-only", true)) {
            manager.begin();
            var em = factory.createEntityManager();
            em.persist(new Ledger(1, "first"));
            Ledger duplicate = new Ledger(1, "duplicate");
            assertThatThrownBy(() -> em.persist(duplicate)).isInstanceOf(PersistenceException.class);
            assertThat(registry.getRollbackOnly()).isTrue();
            assertThatThrownBy(manager::commit).isInstanceOf(jakarta.transaction.RollbackException.class);
            assertThat(rows("registry-rollback-only")).isZero();
            em.close();
        }
    }

    @Test
    void anUnsynchronizedContextJoinsOnlyWhenAsked() throws Exception { // §7.6.1
        try (var factory = factory("registry-unsync", true);
                var em = factory.createEntityManager(SynchronizationType.UNSYNCHRONIZED)) {
            manager.begin();
            em.persist(new Ledger(1, "later"));
            assertThat(em.isJoinedToTransaction()).isFalse();
            assertThatThrownBy(em::flush).isInstanceOf(TransactionRequiredException.class);
            manager.commit();
            assertThat(rows("registry-unsync")).isZero();

            manager.begin();
            em.joinTransaction();
            assertThat(em.isJoinedToTransaction()).isTrue();
            manager.commit();
            assertThat(rows("registry-unsync")).isEqualTo(1);
        }
    }

    @Test
    void aJtaUnitWithoutABridgeFindsTheServerRegistryThroughJndi() throws Exception { // §9.6
        JndiRegistry.bound = registry;
        System.setProperty("java.naming.factory.initial", JndiRegistry.class.getName());
        try (var factory = factory("registry-jndi", false)) {
            manager.begin();
            try (var em = factory.createEntityManager()) {
                em.persist(new Ledger(1, "found"));
            }
            manager.commit();
            assertThat(rows("registry-jndi")).isEqualTo(1);
        }
    }

    /** A JTA unit of {@link Voucher}, whose identifiers a generator table allocates; with a non-JTA data source or not. */
    private EntityManagerFactory voucherFactory(String name, NonJtaDataSource nonJta) {
        return noether().createContainerEntityManagerFactory(new ContainerUnitInfo(name,
                new EnlistingDataSource(url(name), manager), nonJta, List.of(Voucher.class)),
            Map.of("io.vidocq.noether.jpa.transaction-integration", new SynchronizationRegistryIntegration(registry)));
    }

    private static List<Long> committedVouchers(String name) throws Exception {
        List<Long> ids = new ArrayList<>();
        try (var connection = DriverManager.getConnection(url(name), "sa", "");
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT id FROM jta_voucher ORDER BY id")) {
            while (result.next()) ids.add(result.getLong(1));
        }
        return ids;
    }

    private long persistVoucher(EntityManagerFactory factory, String code, boolean commit) throws Exception {
        manager.begin();
        Voucher voucher = new Voucher(code);
        try (var em = factory.createEntityManager()) {
            em.persist(voucher);
            em.flush();
        }
        if (commit) manager.commit(); else manager.rollback();
        return voucher.id;
    }

    /**
     * BUG-20261111-04: the server enlists every connection of the JTA data source and refuses their commit, so a JTA unit
     * allocates its TABLE identifiers on a connection of its non-JTA data source, which it commits on its own.
     */
    @Test
    void aJtaUnitAllocatesTableIdentifiersOnAConnectionOfItsNonJtaDataSource() throws Exception {
        NonJtaDataSource nonJta = new NonJtaDataSource(url("registry-table"));
        try (var factory = voucherFactory("registry-table", nonJta)) {
            long id = persistVoucher(factory, "first", true);

            assertThat(id).isEqualTo(1);
            assertThat(committedVouchers("registry-table")).containsExactly(1L);
            assertThat(nonJta.handedOut).as("the allocation").singleElement()
                .satisfies(connection -> assertThat(connection.isClosed()).isTrue());
        }
    }

    /** §11.1.52: the block an allocation committed stays allocated when the business transaction rolls back. */
    @Test
    void identifiersAllocatedForARolledBackTransactionAreNeverHandedOutAgain() throws Exception {
        NonJtaDataSource nonJta = new NonJtaDataSource(url("registry-table-rollback"));
        try (var factory = voucherFactory("registry-table-rollback", nonJta)) {
            List<Long> handedOut = new ArrayList<>();
            handedOut.add(persistVoucher(factory, "doomed", false));
            for (int i = 0; i < 11; i++) handedOut.add(persistVoucher(factory, "voucher " + i, true));

            assertThat(handedOut).doesNotHaveDuplicates().hasSize(12);
            assertThat(committedVouchers("registry-table-rollback"))
                .containsExactly(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
            assertThat(nonJta.handedOut).as("two blocks of 10").hasSize(2);
        }
    }

    @Test
    void aJtaUnitWithATableGeneratorAndNoNonJtaDataSourceIsRefused() {
        assertThatThrownBy(() -> voucherFactory("registry-table-refused", null))
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining("Persistence unit registry-table-refused")
            .hasMessageContaining("Voucher (TABLE, generator vouchers)")
            .hasMessageContaining("<non-jta-data-source>")
            .hasMessageContaining("quarkus.datasource.\"name\".jdbc.transactions=disabled")
            .hasMessageContaining("SEQUENCE or IDENTITY");
    }

    @Test
    void withoutJndiAJtaUnitWithoutABridgeIsStillRefused() {
        assertThatThrownBy(() -> factory("registry-no-jndi", false))
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining("noether-jpa-jta");
    }

    /** A session of the bridge whose connections and completion steps record the thread that runs them. */
    private TransactionIntegration.Session session(SynchronizationType synchronization, Map<String, Thread> threads,
            List<Connection> acquired, List<Boolean> completions) {
        return new SynchronizationRegistryIntegration(registry).open(synchronization,
            () -> {
                threads.put("acquire", Thread.currentThread());
                Connection connection = DriverManager.getConnection(url("registry-session"), "sa", "");
                acquired.add(connection);
                return connection;
            },
            new TransactionIntegration.Completion() {
                @Override
                public void beforeCommit(Connection connection) {
                    threads.put("flush", Thread.currentThread());
                }

                @Override
                public void afterCompletion(boolean committed) {
                    threads.put("detach", Thread.currentThread());
                    completions.add(committed);
                }
            });
    }

    /** Decision D44: the container associates its transaction with the caller's thread; no JDBC work leaves it. */
    @Test
    void theJdbcWorkOfAJoinedContextRunsOnTheCallerThread() throws Exception {
        assertThat(Thread.currentThread().isVirtual()).isFalse();
        Map<String, Thread> threads = new ConcurrentHashMap<>();
        List<Connection> acquired = new ArrayList<>();
        var session = session(SynchronizationType.SYNCHRONIZED, threads, acquired, new ArrayList<>());
        manager.begin();
        session.join();
        threads.put("work", session.onConnection(connection -> Thread.currentThread()));
        manager.commit();

        assertThat(threads).containsOnlyKeys("acquire", "work", "flush", "detach")
            .allSatisfy((step, thread) -> assertThat(thread).as(step).isSameAs(Thread.currentThread()));
        assertThat(acquired).as("one handle for the work, one for the flush").hasSize(2)
            .allSatisfy(connection -> assertThat(connection.isClosed()).isTrue());
    }

    /** What a pool sees of the handles it handed out when its own synchronization runs. */
    private Synchronization openHandles(List<Connection> acquired, List<Long> seen) {
        return new Synchronization() {
            @Override
            public void beforeCompletion() {
                seen.add(open(acquired));
            }

            @Override
            public void afterCompletion(int status) {
                seen.add(open(acquired));
            }
        };
    }

    private static long open(List<Connection> connections) {
        return connections.stream().filter(connection -> {
            try {
                return !connection.isClosed();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }).count();
    }

    /**
     * BUG-20261111-05: a pool closes, with a warning, the handles still open when the transaction completes (Agroal:
     * "Closing 1 open connection(s) prior to commit"). Each unit of work closes its handle, so the pool finds none open
     * whether its synchronization runs before Noether's or after it; JTA leaves the order of interposed
     * synchronizations open.
     */
    @Test
    void noHandleIsOpenWhenAnotherSynchronizationChecksBeforeOrAfterTheCommit() throws Exception {
        List<Connection> acquired = new ArrayList<>();
        List<Long> before = new ArrayList<>();
        List<Long> after = new ArrayList<>();
        var session = session(SynchronizationType.SYNCHRONIZED, new ConcurrentHashMap<>(), acquired, new ArrayList<>());
        manager.begin();
        registry.registerInterposedSynchronization(openHandles(acquired, before));
        session.join();
        session.onConnection(connection -> connection);
        registry.registerInterposedSynchronization(openHandles(acquired, after));
        manager.commit();

        assertThat(before).as("registered before Noether's").containsExactly(0L, 0L);
        assertThat(after).as("registered after Noether's").containsExactly(0L, 0L);
        assertThat(acquired).isNotEmpty();
    }

    @Test
    void noHandleIsOpenWhenAnotherSynchronizationChecksAfterARollback() throws Exception {
        List<Connection> acquired = new ArrayList<>();
        List<Long> seen = new ArrayList<>();
        var session = session(SynchronizationType.SYNCHRONIZED, new ConcurrentHashMap<>(), acquired, new ArrayList<>());
        manager.begin();
        registry.registerInterposedSynchronization(openHandles(acquired, seen));
        session.join();
        session.onConnection(connection -> connection);
        manager.rollback();

        assertThat(seen).as("afterCompletion only").containsExactly(0L);
        assertThat(acquired).singleElement().satisfies(connection -> assertThat(connection.isClosed()).isTrue());
    }

    /** The connection handed out as such (EntityManager.unwrap) stays the same until Noether's synchronization. */
    @Test
    void theConnectionHandedOutStaysOpenUntilTheTransactionCompletes() throws Exception {
        List<Connection> acquired = new ArrayList<>();
        var session = session(SynchronizationType.SYNCHRONIZED, new ConcurrentHashMap<>(), acquired, new ArrayList<>());
        manager.begin();
        session.join();
        Connection connection = session.connection();
        assertThat(session.connection()).isSameAs(connection);
        assertThat(connection.isClosed()).isFalse();
        manager.commit();

        assertThat(connection.isClosed()).isTrue();
        assertThat(acquired).allSatisfy(handle -> assertThat(handle.isClosed()).isTrue());
    }

    /** §3.3.2, §7.9.1: a context marked for rollback is not flushed, and its instances are detached after completion. */
    @Test
    void aJoinedContextMarksTheTransactionForRollbackAndIsNotFlushed() throws Exception {
        Map<String, Thread> threads = new ConcurrentHashMap<>();
        List<Boolean> completions = new ArrayList<>();
        var session = session(SynchronizationType.SYNCHRONIZED, threads, new ArrayList<>(), completions);
        manager.begin();
        session.join();
        assertThat(session.isRollbackOnly()).isFalse();
        session.rollbackOnly();
        assertThat(session.isRollbackOnly()).isTrue();
        assertThat(registry.getRollbackOnly()).isTrue();
        assertThatThrownBy(manager::commit).isInstanceOf(jakarta.transaction.RollbackException.class);

        assertThat(threads).doesNotContainKey("flush");
        assertThat(completions).containsExactly(false);
        assertThat(session.joined()).isFalse();
    }

    /** A transaction already marked for rollback refuses new synchronizations: the join fails and holds no connection. */
    @Test
    void joiningATransactionMarkedForRollbackFailsWithoutTakingAConnection() throws Exception {
        List<Connection> acquired = new ArrayList<>();
        var session = session(SynchronizationType.SYNCHRONIZED, new ConcurrentHashMap<>(), acquired, new ArrayList<>());
        manager.begin();
        manager.setRollbackOnly();
        try {
            assertThatThrownBy(session::join).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("Unable to join the JTA transaction");
            assertThat(session.joined()).isFalse();
            assertThat(acquired).isEmpty();
        } finally {
            manager.rollback();
        }
    }

    /** §7.6.1: a persistence context is associated with one transaction at a time. */
    @Test
    void aContextJoinedToOneTransactionRefusesAnother() throws Exception {
        var session = session(SynchronizationType.SYNCHRONIZED, new ConcurrentHashMap<>(), new ArrayList<>(), new ArrayList<>());
        manager.begin();
        session.join();
        Transaction first = manager.suspend();
        manager.begin();
        try {
            assertThatThrownBy(session::active).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("different JTA transaction");
            assertThatThrownBy(session::join).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("different transaction");
        } finally {
            manager.rollback();
            manager.resume(first);
            manager.rollback();
        }
        assertThat(session.joined()).isFalse();
    }
}
