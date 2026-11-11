package io.vidocq.noether.jpa.cdi;

import io.vidocq.noether.jpa.core.spi.TransactionIntegration;
import io.vidocq.mansart.transactions.jdbc.ConnectionXAResource;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.SynchronizationType;
import jakarta.persistence.TransactionRequiredException;
import jakarta.transaction.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Function;

/**
 * JTA lifecycle isolated from the provider core, reusing Mansart's delivered JDBC XA adapter. Every JDBC call, the
 * transaction manager's completion included, runs on the thread that makes it: a container's context bound to that
 * thread stays visible to the driver.
 */
public final class JtaIntegration implements TransactionIntegration {
    private final TransactionManager manager;

    public JtaIntegration(TransactionManager manager) { this.manager = Objects.requireNonNull(manager); }

    Transaction current() {
        try {
            Transaction transaction = manager.getTransaction();
            if (transaction == null) return null;
            int status = transaction.getStatus();
            return status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK ? transaction : null;
        } catch (SystemException failure) { throw new PersistenceException("Cannot resolve the current JTA transaction", failure); }
    }

    @Override public Session open(SynchronizationType synchronization, Connections connections, Completion completion) {
        return new JtaSession(synchronization, connections, completion);
    }

    private final class JtaSession implements Session {
        private final SynchronizationType synchronization;
        private final Connections connections;
        private final Completion completion;
        private volatile Transaction joined;
        private volatile Connection connection;

        JtaSession(SynchronizationType synchronization, Connections connections, Completion completion) {
            this.synchronization = synchronization;
            this.connections = connections;
            this.completion = completion;
        }

        @Override public boolean active() {
            Transaction current = current();
            if (joined == null && current != null && synchronization == SynchronizationType.SYNCHRONIZED) join();
            if (joined != null && current != null && joined != current) {
                throw new PersistenceException("The persistence context is associated with a different JTA transaction");
            }
            return joined != null && joined == current;
        }

        @Override public boolean joined() { return joined != null && joined == current(); }

        @Override public void join() {
            Transaction transaction = current();
            if (transaction == null) throw new TransactionRequiredException("No active JTA transaction to join");
            if (joined == transaction) return;
            if (joined != null) throw new PersistenceException("The persistence context is already associated with a different transaction");
            Connection acquired = acquire();
            var released = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable release = () -> {
                if (released.compareAndSet(false, true)) close(acquired);
            };
            var enlisted = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                transaction.registerSynchronization(new Synchronization() {
                    @Override public void beforeCompletion() {
                        if (enlisted.get()) completion.beforeCommit(acquired);
                    }
                    @Override public void afterCompletion(int status) {
                        try { if (enlisted.get()) completion.afterCompletion(status == Status.STATUS_COMMITTED); }
                        finally {
                            connection = null;
                            joined = null;
                            release.run();
                        }
                    }
                });
                if (!transaction.enlistResource(new ConnectionXAResource(acquired))) {
                    throw new PersistenceException("The JTA transaction refused the persistence resource");
                }
                enlisted.set(true);
                joined = transaction;
                connection = acquired;
            } catch (Exception failure) {
                joined = null;
                connection = null;
                try {
                    if (transaction.getStatus() == Status.STATUS_ACTIVE) transaction.setRollbackOnly();
                } catch (SystemException | RuntimeException marking) { failure.addSuppressed(marking); }
                try { release.run(); } catch (RuntimeException closing) { failure.addSuppressed(closing); }
                throw new PersistenceException("Unable to enlist persistence with JTA", failure);
            }
        }

        @Override public void rollbackOnly() {
            if (!joined()) return;
            try {
                if (joined.getStatus() == Status.STATUS_ACTIVE) joined.setRollbackOnly();
            } catch (SystemException failure) { throw new PersistenceException(failure); }
        }
        @Override public boolean isRollbackOnly() {
            try { return joined != null && joined.getStatus() == Status.STATUS_MARKED_ROLLBACK; }
            catch (SystemException failure) { throw new PersistenceException(failure); }
        }
        @Override public Connection connection() {
            if (!active()) throw new TransactionRequiredException("The persistence context is not joined");
            return connection;
        }
        @Override public <T> T onConnection(Function<Connection, T> work) {
            return work.apply(connection());
        }

        private Connection acquire() {
            try { return connections.acquire(); }
            catch (SQLException failure) { throw new PersistenceException("Unable to obtain a connection: " + failure.getMessage(), failure); }
        }
    }

    private static void close(Connection connection) {
        try { connection.close(); }
        catch (SQLException failure) { throw new PersistenceException("Unable to release a connection: " + failure.getMessage(), failure); }
    }
}
