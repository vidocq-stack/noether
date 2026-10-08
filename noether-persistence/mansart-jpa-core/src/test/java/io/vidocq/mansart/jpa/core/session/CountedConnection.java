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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

/** A connection that decrements its data source's open count when it is closed. Written out, not proxied. */
@SuppressWarnings({"deprecation", "removal"})
final class CountedConnection implements Connection {

    private final Connection delegate;
    private final AtomicInteger open;
    private boolean closed;

    CountedConnection(Connection delegate, AtomicInteger open) {
        this.delegate = delegate;
        this.open = open;
    }

    @Override
    public void close() throws SQLException {
        if (!closed) {
            closed = true;
            open.decrementAndGet();
        }
        delegate.close();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed || delegate.isClosed();
    }

    @Override
    public java.sql.Statement createStatement() throws java.sql.SQLException {
        return delegate.createStatement();
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String a0) throws java.sql.SQLException {
        return delegate.prepareStatement(a0);
    }

    @Override
    public java.sql.CallableStatement prepareCall(java.lang.String a0) throws java.sql.SQLException {
        return delegate.prepareCall(a0);
    }

    @Override
    public java.lang.String nativeSQL(java.lang.String a0) throws java.sql.SQLException {
        return delegate.nativeSQL(a0);
    }

    @Override
    public void setAutoCommit(boolean a0) throws java.sql.SQLException {
        delegate.setAutoCommit(a0);
    }

    @Override
    public boolean getAutoCommit() throws java.sql.SQLException {
        return delegate.getAutoCommit();
    }

    @Override
    public void commit() throws java.sql.SQLException {
        delegate.commit();
    }

    @Override
    public void rollback() throws java.sql.SQLException {
        delegate.rollback();
    }

    @Override
    public java.sql.DatabaseMetaData getMetaData() throws java.sql.SQLException {
        return delegate.getMetaData();
    }

    @Override
    public void setReadOnly(boolean a0) throws java.sql.SQLException {
        delegate.setReadOnly(a0);
    }

    @Override
    public boolean isReadOnly() throws java.sql.SQLException {
        return delegate.isReadOnly();
    }

    @Override
    public void setCatalog(java.lang.String a0) throws java.sql.SQLException {
        delegate.setCatalog(a0);
    }

    @Override
    public java.lang.String getCatalog() throws java.sql.SQLException {
        return delegate.getCatalog();
    }

    @Override
    public void setTransactionIsolation(int a0) throws java.sql.SQLException {
        delegate.setTransactionIsolation(a0);
    }

    @Override
    public int getTransactionIsolation() throws java.sql.SQLException {
        return delegate.getTransactionIsolation();
    }

    @Override
    public java.sql.SQLWarning getWarnings() throws java.sql.SQLException {
        return delegate.getWarnings();
    }

    @Override
    public void clearWarnings() throws java.sql.SQLException {
        delegate.clearWarnings();
    }

    @Override
    public java.sql.Statement createStatement(int a0, int a1) throws java.sql.SQLException {
        return delegate.createStatement(a0, a1);
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String a0, int a1, int a2) throws java.sql.SQLException {
        return delegate.prepareStatement(a0, a1, a2);
    }

    @Override
    public java.sql.CallableStatement prepareCall(java.lang.String a0, int a1, int a2) throws java.sql.SQLException {
        return delegate.prepareCall(a0, a1, a2);
    }

    @Override
    public java.util.Map<java.lang.String, java.lang.Class<?>> getTypeMap() throws java.sql.SQLException {
        return delegate.getTypeMap();
    }

    @Override
    public void setTypeMap(java.util.Map<java.lang.String, java.lang.Class<?>> a0) throws java.sql.SQLException {
        delegate.setTypeMap(a0);
    }

    @Override
    public void setHoldability(int a0) throws java.sql.SQLException {
        delegate.setHoldability(a0);
    }

    @Override
    public int getHoldability() throws java.sql.SQLException {
        return delegate.getHoldability();
    }

    @Override
    public java.sql.Savepoint setSavepoint() throws java.sql.SQLException {
        return delegate.setSavepoint();
    }

    @Override
    public java.sql.Savepoint setSavepoint(java.lang.String a0) throws java.sql.SQLException {
        return delegate.setSavepoint(a0);
    }

    @Override
    public void rollback(java.sql.Savepoint a0) throws java.sql.SQLException {
        delegate.rollback(a0);
    }

    @Override
    public void releaseSavepoint(java.sql.Savepoint a0) throws java.sql.SQLException {
        delegate.releaseSavepoint(a0);
    }

    @Override
    public java.sql.Statement createStatement(int a0, int a1, int a2) throws java.sql.SQLException {
        return delegate.createStatement(a0, a1, a2);
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String a0, int a1, int a2, int a3) throws java.sql.SQLException {
        return delegate.prepareStatement(a0, a1, a2, a3);
    }

    @Override
    public java.sql.CallableStatement prepareCall(java.lang.String a0, int a1, int a2, int a3) throws java.sql.SQLException {
        return delegate.prepareCall(a0, a1, a2, a3);
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String a0, int a1) throws java.sql.SQLException {
        return delegate.prepareStatement(a0, a1);
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String a0, int[] a1) throws java.sql.SQLException {
        return delegate.prepareStatement(a0, a1);
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String a0, java.lang.String[] a1) throws java.sql.SQLException {
        return delegate.prepareStatement(a0, a1);
    }

    @Override
    public java.sql.Clob createClob() throws java.sql.SQLException {
        return delegate.createClob();
    }

    @Override
    public java.sql.Blob createBlob() throws java.sql.SQLException {
        return delegate.createBlob();
    }

    @Override
    public java.sql.NClob createNClob() throws java.sql.SQLException {
        return delegate.createNClob();
    }

    @Override
    public java.sql.SQLXML createSQLXML() throws java.sql.SQLException {
        return delegate.createSQLXML();
    }

    @Override
    public boolean isValid(int a0) throws java.sql.SQLException {
        return delegate.isValid(a0);
    }

    @Override
    public void setClientInfo(java.lang.String a0, java.lang.String a1) throws java.sql.SQLClientInfoException {
        delegate.setClientInfo(a0, a1);
    }

    @Override
    public void setClientInfo(java.util.Properties a0) throws java.sql.SQLClientInfoException {
        delegate.setClientInfo(a0);
    }

    @Override
    public java.lang.String getClientInfo(java.lang.String a0) throws java.sql.SQLException {
        return delegate.getClientInfo(a0);
    }

    @Override
    public java.util.Properties getClientInfo() throws java.sql.SQLException {
        return delegate.getClientInfo();
    }

    @Override
    public java.sql.Array createArrayOf(java.lang.String a0, java.lang.Object[] a1) throws java.sql.SQLException {
        return delegate.createArrayOf(a0, a1);
    }

    @Override
    public java.sql.Struct createStruct(java.lang.String a0, java.lang.Object[] a1) throws java.sql.SQLException {
        return delegate.createStruct(a0, a1);
    }

    @Override
    public void setSchema(java.lang.String a0) throws java.sql.SQLException {
        delegate.setSchema(a0);
    }

    @Override
    public java.lang.String getSchema() throws java.sql.SQLException {
        return delegate.getSchema();
    }

    @Override
    public void abort(java.util.concurrent.Executor a0) throws java.sql.SQLException {
        delegate.abort(a0);
    }

    @Override
    public void setNetworkTimeout(java.util.concurrent.Executor a0, int a1) throws java.sql.SQLException {
        delegate.setNetworkTimeout(a0, a1);
    }

    @Override
    public int getNetworkTimeout() throws java.sql.SQLException {
        return delegate.getNetworkTimeout();
    }

    @Override
    public void beginRequest() throws java.sql.SQLException {
        delegate.beginRequest();
    }

    @Override
    public void endRequest() throws java.sql.SQLException {
        delegate.endRequest();
    }

    @Override
    public boolean setShardingKeyIfValid(java.sql.ShardingKey a0, java.sql.ShardingKey a1, int a2) throws java.sql.SQLException {
        return delegate.setShardingKeyIfValid(a0, a1, a2);
    }

    @Override
    public boolean setShardingKeyIfValid(java.sql.ShardingKey a0, int a1) throws java.sql.SQLException {
        return delegate.setShardingKeyIfValid(a0, a1);
    }

    @Override
    public void setShardingKey(java.sql.ShardingKey a0, java.sql.ShardingKey a1) throws java.sql.SQLException {
        delegate.setShardingKey(a0, a1);
    }

    @Override
    public void setShardingKey(java.sql.ShardingKey a0) throws java.sql.SQLException {
        delegate.setShardingKey(a0);
    }
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }
}
