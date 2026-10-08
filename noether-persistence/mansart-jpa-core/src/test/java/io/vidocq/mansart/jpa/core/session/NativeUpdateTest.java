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
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TransactionRequiredException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §3.11 native queries, update part (brought forward from P7): SQL passed to the database as written. */
class NativeUpdateTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:native-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table Crew (id bigint primary key, name varchar(50), version int)");
            ddl.execute("insert into Crew values (1, 'Brigade', 0), (2, 'Sûreté', 0)");
        }
        emf = new PersistenceConfiguration("native").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Crew.class).property(PersistenceConfiguration.JDBC_URL, url).property(PersistenceConfiguration.JDBC_USER, "sa")
            .createEntityManagerFactory();
        em = emf.createEntityManager();
    }

    @AfterEach
    void close() throws SQLException {
        emf.close();
        database.close();
    }

    private String name(long id) throws SQLException {
        try (Statement query = database.createStatement(); ResultSet row = query.executeQuery("select name from Crew where id = " + id)) {
            return row.next() ? row.getString(1) : null;
        }
    }

    @Test
    void aNativeDeleteReturnsItsCount() throws SQLException {
        em.getTransaction().begin();
        assertThat(em.createNativeQuery("DELETE FROM Crew").executeUpdate()).isEqualTo(2);
        em.getTransaction().commit();
        assertThat(name(1)).isNull();
    }

    @Test
    void positionalParametersAreBound() throws SQLException {
        em.getTransaction().begin();
        int count = em.createNativeQuery("UPDATE Crew SET name = ?1 WHERE id = ?2").setParameter(1, "Brigade de sûreté")
            .setParameter(2, 1L).executeUpdate();
        em.getTransaction().commit();
        assertThat(count).isEqualTo(1);
        assertThat(name(1)).isEqualTo("Brigade de sûreté");
    }

    @Test
    void anUpdateNeedsATransaction() { // §3.11.6
        assertThatThrownBy(() -> em.createNativeQuery("DELETE FROM Crew").executeUpdate())
            .isInstanceOf(TransactionRequiredException.class);
    }

    @Test
    void thePersistenceContextIsFlushedFirst() { // FlushModeType.AUTO
        em.getTransaction().begin();
        em.persist(new Crew(3, "Brigade mobile"));
        assertThat(em.createNativeQuery("UPDATE Crew SET name = 'renamed' WHERE id = 3").executeUpdate()).isEqualTo(1);
        em.getTransaction().rollback();
    }

    @Test
    void readingANativeQueryComesWithP7() {
        assertThatThrownBy(() -> em.createNativeQuery("SELECT name FROM Crew").getResultList())
            .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("P7");
    }

    @Test
    void aFailedStatementMarksTheTransactionForRollback() { // §3.12
        em.getTransaction().begin();
        assertThatThrownBy(() -> em.createNativeQuery("DELETE FROM NoSuchTable").executeUpdate())
            .isInstanceOf(PersistenceException.class);
        assertThat(em.getTransaction().getRollbackOnly()).isTrue();
        em.getTransaction().rollback();
    }
}
