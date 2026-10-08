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

import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.RollbackException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * The resource-local {@link EntityTransaction} of an entity manager (§7.5.2–7.5.4), over one JDBC connection. The
 * connection is taken when the transaction begins and given back when it ends, so an idle entity manager holds none.
 *
 * <p>Used by one thread, like the entity manager that owns it, with one exception: closing the factory abandons the
 * transaction from another thread. The connection is therefore handed over atomically: whoever takes it out of the
 * reference (commit, rollback or abandon) is the only one to end the transaction, so it is never rolled back or
 * closed twice, nor committed after being rolled back.
 */
final class ResourceLocalTransaction implements EntityTransaction {

    private final EntityManagerFactoryImpl factory;
    private final TransactionListener listener;
    /**
     * Held by the owning thread while it works on the connection (flush, commit, rollback), and by a closing factory
     * that abandons the transaction: the connection is never rolled back and released under a running flush. A
     * {@link ReentrantLock}, not a monitor: a virtual thread waiting for it parks without pinning its carrier.
     */
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicReference<Connection> connection = new AtomicReference<>();
    private volatile boolean rollbackOnly;
    private Integer timeout;

    ResourceLocalTransaction(EntityManagerFactoryImpl factory, TransactionListener listener) {
        this.factory = factory;
        this.listener = listener;
    }

    @Override
    public void begin() {
        if (connection.get() != null) {
            throw new IllegalStateException("The transaction is already active");
        }
        if (!factory.isOpen()) {
            throw new IllegalStateException("The EntityManagerFactory is closed");
        }
        Connection acquired = null;
        try {
            acquired = factory.connections().acquire();
            if (acquired.getAutoCommit()) {
                acquired.setAutoCommit(false);
            }
        } catch (SQLException e) {
            closeQuietly(acquired);
            throw new PersistenceException("Unable to begin a transaction: " + e.getMessage(), e);
        }
        rollbackOnly = false;
        connection.set(acquired);
        factory.register(this);
        // close() flips its flag before abandoning what is registered: either it saw this registration, or this sees the flag
        if (!factory.isOpen()) {
            abandon();
            throw new IllegalStateException("The EntityManagerFactory is closed");
        }
    }

    @Override
    public void commit() {
        lock.lock();
        try {
            if (!rollbackOnly) {
                try {
                    listener.beforeCommit(activeConnection());
                } catch (IllegalStateException e) {
                    throw e; // no active transaction
                } catch (RuntimeException e) {
                    Connection current = takeConnection();
                    try {
                        current.rollback();
                    } catch (SQLException rollbackFailure) {
                        e.addSuppressed(rollbackFailure);
                    }
                    listener.afterRollback();
                    throw end(current, new RollbackException("The flush before the commit failed: " + e.getMessage(), e));
                }
            }
            Connection current = takeConnection();
            if (rollbackOnly) {
                listener.afterRollback();
                try {
                    current.rollback();
                } catch (SQLException e) {
                    throw end(current, new RollbackException("The transaction was marked for rollback, and the rollback failed", e));
                }
                throw end(current, new RollbackException("The transaction was marked for rollback only"));
            }
            try {
                current.commit();
            } catch (SQLException e) {
                try {
                    current.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                listener.afterRollback();
                throw end(current, new RollbackException("The commit failed: " + e.getMessage(), e));
            }
            end(current, null);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void rollback() {
        lock.lock();
        try {
            Connection current = takeConnection();
            listener.afterRollback();
            try {
                current.rollback();
            } catch (SQLException e) {
                throw end(current, new PersistenceException("The rollback failed: " + e.getMessage(), e));
            }
            end(current, null);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void setRollbackOnly() {
        activeConnection();
        rollbackOnly = true;
    }

    @Override
    public boolean getRollbackOnly() {
        activeConnection();
        return rollbackOnly;
    }

    @Override
    public boolean isActive() {
        return connection.get() != null;
    }

    @Override
    public void setTimeout(Integer seconds) {
        this.timeout = seconds;
    }

    @Override
    public Integer getTimeout() {
        return timeout;
    }

    /** The connection of the active transaction; the entity manager runs its statements on it. */
    /** Runs {@code work} on the connection of the active transaction, which no closing factory can release meanwhile. */
    void onConnection(Consumer<Connection> work) {
        lock.lock();
        try {
            work.accept(activeConnection());
        } finally {
            lock.unlock();
        }
    }

    Connection connection() {
        return activeConnection();
    }

    /** Rolls back and releases an abandoned transaction, when its factory is closed; a no-op if it already ended. */
    void abandon() {
        lock.lock();
        try {
            Connection current = connection.getAndSet(null);
            if (current != null) {
                try {
                    current.rollback();
                } catch (SQLException ignored) {
                    // the factory is closing: nothing more can be done for this transaction
                }
                end(current, null);
            }
        } finally {
            lock.unlock();
        }
    }

    private Connection activeConnection() {
        Connection current = connection.get();
        if (current == null) {
            throw new IllegalStateException("The transaction is not active");
        }
        return current;
    }

    /** Takes the connection out: from here on, this thread alone ends the transaction. */
    private Connection takeConnection() {
        Connection current = connection.getAndSet(null);
        if (current == null) {
            throw new IllegalStateException("The transaction is not active");
        }
        return current;
    }

    /** Ends the transaction whatever happened: the connection goes back, the transaction is no longer active. */
    private <E extends RuntimeException> E end(Connection current, E failure) {
        rollbackOnly = false;
        factory.unregister(this);
        try {
            current.close();
        } catch (SQLException e) {
            if (failure != null) {
                failure.addSuppressed(e);
            } else {
                throw new PersistenceException("Unable to release the connection: " + e.getMessage(), e);
            }
        }
        return failure;
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // already failing
            }
        }
    }
}
