package io.vidocq.noether.jpa.cdi;

import io.vidocq.noether.jpa.core.spi.TransactionIntegration;
import jakarta.persistence.EntityManagerFactory;

/** Optional container configuration seam: a runtime supplies its PersistenceUnitInfo and managed data sources here. */
public interface PersistenceUnitBootstrap {
    EntityManagerFactory create(String unitName, TransactionIntegration transactions);
}
