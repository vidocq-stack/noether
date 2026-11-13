package io.vidocq.noether.jpa.cdi;

import io.vidocq.noether.jpa.core.spi.PersistenceDescriptors;
import io.vidocq.noether.jpa.core.spi.TransactionIntegration;
import io.vidocq.noether.jpa.jta.SynchronizationRegistryIntegration;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.*;
import jakarta.persistence.*;
import java.util.*;

/**
 * Factory ownership is the CDI deployment's, not a static per-JVM cache. A JTA unit of {@code persistence.xml} runs on
 * the container's data source bean and transaction synchronization registry bean ({@link ContainerTransactions});
 * a resource-local unit needs neither. A {@link PersistenceUnitBootstrap} bean supplies its own data sources and gets
 * the registry's bridge.
 */
public final class PersistenceFactoryCreator implements SyntheticBeanCreator<EntityManagerFactory> {
    @Override public EntityManagerFactory create(Instance<Object> lookup, Parameters params) {
        String unit = params.get("unit", String.class);
        var bootstrap = lookup.select(PersistenceUnitBootstrap.class);
        if (!bootstrap.isUnsatisfied()) {
            return new ContainerEntityManagerFactory(bootstrap.get().create(unit, bridge(lookup, unit)));
        }
        ClassLoader loader = loader();
        String name = unit.isEmpty() ? PersistenceDescriptors.defaultUnitName(loader) : unit;
        Map<String, Object> properties = PersistenceDescriptors.transactions(name, loader)
            .filter(PersistenceDescriptors.UnitTransactions::jta)
            .map(transactions -> jtaProperties(lookup, name, transactions))
            .orElse(Map.of());
        return new ContainerEntityManagerFactory(Persistence.createEntityManagerFactory(name, properties));
    }

    /**
     * §9.6: the container's JTA data source and its transaction bridge, as a container passes them to the provider, and
     * the unit's non-JTA data source when it names one (BUG-20261111-04: its TABLE generators allocate through it).
     */
    private static Map<String, Object> jtaProperties(Instance<Object> lookup, String unit, PersistenceDescriptors.UnitTransactions transactions) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(TransactionIntegration.PROPERTY, bridge(lookup, unit));
        properties.put("jakarta.persistence.jtaDataSource",
            ContainerTransactions.dataSource(lookup, unit, transactions.jtaDataSourceName()));
        String nonJta = transactions.nonJtaDataSourceName();
        if (nonJta != null && !nonJta.isBlank()) {
            properties.put("jakarta.persistence.nonJtaDataSource", ContainerTransactions.nonJtaDataSource(lookup, unit, nonJta));
        }
        return properties;
    }

    private static TransactionIntegration bridge(Instance<Object> lookup, String unit) {
        return new SynchronizationRegistryIntegration(ContainerTransactions.registry(lookup, unit));
    }

    static ClassLoader loader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? PersistenceFactoryCreator.class.getClassLoader() : loader;
    }
}
