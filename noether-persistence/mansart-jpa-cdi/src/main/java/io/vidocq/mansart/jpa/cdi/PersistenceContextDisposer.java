package io.vidocq.mansart.jpa.cdi;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.*;
import jakarta.persistence.EntityManager;

public final class PersistenceContextDisposer implements SyntheticBeanDisposer<EntityManager> {
    @Override public void dispose(EntityManager context, Instance<Object> lookup, Parameters params) {
        ((ContainerEntityManager) context).destroy();
    }
}
