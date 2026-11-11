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
package io.vidocq.noether.jpa.jta;

import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.util.HashMap;
import java.util.Map;

/** Test stand-in for a server's registry (JTA 2.0 §3.4.4) over a real transaction manager. */
final class RegistryOverTransactionManager implements TransactionSynchronizationRegistry {
    private final TransactionManager manager;
    private final Map<Transaction, Map<Object, Object>> resources = new HashMap<>();
    int interposed;

    RegistryOverTransactionManager(TransactionManager manager) {
        this.manager = manager;
    }

    private Transaction current() {
        try {
            return manager.getTransaction();
        } catch (SystemException e) {
            throw new IllegalStateException(e);
        }
    }

    private Transaction required() {
        Transaction transaction = current();
        if (transaction == null) throw new IllegalStateException("No transaction");
        return transaction;
    }

    @Override public Object getTransactionKey() { return current(); }
    @Override public void putResource(Object key, Object value) { resources.computeIfAbsent(required(), t -> new HashMap<>()).put(key, value); }
    @Override public Object getResource(Object key) { return resources.getOrDefault(required(), Map.of()).get(key); }

    @Override public void registerInterposedSynchronization(Synchronization synchronization) {
        try {
            required().registerSynchronization(synchronization);
            interposed++;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override public int getTransactionStatus() {
        try {
            return manager.getStatus();
        } catch (SystemException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override public void setRollbackOnly() {
        try {
            required().setRollbackOnly();
        } catch (SystemException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override public boolean getRollbackOnly() {
        required();
        return getTransactionStatus() == Status.STATUS_MARKED_ROLLBACK;
    }
}
