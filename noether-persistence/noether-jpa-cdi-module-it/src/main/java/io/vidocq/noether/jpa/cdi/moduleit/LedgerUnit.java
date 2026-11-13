package io.vidocq.noether.jpa.cdi.moduleit;

import jakarta.enterprise.context.Dependent;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnit;

/** The factory of the {@code xml-ledger} unit, injected the Jakarta Persistence way. */
@Dependent
public class LedgerUnit {
    @PersistenceUnit(unitName = "xml-ledger") public EntityManagerFactory factory;
}
