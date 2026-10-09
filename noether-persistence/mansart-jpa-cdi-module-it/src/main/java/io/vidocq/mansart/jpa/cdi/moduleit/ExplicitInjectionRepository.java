package io.vidocq.mansart.jpa.cdi.moduleit;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.persistence.*;

/** Diagnostic control: existing CDI injection points distinguish synthesis from injection-point discovery. */
@Dependent
public class ExplicitInjectionRepository {
    @Inject @PersistenceContext(unitName = "explicit") public EntityManager manager;
    @Inject @PersistenceUnit(unitName = "explicit") public EntityManagerFactory factory;
}
