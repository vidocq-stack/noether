/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package datacoexist;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import java.util.List;

/**
 * An entity compiled by both noether-jpa-processor and mansart-data-processor. Its plural attribute is mapped by
 * property access (§2.3.2), its field is {@code @Transient}: Mansart Data, which reads fields, cannot write
 * {@code _Book} for a collection-valued field yet (BUG-20261111-06), and skips a transient one.
 */
@Entity
@Access(AccessType.FIELD)
public class Book {
    @Id
    public long id;
    public String title;
    @Transient
    private List<String> tags;

    @Access(AccessType.PROPERTY)
    @ElementCollection
    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }
}
