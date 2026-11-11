package io.vidocq.noether.jpa.cdi.moduleit;

import jakarta.enterprise.context.Dependent;
import jakarta.persistence.*;

/** A resource-local unit reached through {@code @PersistenceUnit}: it needs no transaction bean and no data source bean. */
@Dependent
public class ResourceLocalRepository {
    @PersistenceUnit(unitName = "xml-local") public EntityManagerFactory factory;
}
