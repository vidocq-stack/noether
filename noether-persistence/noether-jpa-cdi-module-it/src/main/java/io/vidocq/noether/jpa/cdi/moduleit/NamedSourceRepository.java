package io.vidocq.noether.jpa.cdi.moduleit;

import jakarta.enterprise.context.Dependent;
import jakarta.persistence.*;

/**
 * A JTA unit of {@code META-INF/persistence.xml} whose {@code jta-data-source} is {@code ledger}: noether-jpa-cdi takes
 * the data source bean {@code @Named("ledger")}.
 */
@Dependent
public class NamedSourceRepository {
    @PersistenceContext(unitName = "xml-ledger") public EntityManager manager;
}
