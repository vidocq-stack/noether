package io.vidocq.mansart.jpa.core.spi;

import jakarta.persistence.SynchronizationType;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;

/** Container transaction bridge. No transaction-manager or CDI API enters the provider core. */
public interface TransactionIntegration {
    String PROPERTY = "io.vidocq.mansart.jpa.transaction-integration";

    @FunctionalInterface interface Connections { Connection acquire() throws SQLException; }
    interface Completion {
        void beforeCommit(Connection connection);
        void afterCompletion(boolean committed);
    }
    interface Session {
        boolean active();
        boolean joined();
        void join();
        void rollbackOnly();
        boolean isRollbackOnly();
        Connection connection();
        <T> T onConnection(Function<Connection, T> work);
    }
    Session open(SynchronizationType synchronization, Connections connections, Completion completion);
}
