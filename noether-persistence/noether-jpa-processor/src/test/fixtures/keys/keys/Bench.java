package keys;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;

/** Its id class is a record whose components follow the identifier attributes. */
@Entity
@IdClass(BenchKey.class)
public class Bench {
    @Id String aisle;
    @Id int row;
}
