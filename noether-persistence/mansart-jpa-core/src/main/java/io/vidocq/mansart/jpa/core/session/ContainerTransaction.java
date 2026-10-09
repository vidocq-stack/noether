package io.vidocq.mansart.jpa.core.session;

import io.vidocq.mansart.jpa.core.spi.TransactionIntegration;
import java.sql.Connection;
import java.util.function.Function;

/** Core-facing view; the integration owns enlistment, completion and connection release. */
final class ContainerTransaction implements SessionTransaction {
    private final TransactionIntegration.Session session;
    ContainerTransaction(TransactionIntegration.Session session) { this.session = session; }
    @Override public boolean isActive() { return session.active(); }
    @Override public boolean joined() { return session.joined(); }
    @Override public void join() { session.join(); }
    @Override public void setRollbackOnly() { session.rollbackOnly(); }
    @Override public boolean getRollbackOnly() { return session.isRollbackOnly(); }
    @Override public Connection connection() { return session.connection(); }
    @Override public <T> T onConnection(Function<Connection, T> work) { return session.onConnection(work); }
    private IllegalStateException managed() { return new IllegalStateException("A JTA transaction is controlled by the container"); }
    @Override public void begin() { throw managed(); }
    @Override public void commit() { throw managed(); }
    @Override public void rollback() { throw managed(); }
    @Override public void setTimeout(Integer timeout) { throw managed(); }
    @Override public Integer getTimeout() { throw managed(); }
}
