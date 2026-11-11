package io.vidocq.noether.jpa.cdi.moduleit;

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
 * The data source a container hands a JTA unit, as the Vidocq runtime extension will assemble it: a connection
 * obtained inside a transaction is enlisted in it through Mansart's JDBC XA adapter, so the transaction manager, never
 * Noether, completes it.
 */
final class EnlistingDataSource implements DataSource {
    private final String url;
    private final TransactionManager manager;

    EnlistingDataSource(String url, TransactionManager manager) {
        this.url = url;
        this.manager = manager;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(url, "sa", "");
        try {
            if (manager.getStatus() == Status.STATUS_ACTIVE
                    && !manager.getTransaction().enlistResource(new ConnectionXAResource(connection))) {
                throw new SQLException("Enlistment refused");
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
    @Override public void setLogWriter(PrintWriter out) { /* logs nothing: getLogWriter() stays null */ }
    @Override public void setLoginTimeout(int seconds) { /* no timeout: getLoginTimeout() stays 0 */ }
    @Override public int getLoginTimeout() { return 0; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("Not a wrapper"); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
}
