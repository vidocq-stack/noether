package io.vidocq.noether.jpa.cdi;

import io.vidocq.noether.jpa.core.spi.TransactionIntegration;
import jakarta.persistence.EntityManagerFactory;

/**
 * Optional container configuration seam: a runtime supplies its PersistenceUnitInfo and managed data sources here. A
 * JTA data source it supplies must enlist its connections in the container's transactions; {@code transactions} is the
 * bridge over the container's {@code TransactionSynchronizationRegistry} bean, which must exist when this bean does.
 */
public interface PersistenceUnitBootstrap {
    EntityManagerFactory create(String unitName, TransactionIntegration transactions);
}
