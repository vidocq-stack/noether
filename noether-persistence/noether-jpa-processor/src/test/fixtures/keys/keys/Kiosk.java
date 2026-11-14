package keys;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.OneToOne;

/** A derived identity whose id class has no field for the relationship. */
@Entity
@IdClass(KioskKey.class)
public class Kiosk {
    @Id @OneToOne Hall hall;
    @Id int rank;
}
