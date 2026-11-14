package keys;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;

/** Its id class has no field of the identifier attributes' names. */
@Entity
@IdClass(GateKey.class)
public class Gate {
    @Id String code;
    @Id int number;
}
