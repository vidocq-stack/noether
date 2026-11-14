package keys;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;

/** Its id class declares a field for each identifier attribute. */
@Entity
@IdClass(SeatKey.class)
public class Seat {
    @Id String aisle;
    @Id int place;
    String holder;
}
