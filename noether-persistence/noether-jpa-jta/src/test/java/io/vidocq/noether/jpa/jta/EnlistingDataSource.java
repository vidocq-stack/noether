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

import io.vidocq.mansart.transactions.jdbc.ConnectionXAResource;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Test stand-in for a server's JTA data source: a connection obtained inside a transaction is enlisted in it by
 * the data source, so the transaction manager — never the persistence provider — commits or rolls it back.
 */
final class EnlistingDataSource implements DataSource {
    private final String url;
    private final TransactionManager manager;
    int enlisted;

    EnlistingDataSource(String url, TransactionManager manager) {
        this.url = url;
        this.manager = manager;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(url, "sa", "");
        try {
            if (manager.getStatus() == Status.STATUS_ACTIVE) {
                if (!manager.getTransaction().enlistResource(new ConnectionXAResource(connection))) {
                    throw new SQLException("Enlistment refused");
                }
                enlisted++;
            }
        } catch (SQLException e) {
            connection.close();
            throw e;
        } catch (Exception e) {
            connection.close();
            throw new SQLException(e);
        }
        return connection;
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
