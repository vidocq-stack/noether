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
package io.vidocq.noether.it.quarkus;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceUnitTransactionType;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Noether, unchanged, as the Jakarta Persistence provider of a Quarkus application (Vidocq/noether#45): ArC runs
 * Noether's CDI 4.1 Lite build compatible extension at build time. The JTA unit runs on Quarkus' own pieces: Agroal's
 * default data source, which enlists its connections in Narayana's transactions, and Narayana's
 * {@code TransactionSynchronizationRegistry}. Quarkus' Hibernate ORM extension is absent, so every statement here is
 * Noether's, and no Mansart module is in the application.
 */
@QuarkusTest
class QuarkusPortabilityTest {

    @Inject
    Library library;

    @Test
    void theProviderWritesAndQueriesInsideNarayanaTransactions() {
        library.add("Germinal");
        library.add("Germinal");

        assertEquals(2, library.count("Germinal"));
    }

    @Test
    void aRolledBackTransactionUndoesTheFlushedInsert() {
        assertThrows(IllegalStateException.class, () -> library.addThenFail("Doomed"));

        assertEquals(0, library.count("Doomed"));
    }

    @Test
    void aFindOutsideATransactionReadsTheCommittedRow() {
        long id = library.add("Nana");

        assertEquals("Nana", library.title(id));
    }

    @Test
    void aCommittedInsertIsInTheDatabaseOnceTheTransactionEnds() throws SQLException {
        library.add("Pot-Bouille");

        assertEquals(1, library.committedRows("Pot-Bouille"));
    }

    @Test
    void aTransactionMarkedForRollbackThroughTheRegistryUndoesTheFlushedInsert() throws SQLException {
        library.addThenMarkRollbackOnly("Marked");

        assertEquals(0, library.committedRows("Marked"));
        assertEquals(0, library.count("Marked"));
    }

    /** §7.9.1: nothing flushes in rename(); the interposed synchronization flushes before Narayana commits. */
    @Test
    void anUpdateIsFlushedBeforeTheCommitAndReadInTheNextTransaction() throws SQLException {
        long id = library.add("L'Assommoir");

        library.rename(id, "La Bête humaine");

        assertEquals("La Bête humaine", library.title(id));
        assertEquals(1, library.committedRows("La Bête humaine"));
        assertEquals(0, library.committedRows("L'Assommoir"));
    }

    @Test
    void aQueryOfTheSameTransactionSeesTheUnflushedUpdate() { // §3.11.2, FlushModeType.AUTO
        long id = library.add("Thérèse Raquin");

        assertEquals(1, library.renameThenCount(id, "Madeleine Férat"));
    }

    /** The same H2 session: each statement of Noether works on the connection Agroal enlisted in Narayana's transaction. */
    @Test
    void noetherWorksOnTheConnectionAgroalEnlisted() throws SQLException {
        long[] sessions = library.sessions();

        assertEquals(sessions[2], sessions[0]);
        assertEquals(sessions[2], sessions[1]);
    }

    @Test
    void persistenceUnitInjectionGivesTheContainerFactoryOfTheJtaUnit() {
        var factory = library.factory();

        assertEquals("library", factory.getName());
        assertEquals(PersistenceUnitTransactionType.JTA, factory.getTransactionType());
        assertThrows(IllegalStateException.class, factory::close);
    }

    /**
     * BUG-20261113-03: an injection point that leaves the name of the application's only unit out reaches that unit, so
     * the factory and, within a transaction, the persistence context of the injection points that name it.
     */
    @Test
    void anInjectionPointWithoutAUnitNameSharesTheFactoryAndContextOfTheOnlyUnit() {
        assertEquals("library", library.unnamedFactory().getName());
        assertSame(library.factory().getMetamodel(), library.unnamedFactory().getMetamodel());
        assertTrue(library.unnamedContextManages("Unnamed"), "one persistence context per transaction (§7.6.4)");
    }

    /**
     * Vidocq/noether#45: on Quarkus, Quarkus' transactions (F4, F10) and Agroal's pool (F7). No Mansart module is on
     * the path, and neither is Noether's optional pool adapter.
     */
    @Test
    void noMansartClassReachesTheApplication() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        assertDoesNotThrow(() -> Class.forName("io.vidocq.noether.jpa.jta.SynchronizationRegistryIntegration", false, loader));
        for (String name : List.of("io.vidocq.mansart.transactions.api.MansartTransactions",
                "io.vidocq.mansart.transactions.core.MansartTransactionManager",
                "io.vidocq.mansart.transactions.jdbc.ConnectionXAResource",
                "io.vidocq.mansart.pool.PoolConfig",
                "io.vidocq.mansart.pool.core.MansartDataSource",
                "io.vidocq.noether.jpa.pool.mansart.MansartConnectionPoolFactory")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(name, false, loader), name);
        }
    }
}
