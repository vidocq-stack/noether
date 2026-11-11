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
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.SynchronizationType;
import jakarta.persistence.TransactionRequiredException;
import jakarta.persistence.spi.PersistenceProvider;
import jakarta.persistence.spi.PersistenceProviderResolverHolder;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
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
        return PersistenceProviderResolverHolder.getPersistenceProviderResolver().getPersistenceProviders().stream()
            .filter(provider -> provider.getClass().getName().equals("io.vidocq.noether.jpa.core.NoetherPersistenceProvider"))
            .findFirst().orElseThrow();
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

    @Test
    void withoutJndiAJtaUnitWithoutABridgeIsStillRefused() {
        assertThatThrownBy(() -> factory("registry-no-jndi", false))
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining("noether-jpa-jta");
    }
}
