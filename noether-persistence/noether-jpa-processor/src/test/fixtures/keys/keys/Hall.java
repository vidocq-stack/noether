package keys;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** The parent of the derived identities. */
@Entity
public class Hall {
    @Id long id;
}
