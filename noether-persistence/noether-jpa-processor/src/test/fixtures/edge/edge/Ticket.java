package edge;

import jakarta.persistence.Entity;

/** Its identifier is a Long, declared as the type variable of Keyed. */
@Entity
public class Ticket extends Keyed<Long> {
    String title;
}
