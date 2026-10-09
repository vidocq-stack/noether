package io.vidocq.mansart.jpa.core.spi;

import jakarta.persistence.PersistenceException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

/** Executes blocking provider/integration work on a virtual thread without retaining thread-local context. */
public final class JdbcExecution {
    private JdbcExecution() {}

    public static <T> T call(Callable<T> work) {
        if (Thread.currentThread().isVirtual()) {
            try {
                return work.call();
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new PersistenceException("JDBC work failed", failure);
            }
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                return executor.submit(work).get();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new PersistenceException("Interrupted while waiting for JDBC work", failure);
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                if (failure.getCause() instanceof Error error) throw error;
                throw new PersistenceException("JDBC work failed", failure.getCause());
            }
        }
    }
}
