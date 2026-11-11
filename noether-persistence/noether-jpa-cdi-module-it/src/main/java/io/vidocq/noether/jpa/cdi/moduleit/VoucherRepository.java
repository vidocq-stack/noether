package io.vidocq.noether.jpa.cdi.moduleit;

import jakarta.enterprise.context.Dependent;
import jakarta.persistence.*;

/**
 * A JTA unit of {@code META-INF/persistence.xml} with a TABLE generator and the {@code non-jta-data-source}
 * {@code allocations}: noether-jpa-cdi takes the data source bean {@code @Named("allocations")}, through which the
 * generator allocates.
 */
@Dependent
public class VoucherRepository {
    @PersistenceContext(unitName = "xml-vouchers") public EntityManager manager;
}
