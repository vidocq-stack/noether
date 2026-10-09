package io.vidocq.mansart.jpa.cdi.moduleit;

import jakarta.enterprise.context.Dependent;
import jakarta.persistence.*;

@Dependent
public class Repository {
    @PersistenceContext(unitName = "container") public EntityManager manager;
    @PersistenceUnit(unitName = "container") public EntityManagerFactory factory;
}
