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
import java.util.List;

/** Persists entities whose identifiers the TABLE generator allocates through the unit's non-JTA data source. */
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

    /** The identifier is allocated and the INSERT flushed, then the transaction rolls back. */
    @Transactional
    public long addThenFail(String code, long[] allocated) {
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
