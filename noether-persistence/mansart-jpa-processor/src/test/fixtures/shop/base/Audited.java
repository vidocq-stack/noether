package shop.base;

import jakarta.persistence.MappedSuperclass;
import java.time.Instant;

/** A mapped superclass in another package: its members are reached through method handles. */
@MappedSuperclass
public abstract class Audited {
    private Instant createdAt;
    protected int revision;

    public Instant createdAt() {
        return createdAt;
    }
}
