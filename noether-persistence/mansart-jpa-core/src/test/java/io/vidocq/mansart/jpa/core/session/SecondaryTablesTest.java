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

import io.vidocq.mansart.jpa.core.model.build.fixtures.Gadget;
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

/** §11.1.46 secondary tables: one row per table, joined by the primary key. */
class SecondaryTablesTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:secondary-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table Gadget (id bigint primary key, name varchar(50))");
            ddl.execute("create table GADGET_DETAILS (GADGET_ID bigint primary key references Gadget(id), WAREHOUSE varchar(50))");
            ddl.execute("create table GADGET_NOTES (id bigint primary key references Gadget(id), note varchar(50))");
        }
        emf = new PersistenceConfiguration("secondary").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Gadget.class).property(PersistenceConfiguration.JDBC_URL, url)
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
    void anInstanceIsWrittenToEachOfItsTables() throws SQLException {
        em.getTransaction().begin();
        em.persist(new Gadget(1, "lamp", "WH1", "fragile"));
        em.getTransaction().commit();
        assertThat(scalar("select name from Gadget where id = 1")).isEqualTo("lamp");
        assertThat(scalar("select WAREHOUSE from GADGET_DETAILS where GADGET_ID = 1")).isEqualTo("WH1");
        assertThat(scalar("select note from GADGET_NOTES where id = 1")).isEqualTo("fragile");
    }

    @Test
    void anInstanceIsReadFromEachOfItsTablesAndChangedThere() throws SQLException {
        em.getTransaction().begin();
        em.persist(new Gadget(1, "lamp", "WH1", "fragile"));
        em.getTransaction().commit();
        em.clear();
        Gadget gadget = em.find(Gadget.class, 1L);
        assertThat(gadget.warehouse()).isEqualTo("WH1");
        assertThat(gadget.note()).isEqualTo("fragile");
        em.getTransaction().begin();
        gadget.warehouse("WH2");
        em.getTransaction().commit();
        assertThat(scalar("select WAREHOUSE from GADGET_DETAILS where GADGET_ID = 1")).isEqualTo("WH2");
    }

    @Test
    void aMissingSecondaryRowReadsAsNullColumns() throws SQLException {
        try (Statement insert = database.createStatement()) {
            insert.executeUpdate("insert into Gadget values (2, 'bare')");
        }
        Gadget gadget = em.find(Gadget.class, 2L);
        assertThat(gadget.warehouse()).isNull();
        assertThat(gadget.note()).isNull();
    }

    @Test
    void anInstanceIsDeletedFromEachOfItsTables() throws SQLException {
        em.getTransaction().begin();
        Gadget gadget = new Gadget(1, "lamp", "WH1", "fragile");
        em.persist(gadget);
        em.getTransaction().commit();
        em.getTransaction().begin();
        em.remove(gadget);
        em.getTransaction().commit(); // the secondary rows first: they reference the primary one
        assertThat(scalar("select count(*) from Gadget")).isEqualTo("0");
        assertThat(scalar("select count(*) from GADGET_DETAILS")).isEqualTo("0");
    }
}
