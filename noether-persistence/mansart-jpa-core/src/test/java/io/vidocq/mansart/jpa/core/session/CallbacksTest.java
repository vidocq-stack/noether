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

import io.vidocq.mansart.jpa.core.model.build.fixtures.callbacks.Journal;
import io.vidocq.mansart.jpa.core.model.build.fixtures.callbacks.Quiet;
import io.vidocq.mansart.jpa.core.model.build.fixtures.callbacks.Record;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §3.6: lifecycle callbacks and entity listeners, called where §3.6.3 says, in the order of §3.6.4. */
class CallbacksTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        Journal.ENTRIES.clear();
        String url = "jdbc:h2:mem:callbacks-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table Record (id bigint primary key, text varchar(50), stamp varchar(50))");
            ddl.execute("create table Quiet (id bigint primary key)");
        }
        emf = new PersistenceConfiguration("callbacks").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Record.class).managedClass(Quiet.class).property(PersistenceConfiguration.JDBC_URL, url)
            .property(PersistenceConfiguration.JDBC_USER, "sa").createEntityManagerFactory();
        em = emf.createEntityManager();
    }

    @AfterEach
    void close() throws SQLException {
        emf.close();
        database.close();
    }

    private Record stored() {
        Record record = new Record(1, "first");
        em.getTransaction().begin();
        em.persist(record);
        em.getTransaction().commit();
        Journal.ENTRIES.clear();
        return record;
    }

    @Test
    void persistCallsThePrePersistCallbacksAndTheInsertThePostPersistOnes() {
        em.getTransaction().begin();
        em.persist(new Record(1, "first"));
        assertThat(Journal.ENTRIES).containsExactly("AuditListener.prePersist", "StampListener.prePersist", "Record.onPersist");
        em.getTransaction().commit();
        assertThat(Journal.ENTRIES).endsWith("Tracked.persisted");
    }

    @Test
    void anUpdateCallsPreUpdateWhoseChangesAreWrittenThenPostUpdate() throws SQLException {
        Record record = stored();
        em.getTransaction().begin();
        record.text("second");
        em.getTransaction().commit();
        assertThat(Journal.ENTRIES).containsExactly("StampListener.preUpdate", "Record.updated");
        try (Statement query = database.createStatement(); ResultSet row = query.executeQuery("select stamp from Record")) {
            row.next();
            assertThat(row.getString(1)).isEqualTo("updated");
        }
    }

    @Test
    void anUnchangedInstanceCallsNoUpdateCallback() {
        stored();
        em.getTransaction().begin();
        em.getTransaction().commit();
        assertThat(Journal.ENTRIES).isEmpty();
    }

    @Test
    void removeCallsPreRemoveAndTheDeletePostRemove() {
        Record record = stored();
        em.getTransaction().begin();
        em.remove(record);
        assertThat(Journal.ENTRIES).containsExactly("Record.removing");
        em.getTransaction().commit();
        assertThat(Journal.ENTRIES).containsExactly("Record.removing", "Record.removed");
    }

    @Test
    void aLoadAndARefreshCallPostLoad() {
        stored();
        em.clear();
        Record record = em.find(Record.class, 1L);
        assertThat(Journal.ENTRIES).containsExactly("AuditListener.postLoad", "Record.loaded");
        em.refresh(record);
        assertThat(Journal.ENTRIES).hasSize(4);
    }

    @Test
    void excludedSuperclassListenersAreNotCalled() {
        em.getTransaction().begin();
        em.persist(new Quiet(1));
        em.getTransaction().commit();
        assertThat(Journal.ENTRIES).containsExactly("Tracked.onPersist", "Tracked.persisted");
    }
}
