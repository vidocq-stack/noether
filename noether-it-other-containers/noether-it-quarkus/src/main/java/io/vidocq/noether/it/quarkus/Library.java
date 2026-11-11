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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

/**
 * Plain Jakarta code on Quarkus: a transaction-scoped persistence context injected through Noether's build
 * compatible extension, transactions demarcated by Narayana's {@code @Transactional} interceptor.
 */
@ApplicationScoped
public class Library {

    @PersistenceContext(unitName = "library")
    EntityManager em;

    @Transactional
    public long add(String title) {
        Book book = new Book(title);
        em.persist(book);
        em.flush();
        return book.getId();
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
