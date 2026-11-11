package io.vidocq.noether.jpa.cdi.moduleit;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.transaction.TransactionManager;
import javax.sql.DataSource;

/**
 * The data sources of a CDI container, whose connections it enlists in its transactions: the default one, and one a
 * persistence unit names in its {@code jta-data-source}.
 */
@Dependent
public class DataSources {
    public static final String DEFAULT_URL = "jdbc:h2:mem:moduleit-default;DB_CLOSE_DELAY=-1";
    public static final String LEDGER_URL = "jdbc:h2:mem:moduleit-ledger;DB_CLOSE_DELAY=-1";

    @Produces @Singleton public DataSource defaultSource(TransactionManager manager) {
        return new EnlistingDataSource(DEFAULT_URL, manager);
    }

    @Produces @Singleton @Named("ledger") @Ledger public DataSource ledgerSource(TransactionManager manager) {
        return new EnlistingDataSource(LEDGER_URL, manager);
    }
}
