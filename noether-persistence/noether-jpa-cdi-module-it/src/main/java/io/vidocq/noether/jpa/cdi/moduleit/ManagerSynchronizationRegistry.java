package io.vidocq.noether.jpa.cdi.moduleit;

import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The container's registry (JTA 2.0 §3.4.4) over the actual Mansart transaction manager of this vehicle. */
final class ManagerSynchronizationRegistry implements TransactionSynchronizationRegistry {
    private final TransactionManager manager;
    private final Map<Transaction, Map<Object, Object>> resources = new ConcurrentHashMap<>();

    ManagerSynchronizationRegistry(TransactionManager manager) {
        this.manager = manager;
    }

    private Transaction current() {
        try {
            return manager.getTransaction();
        } catch (SystemException e) {
            throw new IllegalStateException(e);
        }
    }

    private Transaction required() {
        Transaction transaction = current();
        if (transaction == null) throw new IllegalStateException("No transaction");
        return transaction;
    }

    @Override public Object getTransactionKey() { return current(); }
    @Override public void putResource(Object key, Object value) { resources.computeIfAbsent(required(), _ -> new ConcurrentHashMap<>()).put(key, value); }
    @Override public Object getResource(Object key) { return resources.getOrDefault(required(), Map.of()).get(key); }

    @Override public void registerInterposedSynchronization(Synchronization synchronization) {
        try {
            required().registerSynchronization(synchronization);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override public int getTransactionStatus() {
        try {
            return manager.getStatus();
        } catch (SystemException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override public void setRollbackOnly() {
        try {
            required().setRollbackOnly();
        } catch (SystemException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override public boolean getRollbackOnly() {
        required();
        return getTransactionStatus() == Status.STATUS_MARKED_ROLLBACK;
    }
}
