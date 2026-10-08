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

import io.vidocq.mansart.jpa.core.model.build.fixtures.CharsConverter;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Geo;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Letter;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Postal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §3.9 and §11.1.10: converters named by attribute, applied on the way to the database and back, their failures wrapped. */
class ConvertersTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:converters-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table Letter (id bigint primary key, signature varchar(50), initials varchar(10), subject varchar(50), "
                + "street varchar(50), city varchar(50), lat double precision, lon double precision)");
        }
        emf = new PersistenceConfiguration("converters").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Letter.class).managedClass(CharsConverter.class).property(PersistenceConfiguration.JDBC_URL, url)
            .property(PersistenceConfiguration.JDBC_USER, "sa").createEntityManagerFactory();
        em = emf.createEntityManager();
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
    void theConversionsNamedByAttributeApplyBothWays() throws SQLException {
        em.getTransaction().begin();
        em.persist(new Letter(1, "vidocq", "EFV", "memoirs", new Postal("rue morgue", "Paris", new Geo(48.0, 2.0))));
        em.getTransaction().commit();
        assertThat(scalar("select signature from Letter")).isEqualTo("VIDOCQ"); // inherited, converted by the entity
        assertThat(scalar("select initials from Letter")).isEqualTo("VFE"); // auto-applied to char[]
        assertThat(scalar("select street from Letter")).isEqualTo("RUE MORGUE"); // converted by the embedded attribute
        assertThat(scalar("select city from Letter")).isEqualTo("Paris");
        assertThat(scalar("select lat from Letter")).isEqualTo("24.0"); // a nested path
        assertThat(scalar("select lon from Letter")).isEqualTo("2.0");
        em.clear();
        Letter letter = em.find(Letter.class, 1L);
        assertThat(letter.initials()).isEqualTo("EFV");
        assertThat(letter.address().geo()).isEqualTo(new Geo(48.0, 2.0));
    }

    @Test
    void aConverterFailingOnWriteIsAPersistenceExceptionThatMarksTheTransaction() {
        em.getTransaction().begin();
        em.persist(new Letter(1, "vidocq", "EFV", "refused", new Postal("rue morgue", "Paris", new Geo(48.0, 2.0))));
        assertThatThrownBy(() -> em.flush()).isInstanceOf(PersistenceException.class)
            .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(em.getTransaction().getRollbackOnly()).isTrue();
        em.getTransaction().rollback();
    }

    @Test
    void aConverterFailingOnReadIsAPersistenceExceptionThatMarksTheTransaction() throws SQLException {
        try (Statement insert = database.createStatement()) {
            insert.executeUpdate("insert into Letter (id, subject) values (1, 'UNREADABLE')");
        }
        em.getTransaction().begin();
        assertThatThrownBy(() -> em.find(Letter.class, 1L)).isInstanceOf(PersistenceException.class)
            .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(em.getTransaction().getRollbackOnly()).isTrue();
        em.getTransaction().rollback();
    }
}
