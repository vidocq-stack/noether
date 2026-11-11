package io.vidocq.noether.jpa.cdi.moduleit;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** A data source whose connections take part in no JTA transaction: the one who takes a connection commits it. */
final class NonJtaDataSource implements DataSource {
    private final String url;

    NonJtaDataSource(String url) {
        this.url = url;
    }

    @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url, "sa", ""); }
    @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter out) { /* logs nothing: getLogWriter() stays null */ }
    @Override public void setLoginTimeout(int seconds) { /* no timeout: getLoginTimeout() stays 0 */ }
    @Override public int getLoginTimeout() { return 0; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("Not a wrapper"); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
}
