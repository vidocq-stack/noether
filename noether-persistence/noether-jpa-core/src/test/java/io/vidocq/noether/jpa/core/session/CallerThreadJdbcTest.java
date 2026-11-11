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
package io.vidocq.noether.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.SchemaValidationException;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * JDBC work runs on the thread that asks for it, never on a thread of Noether's own: a container keeps its context
 * (a server's transaction, for instance) on the calling thread, and moving the work elsewhere would lose it.
 */
class CallerThreadJdbcTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Entity
    @Table(name = "caller_thread_item")
    public static class Item {
        @Id
        public int id;
    }

    /** Records the threads that ask for a connection. */
    static final class RecordingDataSource extends CountingDataSource {
        final Set<Thread> threads = ConcurrentHashMap.newKeySet();

        RecordingDataSource(String url) {
            super(url);
        }

        @Override
        public Connection getConnection() throws SQLException {
            threads.add(Thread.currentThread());
            return super.getConnection();
        }
    }

    private RecordingDataSource dataSource;
    private EntityManagerFactory emf;

    @BeforeEach
    void open() {
        // the work used to move to a virtual thread only when it was asked for from a platform thread
        assertThat(Thread.currentThread().isVirtual()).isFalse();
        dataSource = new RecordingDataSource("jdbc:h2:mem:caller-thread-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        emf = new PersistenceConfiguration("caller-thread")
            .managedClass(Item.class)
            .property("jakarta.persistence.nonJtaDataSource", dataSource)
            .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create")
            .createEntityManagerFactory();
    }

    @AfterEach
    void close() {
        emf.close();
    }

    @Test
    void schemaGenerationAtBootstrapRunsOnTheCallerThread() { // §9.4
        assertThat(dataSource.threads).containsExactly(Thread.currentThread());
    }

    @Test
    void schemaManagerRunsOnTheCallerThread() throws SchemaValidationException { // §7.12
        dataSource.threads.clear();
        emf.getSchemaManager().validate();
        emf.getSchemaManager().truncate();
        emf.getSchemaManager().drop(false);
        emf.getSchemaManager().create(false);
        assertThat(dataSource.threads).containsExactly(Thread.currentThread());
    }

    @Test
    void readOutsideATransactionRunsOnTheCallerThread() { // §7.7.1
        dataSource.threads.clear();
        try (var em = emf.createEntityManager()) {
            assertThat(em.find(Item.class, 1)).isNull();
        }
        assertThat(dataSource.threads).containsExactly(Thread.currentThread());
    }

    @Test
    void connectionFunctionsRunOnTheCallerThread() {
        try (var em = emf.createEntityManager()) {
            assertThat(em.callWithConnection((Connection connection) -> Thread.currentThread())).isSameAs(Thread.currentThread());
            em.getTransaction().begin();
            assertThat(em.callWithConnection((Connection connection) -> Thread.currentThread())).isSameAs(Thread.currentThread());
            em.getTransaction().rollback();
        }
    }
}
