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

import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Customer;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.RollbackException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §3.2.4 and §3.3.2: the persistence context of an entity manager is flushed at commit and detached at rollback. */
class FlushOnCommitTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private String url;
    private Connection keepAlive;
    private EntityManagerFactory emf;
    private EntityManagerImpl em;
    private MappedEntity customer;

    @BeforeEach
    void open() throws SQLException {
        url = "jdbc:h2:mem:flush-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url);
        try (Statement ddl = keepAlive.createStatement()) {
            ddl.execute("create table Customer (id bigint primary key, name varchar(50), E_MAIL varchar(120), visits int, "
                + "balance decimal(19,2), credit decimal(12,2), since date, legacy timestamp, birthDate date, token uuid, level int, "
                + "preferredLevel varchar(10), grade varchar(5), notes varchar(1000), picture varbinary(100), version int, "
                + "biography varchar(1000))");
        }
        emf = new PersistenceConfiguration("flush").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Customer.class).property(PersistenceConfiguration.JDBC_URL, url).createEntityManagerFactory();
        em = (EntityManagerImpl) emf.createEntityManager();
        customer = emf.unwrap(EntityManagerFactoryImpl.class).mapping().entity(Customer.class).orElseThrow();
    }

    @AfterEach
    void close() throws SQLException {
        if (emf.isOpen()) {
            emf.close();
        }
        keepAlive.close();
    }

    private Object newCustomer(long id, String name) {
        Object instance = customer.access().instantiate();
        customer.access().set(instance, customer.model().attributes().indexOf(customer.model().attribute("id").orElseThrow()), id);
        customer.access().set(instance, customer.model().attributes().indexOf(customer.model().attribute("name").orElseThrow()), name);
        return instance;
    }

    private long rows() throws SQLException {
        try (Statement query = keepAlive.createStatement(); ResultSet count = query.executeQuery("select count(*) from Customer")) {
            count.next();
            return count.getLong(1);
        }
    }

    @Test
    void commitWritesWhatThePersistenceContextHolds() throws SQLException {
        em.getTransaction().begin();
        em.context().persist(newCustomer(1, "Vidocq"), customer);
        em.getTransaction().commit();
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void flushWritesWithoutCommitting() throws SQLException {
        em.getTransaction().begin();
        em.context().persist(newCustomer(1, "Vidocq"), customer);
        em.flush();
        assertThat(rows()).isZero(); // another connection does not see the uncommitted row
        em.getTransaction().rollback();
        assertThat(rows()).isZero();
    }

    @Test
    void aRollbackDetachesTheInstances() { // §3.3.2
        Object instance = newCustomer(1, "Vidocq");
        em.getTransaction().begin();
        em.context().persist(instance, customer);
        em.getTransaction().rollback();
        assertThat(em.context().size()).isZero();
    }

    @Test
    void aFlushThatFailsAtCommitRollsBack() throws SQLException {
        try (Statement other = keepAlive.createStatement()) {
            other.executeUpdate("insert into Customer (id, name) values (1, 'already')");
        }
        em.getTransaction().begin();
        em.context().persist(newCustomer(1, "Vidocq"), customer);
        em.context().persist(newCustomer(2, "Eugène"), customer);
        assertThatThrownBy(() -> em.getTransaction().commit()).isInstanceOf(RollbackException.class)
            .hasCauseInstanceOf(EntityExistsException.class);
        assertThat(em.getTransaction().isActive()).isFalse();
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void aTransactionWithNothingManagedNeedsNoDialect() { // the unit names a dialect that does not exist
        try (EntityManagerFactory other = new PersistenceConfiguration("nodialect")
                .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider").managedClass(Customer.class)
                .property(PersistenceConfiguration.JDBC_URL, url).property("io.vidocq.mansart.jpa.dialect", "nope")
                .createEntityManagerFactory()) {
            var manager = other.createEntityManager();
            manager.getTransaction().begin();
            manager.flush();
            manager.getTransaction().commit();
        }
    }

    @Test
    void clearDetachesEverything() {
        em.getTransaction().begin();
        Object instance = newCustomer(1, "Vidocq");
        em.context().persist(instance, customer);
        em.clear();
        assertThat(em.context().contains(instance)).isFalse();
        em.getTransaction().rollback();
    }
}
