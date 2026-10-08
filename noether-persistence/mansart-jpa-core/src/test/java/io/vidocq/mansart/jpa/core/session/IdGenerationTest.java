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

import io.vidocq.mansart.jpa.core.model.build.fixtures.Account;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Badge;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Stamp;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Token;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §11.1.20 generated identifiers: assigned at persist, by blocks of allocationSize. */
class IdGenerationTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:ids-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create schema BANK");
            ddl.execute("create table BANK.ACCOUNTS (number bigint primary key, OWNER_NAME varchar(50), active boolean)");
            ddl.execute("create sequence ACCT_SEQ start with 1 increment by 20"); // the generator names no schema
            ddl.execute("create table GENERATOR_TABLE (PK_COL varchar(10) primary key, VAL_COL int)");
            ddl.execute("create table Badge (id int primary key)");
            ddl.execute("create table Token (id uuid primary key)");
            ddl.execute("create table SEQUENCE (SEQ_NAME varchar(10) primary key, SEQ_COUNT int)");
            ddl.execute("insert into SEQUENCE values ('SEQ_GEN', 0)");
            ddl.execute("create table Stamp (id bigint primary key)");
        }
        emf = new PersistenceConfiguration("ids").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Account.class).managedClass(Badge.class).managedClass(Token.class).managedClass(Stamp.class)
            .property(PersistenceConfiguration.JDBC_URL, url).property(PersistenceConfiguration.JDBC_USER, "sa")
            .createEntityManagerFactory();
    }

    @AfterEach
    void close() throws SQLException {
        emf.close();
        database.close();
    }

    private long scalar(String sql) throws SQLException {
        try (Statement query = database.createStatement(); ResultSet row = query.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    @Test
    void aSequenceAssignsTheIdentifierAtPersistByBlocks() throws SQLException {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Account account = new Account();
            em.persist(account);
            assertThat(account.getNumber()).isEqualTo(i + 1L); // assigned at persist, before any flush
            accounts.add(account);
        }
        em.getTransaction().commit();
        assertThat(scalar("select count(*) from BANK.ACCOUNTS")).isEqualTo(25);
        // two blocks of 20 were taken (1 and 21): the sequence hands out 41 next
        assertThat(scalar("values next value for ACCT_SEQ")).isEqualTo(41);
    }

    @Test
    void aTableGeneratorCreatesItsRowAndMovesItByBlocks() throws SQLException {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Badge first = new Badge();
        Badge second = new Badge();
        em.persist(first);
        em.persist(second);
        em.getTransaction().commit();
        assertThat(first.id()).isEqualTo(100);
        assertThat(second.id()).isEqualTo(101);
        assertThat(scalar("select VAL_COL from GENERATOR_TABLE where PK_COL = 'BADGE'")).isEqualTo(109);
    }

    @Test
    void aUuidIdentifierIsRandom() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Token token = new Token();
        em.persist(token);
        em.getTransaction().commit();
        assertThat(token.id()).isNotNull();
    }

    @Test
    void autoUsesTheDefaultTableGenerator() throws SQLException { // the SEQUENCE / SEQ_GEN table of the TCK DDL
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Stamp stamp = new Stamp();
        em.persist(stamp);
        em.getTransaction().commit();
        assertThat(stamp.id()).isEqualTo(1L);
        assertThat(scalar("select SEQ_COUNT from SEQUENCE where SEQ_NAME = 'SEQ_GEN'")).isEqualTo(50);
    }

    @Test
    void concurrentEntityManagersNeverShareAnIdentifier() throws Exception { // no lock across the round trip
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                futures.add(threads.submit(() -> {
                    EntityManager em = emf.createEntityManager();
                    em.getTransaction().begin();
                    for (int i = 0; i < 30; i++) {
                        Account account = new Account();
                        em.persist(account);
                        ids.add(account.getNumber());
                    }
                    em.getTransaction().commit();
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }
        assertThat(ids).hasSize(16 * 30);
        assertThat(scalar("select count(*) from BANK.ACCOUNTS")).isEqualTo(16 * 30);
    }
}
