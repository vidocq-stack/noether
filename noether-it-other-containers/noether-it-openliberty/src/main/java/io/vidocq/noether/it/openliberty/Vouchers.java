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
package io.vidocq.noether.it.openliberty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import java.util.List;

/**
 * TABLE identifiers in Liberty's transactions (BUG-20261111-04). Liberty enlists every connection of
 * {@code jdbc/library} and refuses a commit on it (DSRA9250E), so the unit allocates its blocks through its
 * {@code non-jta-data-source}, {@code jdbc/allocations}.
 */
@ApplicationScoped
public class Vouchers {

    @PersistenceContext(unitName = "library")
    EntityManager em;

    @Transactional
    public long add(String code) {
        Voucher voucher = new Voucher(code);
        em.persist(voucher);
        em.flush();
        return voucher.getId();
    }

    /** Keeps the identifier the rolled-back transaction was given, for the caller to compare. */
    @Transactional
    public void addThenFail(String code, long[] allocated) {
        Voucher voucher = new Voucher(code);
        em.persist(voucher);
        em.flush();
        allocated[0] = voucher.getId();
        throw new IllegalStateException("rolled back on purpose");
    }

    @Transactional
    public List<Long> ids() {
        return em.createQuery("select v.id from Voucher v order by v.id", Long.class).getResultList();
    }
}
