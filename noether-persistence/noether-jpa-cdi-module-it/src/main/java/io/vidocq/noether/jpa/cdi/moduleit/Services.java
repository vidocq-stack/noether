package io.vidocq.noether.jpa.cdi.moduleit;

import io.vidocq.noether.jpa.cdi.PersistenceUnitBootstrap;
import io.vidocq.noether.jpa.core.spi.TransactionIntegration;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.persistence.*;
import jakarta.transaction.TransactionManager;

/**
 * Real container configuration: production bootstrap is supplied through an SPI, with one JTA data source per unit
 * whose connections the container's transaction manager enlists.
 */
@Dependent
public class Services {
    @Produces @Singleton public PersistenceUnitBootstrap persistenceUnits(TransactionManager manager) {
        return (unit, transactions) -> new PersistenceConfiguration(unit).managedClass(Record.class)
            .transactionType(PersistenceUnitTransactionType.JTA)
            .property("jakarta.persistence.jtaDataSource",
                new EnlistingDataSource("jdbc:h2:mem:container-" + unit + ";DB_CLOSE_DELAY=-1", manager))
            .property(TransactionIntegration.PROPERTY, transactions)
            .property("jakarta.persistence.schema-generation.database.action", "drop-and-create").createEntityManagerFactory();
    }
}
