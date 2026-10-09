package io.vidocq.mansart.jpa.cdi;

import io.vidocq.mansart.jpa.core.spi.TransactionIntegration;
import jakarta.persistence.EntityManagerFactory;

/** Optional container configuration seam: a runtime supplies its PersistenceUnitInfo and managed data sources here. */
public interface PersistenceUnitBootstrap {
    EntityManagerFactory create(String unitName, TransactionIntegration transactions);
}
