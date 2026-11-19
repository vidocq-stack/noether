package io.vidocq.noether.jpa.cdi.moduleit;

import jakarta.enterprise.context.Dependent;
import jakarta.persistence.*;

/**
 * The unit {@code xml-recorded} names {@link RecordingProvider}: noether-jpa-cdi hands it to that provider's
 * {@code createContainerEntityManagerFactory}, with the data source bean {@code @Named("ledger")}.
 */
@Dependent
public class RecordedUnitRepository {
    @PersistenceUnit(unitName = "xml-recorded") public EntityManagerFactory factory;
}
