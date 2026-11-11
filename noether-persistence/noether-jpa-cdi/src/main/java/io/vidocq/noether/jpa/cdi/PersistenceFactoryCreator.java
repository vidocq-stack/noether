package io.vidocq.noether.jpa.cdi;

import io.vidocq.noether.jpa.core.spi.PersistenceDescriptors;
import io.vidocq.noether.jpa.core.spi.TransactionIntegration;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.*;
import jakarta.persistence.*;
import jakarta.transaction.TransactionManager;
import java.util.*;

/** Factory ownership is the CDI deployment's, not a static per-JVM cache. */
public final class PersistenceFactoryCreator implements SyntheticBeanCreator<EntityManagerFactory> {
    @Override public EntityManagerFactory create(Instance<Object> lookup, Parameters params) {
        String unit = params.get("unit", String.class);
        JtaIntegration integration = new JtaIntegration(lookup.select(TransactionManager.class).get());
        var bootstrap = lookup.select(PersistenceUnitBootstrap.class);
        if (bootstrap.isUnsatisfied() && unit.isEmpty()) {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            unit = PersistenceDescriptors.defaultUnitName(loader == null ? PersistenceFactoryCreator.class.getClassLoader() : loader);
        }
        EntityManagerFactory factory = bootstrap.isUnsatisfied() ? Persistence.createEntityManagerFactory(unit, Map.of(TransactionIntegration.PROPERTY, integration))
            : bootstrap.get().create(unit, integration);
        return new ContainerEntityManagerFactory(factory);
    }
}
