package keys;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;

/** Its id class is a record whose components come in another order than the identifier attributes. */
@Entity
@IdClass(BoothKey.class)
public class Booth {
    @Id String aisle;
    @Id int row;
}
