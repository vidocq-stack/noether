package io.vidocq.mansart.jpa.core.session;

import jakarta.persistence.EntityTransaction;
import java.sql.Connection;
import java.util.function.Function;

/** Execution/lifecycle shared by resource-local transactions and the isolated container transaction bridge. */
interface SessionTransaction extends EntityTransaction {
    <T> T onConnection(Function<Connection, T> work);
    Connection connection();
    default void join() {
        if (!isActive()) throw new jakarta.persistence.TransactionRequiredException("No active transaction to join");
    }
    default boolean joined() { return isActive(); }
}
