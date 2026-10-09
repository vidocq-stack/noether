package io.vidocq.mansart.jpa.cdi;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.*;
import jakarta.persistence.EntityManagerFactory;

public final class PersistenceFactoryDisposer implements SyntheticBeanDisposer<EntityManagerFactory> {
    @Override public void dispose(EntityManagerFactory factory, Instance<Object> lookup, Parameters params) {
        if (factory instanceof ContainerEntityManagerFactory containerFactory) containerFactory.destroy();
        else if (factory.isOpen()) factory.close();
    }
}
