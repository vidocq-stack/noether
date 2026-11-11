package io.vidocq.noether.jpa.cdi.moduleit;

import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.transaction.TransactionManager;
import javax.sql.DataSource;

/** A CDI container with its default data source only: no data source named after a {@code non-jta-data-source}. */
@Dependent
public class DefaultSource {
    @Produces @Singleton public DataSource defaultSource(TransactionManager manager) {
        return new EnlistingDataSource(DataSources.DEFAULT_URL, manager);
    }
}
