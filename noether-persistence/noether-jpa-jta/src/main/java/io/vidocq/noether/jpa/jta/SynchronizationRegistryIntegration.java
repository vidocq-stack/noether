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
import java.util.concurrent.atomic.AtomicReference;
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
 *
 * <p>Each unit of work takes a handle from the data source and closes it when the work ends, the flush before
 * completion included. Within a transaction the container hands out the connection it enlisted in it each time
 * (connection sharing). So no handle of Noether is open when a pool checks its connections at completion, whatever the
 * order of the interposed synchronizations, which JTA leaves open (BUG-20261111-05). Only the connection handed out as
 * such ({@code EntityManager.unwrap(Connection.class)}) stays open until Noether's synchronization.
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
        /** The connection handed out by {@link #connection()}, held until the transaction completes. */
        private final AtomicReference<Connection> handedOut = new AtomicReference<>();

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
            try {
                registry.registerInterposedSynchronization(completionOf());
            } catch (IllegalStateException e) {
                // a transaction marked for rollback, for instance, takes no new synchronization (JTA 2.0 §3.4.4)
                throw new PersistenceException("Unable to join the JTA transaction: " + e.getMessage(), e);
            }
            joined = current;
        }

        /** Flushes before the container completes the transaction and detaches after it. */
        private Synchronization completionOf() {
            return new Synchronization() {
                @Override
                public void beforeCompletion() {
                    // §7.9.1: flush before the container commits; a transaction marked for rollback is not flushed.
                    try {
                        if (registry.getTransactionStatus() == Status.STATUS_ACTIVE) {
                            onHandle(handle -> {
                                completion.beforeCommit(handle);
                                return null;
                            });
                        }
                    } finally {
                        releaseHandedOut();
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    try {
                        completion.afterCompletion(status == Status.STATUS_COMMITTED);
                    } finally {
                        joined = null;
                        releaseHandedOut(); // a rollback runs no beforeCompletion
                    }
                }
            };
        }

        @Override
        public void rollbackOnly() {
            if (joined()) registry.setRollbackOnly();
        }

        @Override
        public boolean isRollbackOnly() {
            return joined() && registry.getRollbackOnly();
        }

        /** The connection handed out as such: the same one until the transaction completes. */
        @Override
        public Connection connection() {
            requireActive();
            Connection current = handedOut.get();
            if (current == null) {
                current = acquire();
                handedOut.set(current);
            }
            return current;
        }

        /** A unit of work on a handle of its own, closed when the work ends. */
        @Override
        public <T> T onConnection(Function<Connection, T> work) {
            requireActive();
            return onHandle(work);
        }

        private void requireActive() {
            if (!active()) throw new TransactionRequiredException("The persistence context is not joined to a transaction");
        }

        private <T> T onHandle(Function<Connection, T> work) {
            Connection handle = acquire();
            try {
                return work.apply(handle);
            } finally {
                close(handle);
            }
        }

        /** The container enlists the connection in the transaction when its data source hands it out. */
        private Connection acquire() {
            try {
                return connections.acquire();
            } catch (SQLException e) {
                throw new PersistenceException("Unable to obtain a connection from the JTA data source: " + e.getMessage(), e);
            }
        }

        private void releaseHandedOut() {
            Connection current = handedOut.getAndSet(null);
            if (current != null) close(current);
        }

        /** Closing hands the handle back to the container, which completes the connection with the transaction. */
        private static void close(Connection handle) {
            try {
                handle.close();
            } catch (SQLException e) {
                System.getLogger(SynchronizationRegistryIntegration.class.getName())
                    .log(System.Logger.Level.WARNING, "Unable to hand a JTA connection back to the data source", e);
            }
        }
    }
}
