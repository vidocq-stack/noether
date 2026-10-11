package edge;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** Property access through a private setter that returns this. */
@Entity
public class Fluent {
    private long id;

    @Id
    public long getId() {
        return id;
    }

    private Fluent setId(long id) {
        this.id = id;
        return this;
    }
}
