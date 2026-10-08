package shop;

import jakarta.persistence.Embeddable;

/** A record embeddable: built through its canonical constructor. */
@Embeddable
public record Geo(double lat, double lon) {
}
