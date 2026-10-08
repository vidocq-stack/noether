package app;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** An entity of a package the module does not open. */
@Entity
public class Thing {
    @Id
    private long id;
}
