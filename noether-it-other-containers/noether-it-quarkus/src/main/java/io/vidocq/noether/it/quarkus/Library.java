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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceUnit;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Plain Jakarta code on Quarkus: a transaction-scoped persistence context injected through Noether's build
 * compatible extension, transactions demarcated by Narayana's {@code @Transactional} interceptor, the JTA unit on
 * Quarkus' default Agroal data source.
 */
@ApplicationScoped
public class Library {

    @PersistenceContext(unitName = "library")
    EntityManager em;

    @PersistenceUnit(unitName = "library")
    EntityManagerFactory factory;

    /** The application's only unit, named nowhere: the same unit, so the same factory and context (BUG-20261113-03). */
    @PersistenceContext
    EntityManager unnamed;

    @PersistenceUnit
    EntityManagerFactory unnamedFactory;

    @Inject
    TransactionSynchronizationRegistry registry;

    /** Quarkus' default data source (Agroal): the one Noether takes for the JTA unit. */
    @Inject
    DataSource dataSource;

    public EntityManagerFactory factory() {
        return factory;
    }

    public EntityManagerFactory unnamedFactory() {
        return unnamedFactory;
    }

    /** Persists through the context that names the unit, then asks the one that names none (§7.6.4). */
    @Transactional
    public boolean unnamedContextManages(String title) {
        Book book = new Book(title);
        em.persist(book);
        return unnamed.contains(book);
    }

    @Transactional
    public long add(String title) {
        Book book = new Book(title);
        em.persist(book);
        em.flush();
        return book.getId();
    }

    /** The INSERT reaches the database, then the transaction is marked for rollback: Narayana rolls it back. */
    @Transactional
    public void addThenMarkRollbackOnly(String title) {
        em.persist(new Book(title));
        em.flush();
        registry.setRollbackOnly();
    }

    /** No flush here: the persistence context writes the change before Narayana commits (§7.9.1). */
    @Transactional
    public void rename(long id, String title) {
        em.find(Book.class, id).setTitle(title);
    }

    /**
     * The application reads through the data source before its first use of the persistence context in the transaction,
     * so Agroal's synchronization comes before Noether's. Noether writes the change before Narayana commits.
     */
    @Transactional
    public void readThenRename(long id, String title) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM noether_book")) {
            result.next();
        }
        em.find(Book.class, id).setTitle(title);
    }

    /** §3.11.2: in the AUTO flush mode the change reaches the database before the query of the same transaction. */
    @Transactional
    public long renameThenCount(long id, String title) {
        em.find(Book.class, id).setTitle(title);
        return countTitles(title);
    }

    /**
     * The H2 sessions of two statements of Noether and of a connection the application takes from the data source, in
     * the same transaction: Agroal hands out the connection it enlisted in the transaction again.
     */
    @Transactional
    public long[] sessions() throws SQLException {
        long first = sessionOfNoether();
        long second = sessionOfNoether();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT SESSION_ID()")) {
            result.next();
            return new long[] {first, second, result.getLong(1)};
        }
    }

    private long sessionOfNoether() {
        return ((Number) em.createNativeQuery("SELECT SESSION_ID()").getSingleResult()).longValue();
    }

    /** What the database holds, read straight from the data source outside any transaction. */
    public long committedRows(String title) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM noether_book WHERE title = ?")) {
            statement.setString(1, title);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    /** The INSERT reaches the database (flush) before the exception: only the rollback can undo it. */
    @Transactional
    public void addThenFail(String title) {
        em.persist(new Book(title));
        em.flush();
        throw new IllegalStateException("rolled back on purpose");
    }

    @Transactional
    public long count(String title) {
        return countTitles(title);
    }

    private long countTitles(String title) {
        return em.createQuery("select count(b) from Book b where b.title = :title", Long.class)
            .setParameter("title", title)
            .getSingleResult();
    }

    /** Outside any transaction: the transaction-scoped context reads and detaches at once (§7.6.2). */
    public String title(long id) {
        Book book = em.find(Book.class, id);
        return book == null ? "missing" : book.getTitle();
    }
}
