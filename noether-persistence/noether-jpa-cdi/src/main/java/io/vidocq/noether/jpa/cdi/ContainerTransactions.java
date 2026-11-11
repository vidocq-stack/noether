package io.vidocq.noether.jpa.cdi;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.TransactionSynchronizationRegistry;
import javax.sql.DataSource;

/**
 * What a JTA unit takes from its CDI container (§7.5): the {@link TransactionSynchronizationRegistry} bean and the
 * {@link DataSource} bean whose connections the container enlists in its transactions. Quarkus and Jakarta EE
 * servers have both; on Vidocq the runtime extension brings them; a bare CDI application declares them itself.
 */
final class ContainerTransactions {
    private ContainerTransactions() {
    }

    static TransactionSynchronizationRegistry registry(Instance<Object> lookup, String unit) {
        Instance<TransactionSynchronizationRegistry> registry = lookup.select(TransactionSynchronizationRegistry.class);
        if (registry.isUnsatisfied()) throw missing(unit, "jakarta.transaction.TransactionSynchronizationRegistry bean");
        return registry.get();
    }

    /** The {@code @Default} data source, or the one {@code @Named} after the unit's {@code jta-data-source}. */
    static DataSource dataSource(Instance<Object> lookup, String unit, String jtaDataSourceName) {
        boolean named = jtaDataSourceName != null && !jtaDataSourceName.isBlank();
        Instance<DataSource> source = named
            ? lookup.select(DataSource.class, NamedLiteral.of(jtaDataSourceName.strip())) : lookup.select(DataSource.class);
        if (source.isUnsatisfied()) {
            throw missing(unit, named ? "javax.sql.DataSource bean @Named(\"" + jtaDataSourceName.strip() + "\")"
                : "@Default javax.sql.DataSource bean");
        }
        return source.get();
    }

    private static PersistenceException missing(String unit, String what) {
        return new PersistenceException("Persistence unit " + unit + " is JTA and the CDI container has no " + what
            + ". A JTA unit needs a jakarta.transaction.TransactionSynchronizationRegistry bean and a javax.sql.DataSource"
            + " bean whose connections the container enlists in its transactions (@Named after the unit's"
            + " jta-data-source, @Default when it names none). Quarkus and Jakarta EE servers have both, the Vidocq"
            + " runtime extension brings them; otherwise produce them, or make the unit RESOURCE_LOCAL.");
    }
}
