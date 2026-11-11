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
package io.vidocq.noether.jpa.cdi;

import io.vidocq.mansart.transactions.core.SinglePhaseResource;
import io.vidocq.mansart.transactions.jdbc.ConnectionXAResource;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import javax.sql.DataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

/**
 * Test stand-in for a server's JTA data source. The first connection obtained inside a transaction is enlisted in it,
 * through Mansart's JDBC adapter; within that transaction the data source hands out handles on that same connection
 * (connection sharing), and the transaction manager, never the persistence provider, commits or rolls it back. The
 * connection is closed once the transaction is completed.
 */
final class EnlistingDataSource implements DataSource {
    private final String url;
    private final TransactionManager manager;
    private final Map<Transaction, Connection> enlisted = new ConcurrentHashMap<>();

    EnlistingDataSource(String url, TransactionManager manager) {
        this.url = url;
        this.manager = manager;
    }

    @Override
    public Connection getConnection() throws SQLException {
        try {
            int status = manager.getStatus();
            Transaction transaction = manager.getTransaction();
            Connection shared = transaction == null ? null : enlisted.get(transaction);
            if (shared != null) return new ConnectionHandle(shared);
            if (status != Status.STATUS_ACTIVE) return DriverManager.getConnection(url, "sa", "");
            return new ConnectionHandle(enlist(transaction));
        } catch (SystemException e) {
            throw new SQLException(e);
        }
    }

    private Connection enlist(Transaction transaction) throws SQLException {
        Connection connection = DriverManager.getConnection(url, "sa", "");
        try {
            if (!transaction.enlistResource(new EnlistedConnection(transaction, connection))) {
                throw new SQLException("Enlistment refused");
            }
        } catch (SQLException e) {
            connection.close();
            throw e;
        } catch (Exception e) {
            connection.close();
            throw new SQLException(e);
        }
        enlisted.put(transaction, connection);
        return connection;
    }

    /** Mansart's JDBC adapter; once the transaction manager completed the connection, the data source closes it. */
    private final class EnlistedConnection implements SinglePhaseResource {
        private final Transaction transaction;
        private final Connection connection;
        private final ConnectionXAResource resource;

        EnlistedConnection(Transaction transaction, Connection connection) {
            this.transaction = transaction;
            this.connection = connection;
            this.resource = new ConnectionXAResource(connection);
        }

        @Override
        public void commit(Xid xid, boolean onePhase) throws XAException {
            try {
                resource.commit(xid, onePhase);
            } finally {
                completed();
            }
        }

        @Override
        public void rollback(Xid xid) throws XAException {
            try {
                resource.rollback(xid);
            } finally {
                completed();
            }
        }

        private void completed() throws XAException {
            enlisted.remove(transaction);
            try {
                connection.close();
            } catch (SQLException e) {
                XAException failure = new XAException(XAException.XAER_RMERR);
                failure.initCause(e);
                throw failure;
            }
        }

        @Override public void start(Xid xid, int flags) throws XAException { resource.start(xid, flags); }
        @Override public void end(Xid xid, int flags) throws XAException { resource.end(xid, flags); }
        @Override public int prepare(Xid xid) throws XAException { return resource.prepare(xid); }
        @Override public void forget(Xid xid) throws XAException { resource.forget(xid); }
        @Override public Xid[] recover(int flag) throws XAException { return resource.recover(flag); }
        @Override public boolean isSameRM(XAResource other) { return other == this; }
        @Override public int getTransactionTimeout() throws XAException { return resource.getTransactionTimeout(); }
        @Override public boolean setTransactionTimeout(int seconds) throws XAException { return resource.setTransactionTimeout(seconds); }
    }

    @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter out) { /* this stand-in logs nothing: getLogWriter() stays null */ }
    @Override public void setLoginTimeout(int seconds) { /* this stand-in has no timeout: getLoginTimeout() stays 0 */ }
    @Override public int getLoginTimeout() { return 0; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("Not a wrapper"); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
}
