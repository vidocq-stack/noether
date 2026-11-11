package io.vidocq.noether.jpa.cdi;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.*;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.persistence.*;
import java.util.*;

/** A container-managed persistence context (§7.6) bound to the transactions of the container's synchronization registry. */
public final class PersistenceContextCreator implements SyntheticBeanCreator<EntityManager> {
    public static final class Binding extends AnnotationLiteral<PersistenceBinding> implements PersistenceBinding {
        private final String value;
        public Binding(String value) { this.value = value; }
        @Override public String value() { return value; }
    }
    @Override public EntityManager create(Instance<Object> lookup, Parameters params) {
        String unit = params.get("unit", String.class);
        EntityManagerFactory factory = lookup.select(EntityManagerFactory.class, new Binding("unit:" + unit)).get();
        String[] names = params.get("propertyNames", String[].class, new String[0]);
        String[] values = params.get("propertyValues", String[].class, new String[0]);
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) properties.put(names[i], values[i]);
        return new ContainerEntityManager(factory, ContainerTransactions.registry(lookup, factory.getName()),
            PersistenceContextType.valueOf(params.get("contextType", String.class)),
            SynchronizationType.valueOf(params.get("synchronization", String.class)), properties);
    }
}
