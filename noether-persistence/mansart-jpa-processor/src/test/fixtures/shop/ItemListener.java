package shop;

import jakarta.persistence.PrePersist;

/** A listener of Item; its method is package-private, reached directly from the package. */
public class ItemListener {

    public static final java.util.List<String> SEEN = new java.util.ArrayList<>();

    public ItemListener() {
    }

    @PrePersist
    void prePersist(Item item) {
        SEEN.add("ItemListener.prePersist " + item.name());
    }
}
