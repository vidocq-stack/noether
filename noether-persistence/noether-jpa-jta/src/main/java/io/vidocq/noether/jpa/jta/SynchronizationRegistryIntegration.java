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

import io.vidocq.noether.jpa.core.spi.TransactionIntegration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.SynchronizationType;
import jakarta.persistence.TransactionRequiredException;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Function;

/**
 * JTA through the container's {@link TransactionSynchronizationRegistry} (JTA 2.0 §3.4.4), for a unit whose JTA data
 * source the container manages (§7.5, §9.6). The container enlists each connection its data source hands out in a
 * transaction and completes it: this bridge never enlists a resource and never commits, rolls back or switches the
 * auto-commit mode of a connection. It joins a persistence context to the current transaction with an interposed
 * synchronization, which flushes before completion and detaches after it.
 *
 * <p>The connection of a joined context is acquired on the caller's thread, the one the container associates with the
 * transaction, and used there: no work moves to another thread, where a container would see no transaction.
 */
public final class SynchronizationRegistryIntegration implements TransactionIntegration {
    private final TransactionSynchronizationRegistry registry;

    public SynchronizationRegistryIntegration(TransactionSynchronizationRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    @Override
    public Session open(SynchronizationType synchronization, Connections connections, Completion completion) {
        return new RegistrySession(synchronization, connections, completion);
    }

    private final class RegistrySession implements Session {
        private final SynchronizationType synchronization;
        private final Connections connections;
        private final Completion completion;
        private volatile Object joined;
        private volatile Connection connection;

        RegistrySession(SynchronizationType synchronization, Connections connections, Completion completion) {
            this.synchronization = synchronization;
            this.connections = connections;
            this.completion = completion;
        }

        /** The key of the current transaction while it can still do work, otherwise {@code null}. */
        private Object currentTransaction() {
            int status = registry.getTransactionStatus();
            return status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK ? registry.getTransactionKey() : null;
        }

        /** §7.6.1: a synchronized context joins the current transaction on its first use in it. */
        @Override
        public boolean active() {
            Object current = currentTransaction();
            if (joined == null && current != null && synchronization == SynchronizationType.SYNCHRONIZED) join();
            if (joined != null && current != null && !joined.equals(current)) {
                throw new PersistenceException("The persistence context is associated with a different JTA transaction");
            }
            return joined != null && joined.equals(current);
        }

        @Override
        public boolean joined() {
            return joined != null && joined.equals(currentTransaction());
        }

        @Override
        public void join() {
            Object current = currentTransaction();
            if (current == null) throw new TransactionRequiredException("No active JTA transaction to join");
            if (current.equals(joined)) return;
            if (joined != null) throw new PersistenceException("The persistence context is already associated with a different transaction");
            registry.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                    // §7.9.1: flush before the container commits; a transaction marked for rollback is not flushed.
                    if (registry.getTransactionStatus() == Status.STATUS_ACTIVE) completion.beforeCommit(connection());
                }

                @Override
                public void afterCompletion(int status) {
                    try {
                        completion.afterCompletion(status == Status.STATUS_COMMITTED);
                    } finally {
                        joined = null;
                        release();
                    }
                }
            });
            joined = current;
        }

        @Override
        public void rollbackOnly() {
            if (joined()) registry.setRollbackOnly();
        }

        @Override
        public boolean isRollbackOnly() {
            return joined() && registry.getRollbackOnly();
        }

        /** The container enlists the connection in the transaction when its data source hands it out. */
        @Override
        public Connection connection() {
            if (!active()) throw new TransactionRequiredException("The persistence context is not joined to a transaction");
            Connection current = connection;
            if (current == null) {
                try {
                    current = connections.acquire();
                } catch (SQLException e) {
                    throw new PersistenceException("Unable to obtain a connection from the JTA data source: " + e.getMessage(), e);
                }
                connection = current;
            }
            return current;
        }

        @Override
        public <T> T onConnection(Function<Connection, T> work) {
            return work.apply(connection());
        }

        /** Closing hands the connection back to the container, which already completed it. */
        private void release() {
            Connection current = connection;
            connection = null;
            if (current == null) return;
            try {
                current.close();
            } catch (SQLException e) {
                System.getLogger(SynchronizationRegistryIntegration.class.getName())
                    .log(System.Logger.Level.WARNING, "Unable to release a JTA connection after completion", e);
            }
        }
    }
}
