package keys;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.ManyToOne;

/** A derived identity (§2.4.1.1): the relationship to the parent is part of the identifier. */
@Entity
@IdClass(StandKey.class)
public class Stand {
    @Id @ManyToOne Hall hall;
    @Id int rank;
}
