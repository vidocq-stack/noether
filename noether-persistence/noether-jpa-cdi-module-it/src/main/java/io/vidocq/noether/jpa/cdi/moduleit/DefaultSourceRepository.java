package io.vidocq.noether.jpa.cdi.moduleit;

import jakarta.enterprise.context.Dependent;
import jakarta.persistence.*;

/**
 * A JTA unit of {@code META-INF/persistence.xml} that names no {@code jta-data-source}, without a container bootstrap:
 * noether-jpa-cdi takes the {@code @Default} data source bean.
 */
@Dependent
public class DefaultSourceRepository {
    @PersistenceContext(unitName = "xml-default") public EntityManager manager;
}
