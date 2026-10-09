package io.vidocq.mansart.jpa.cdi;

import io.vidocq.mansart.jpa.core.spi.JdbcExecution;
import io.vidocq.mansart.jpa.core.spi.TransactionIntegration;
import io.vidocq.mansart.transactions.jdbc.ConnectionXAResource;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.SynchronizationType;
import jakarta.persistence.TransactionRequiredException;
import jakarta.transaction.*;
import java.sql.Connection;
import java.util.Objects;
import java.util.function.Function;
import javax.transaction.xa.*;

/** JTA lifecycle isolated from the provider core, reusing Mansart's delivered JDBC XA adapter. */
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
            Connection acquired = JdbcExecution.call(connections::acquire);
            var released = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable release = () -> {
                if (released.compareAndSet(false, true)) JdbcExecution.call(() -> { acquired.close(); return null; });
            };
            var enlisted = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                transaction.registerSynchronization(new Synchronization() {
                    @Override public void beforeCompletion() {
                        if (enlisted.get()) JdbcExecution.call(() -> { completion.beforeCommit(acquired); return null; });
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
                if (!transaction.enlistResource(new VirtualResource(new ConnectionXAResource(acquired)))) {
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
            Connection active = connection();
            return JdbcExecution.call(() -> work.apply(active));
        }
    }

    /** All JDBC adapter calls, including TM-driven completion, execute on virtual threads. */
    private record VirtualResource(ConnectionXAResource delegate) implements io.vidocq.mansart.transactions.core.SinglePhaseResource {
        private interface XaWork<T> { T run() throws XAException; }
        private <T> T call(XaWork<T> work) throws XAException {
            try { return JdbcExecution.call(work::run); }
            catch (PersistenceException failure) {
                if (failure.getCause() instanceof XAException xa) throw xa;
                XAException xa = new XAException(XAException.XAER_RMERR);
                xa.initCause(failure);
                throw xa;
            }
        }
        @Override public void start(Xid xid, int flags) throws XAException { call(() -> { delegate.start(xid, flags); return null; }); }
        @Override public void end(Xid xid, int flags) throws XAException { call(() -> { delegate.end(xid, flags); return null; }); }
        @Override public int prepare(Xid xid) throws XAException { return call(() -> delegate.prepare(xid)); }
        @Override public void commit(Xid xid, boolean onePhase) throws XAException { call(() -> { delegate.commit(xid, onePhase); return null; }); }
        @Override public void rollback(Xid xid) throws XAException { call(() -> { delegate.rollback(xid); return null; }); }
        @Override public void forget(Xid xid) throws XAException { call(() -> { delegate.forget(xid); return null; }); }
        @Override public Xid[] recover(int flags) throws XAException { return call(() -> delegate.recover(flags)); }
        @Override public boolean isSameRM(XAResource other) throws XAException {
            return other instanceof VirtualResource resource && delegate.isSameRM(resource.delegate);
        }
        @Override public int getTransactionTimeout() throws XAException { return delegate.getTransactionTimeout(); }
        @Override public boolean setTransactionTimeout(int timeout) throws XAException { return delegate.setTransactionTimeout(timeout); }
    }
}
