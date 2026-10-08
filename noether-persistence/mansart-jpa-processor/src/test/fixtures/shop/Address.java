package shop;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Embeddable;

/** An embeddable read by field whatever its owner does, with a nested record embeddable. */
@Embeddable
@Access(AccessType.FIELD)
public class Address {
    private String street;
    private Geo geo;

    public String street() {
        return street;
    }
}
