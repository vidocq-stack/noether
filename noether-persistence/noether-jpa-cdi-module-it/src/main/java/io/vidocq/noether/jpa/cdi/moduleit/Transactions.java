package io.vidocq.noether.jpa.cdi.moduleit;

import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * The transaction beans a CDI container gives a JTA unit: an actual transaction manager and its synchronization
 * registry, the one noether-jpa-cdi uses. On Vidocq the runtime extension brings them.
 */
@Dependent
public class Transactions {
    @Produces @Singleton public TransactionManager transactionManager() { return new MansartTransactionManager(); }

    @Produces @Singleton public TransactionSynchronizationRegistry registry(TransactionManager manager) {
        return new ManagerSynchronizationRegistry(manager);
    }
}
