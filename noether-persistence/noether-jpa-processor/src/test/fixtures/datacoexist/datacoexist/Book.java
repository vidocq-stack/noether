/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package datacoexist;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.List;

/**
 * An entity compiled by both noether-jpa-processor and mansart-data-processor, with a collection-valued field: Mansart
 * Data leaves it out of {@code _Book} since Vidocq/mansart#96 (MANSART-049, Noether's BUG-20261111-06).
 */
@Entity
public class Book {
    @Id
    public long id;
    public String title;
    @ElementCollection
    public List<String> tags;
}
