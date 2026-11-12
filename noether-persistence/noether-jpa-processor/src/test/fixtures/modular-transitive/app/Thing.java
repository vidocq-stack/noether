package app;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** An entity of a module that reads the provider through a library. */
@Entity
public class Thing {
    @Id
    private long id;
}
