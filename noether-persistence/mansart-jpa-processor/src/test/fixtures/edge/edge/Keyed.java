package edge;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/** A generic mapped superclass: on its entities the identifier has the type argument, not the erasure. */
@MappedSuperclass
public abstract class Keyed<ID> {
    @Id
    ID id;
}
