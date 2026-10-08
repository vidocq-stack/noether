package shop;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.util.List;
import shop.base.Audited;

/** Field access: private fields through handles, package-private ones directly; a protected constructor. */
@Entity
public class Item extends Audited {
    @Id
    private long id;
    private String name;
    BigDecimal price;
    int stock;
    @ElementCollection
    private List<String> tags;
    @Transient
    private String cache;

    protected Item() {
    }

    public String name() {
        return name;
    }
}
