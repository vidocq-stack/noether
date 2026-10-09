package io.vidocq.mansart.jpa.cdi.moduleit;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.persistence.*;

@Dependent
public class SetterRepository {
    public EntityManager manager;

    @Inject @PersistenceContext(unitName = "setter")
    public void manager(EntityManager value) { manager = value; }
}
