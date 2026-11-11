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
 * https://joinup.ec.europa.eu/collection/eupl/eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.noether.jpa.core.model.build.fixtures.Customer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.RollbackException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * §7.5.1: a resource-local transaction is mapped onto the transaction of its JDBC connection. Once that transaction
 * is completed, the connection goes back to its {@code DataSource} in the auto-commit mode it came with: a pool that
 * finds a returned connection out of auto-commit mode has to roll it back before reuse, a statement the completed
 * transaction does not need.
 */
class AutoCommitRestoredTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private final List<String> events = new CopyOnWriteArrayList<>();
    private EntityManagerFactory emf;

    @AfterEach
    void close() {
        if (emf != null) emf.close();
    }

    @Test
    void aCommittedTransactionReturnsTheConnectionInAutoCommitMode() { // §7.5.1
        EntityManager em = factory(true).createEntityManager();
        em.getTransaction().begin();
        em.getTransaction().commit();

        assertThat(events).containsExactly("setAutoCommit(false)", "commit", "setAutoCommit(true)", "close");
    }

    @Test
    void aRolledBackTransactionReturnsTheConnectionInAutoCommitMode() { // §7.5.1
        EntityManager em = factory(true).createEntityManager();
        em.getTransaction().begin();
        em.getTransaction().rollback();

        assertThat(events).containsExactly("setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close");
    }

    @Test
    void aTransactionMarkedForRollbackReturnsTheConnectionInAutoCommitMode() { // §7.5.4
        EntityManager em = factory(true).createEntityManager();
        em.getTransaction().begin();
        em.getTransaction().setRollbackOnly();
        EntityTransaction transaction = em.getTransaction();

        assertThatThrownBy(transaction::commit).isInstanceOf(RollbackException.class);
        assertThat(events).containsExactly("setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close");
    }

    @Test
    void aConnectionHandedOutWithoutAutoCommitKeepsItsMode() { // the mode it came with is the DataSource's choice
        EntityManager em = factory(false).createEntityManager();
        em.getTransaction().begin();
        em.getTransaction().commit();

        assertThat(events).containsExactly("commit", "close");
    }

    private EntityManagerFactory factory(boolean autoCommit) {
        String url = "jdbc:h2:mem:autocommit-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        CountingDataSource source = new CountingDataSource(url) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                connection.setAutoCommit(autoCommit);
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setAutoCommit" -> events.add("setAutoCommit(" + args[0] + ")");
                            case "commit", "rollback", "close" -> events.add(method.getName());
                            default -> {
                                // not a call that completes the transaction or returns the connection
                            }
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            }
        };
        emf = new PersistenceConfiguration("autocommit").provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Customer.class).property("jakarta.persistence.nonJtaDataSource", source).createEntityManagerFactory();
        events.clear();
        return emf;
    }
}
