package io.vidocq.mansart.jpa.cdi.moduleit;

import io.vidocq.mansart.jpa.cdi.PersistenceUnitBootstrap;
import io.vidocq.mansart.jpa.core.spi.TransactionIntegration;
import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.persistence.*;
import jakarta.transaction.TransactionManager;

/** Real container configuration and actual transaction manager; production bootstrap is supplied through an SPI. */
@Dependent
public class Services {
    private final TransactionManager manager = new MansartTransactionManager();
    @Produces @Singleton public TransactionManager transactionManager() { return manager; }
    @Produces @Singleton public PersistenceUnitBootstrap persistenceUnits() {
        return (unit, transactions) -> new PersistenceConfiguration(unit).managedClass(Record.class)
            .transactionType(PersistenceUnitTransactionType.JTA)
            .property("jakarta.persistence.jdbc.url", "jdbc:h2:mem:container-" + unit + ";DB_CLOSE_DELAY=-1")
            .property(TransactionIntegration.PROPERTY, transactions)
            .property("jakarta.persistence.schema-generation.database.action", "drop-and-create").createEntityManagerFactory();
    }
}
