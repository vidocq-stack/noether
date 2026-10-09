/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.model.xml.fixtures;

import jakarta.persistence.AssociationOverride;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;

@Entity
@AssociationOverride(name = "target", joinColumns = @JoinColumn(name = "ANNOTATED_FK"))
@AssociationOverride(name = "targets", joinTable = @JoinTable(name = "ANNOTATED_LINKS"))
public class LinkEntity extends LinkBase {
    @jakarta.persistence.MappedSuperclass
    public static class DefaultLinks {
        @jakarta.persistence.Id
        public long id;
        @jakarta.persistence.ManyToMany
        public java.util.List<Excluded> targets = new java.util.ArrayList<>();
    }

    @Entity
    @AssociationOverride(name = "targets", joinColumns = @JoinColumn(name = "IGNORED_FK"))
    public static class InvalidOverride extends DefaultLinks {
    }
}
