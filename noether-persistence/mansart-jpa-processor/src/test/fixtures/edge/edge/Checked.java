package edge;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** A no-arg constructor that declares a checked exception. */
@Entity
public class Checked {
    @Id
    long id;

    public Checked() throws Exception {
    }
}
