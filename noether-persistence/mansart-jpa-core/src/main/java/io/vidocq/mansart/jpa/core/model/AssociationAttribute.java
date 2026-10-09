/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A relationship (§2.10, §11.1.26, §11.1.30, §11.1.38, §11.1.40), as its annotations write it: the defaults that
 * depend on the other entities (join column names, the join table) are applied when the unit is mapped.
 *
 * @param targetEntity the entity it references: {@code targetEntity}, else the declared type of a single-valued
 *        relationship, the element type of a collection, the value type of a map
 * @param mappedBy the {@code mappedBy} of the inverse side, or {@code null} on the owning side
 * @param optional whether a single-valued relationship may be {@code null} ({@code optional}, §11.1.26 and §11.1.40)
 * @param joinColumns the {@code @JoinColumn}s written on it, empty for the defaults
 * @param joinTable its {@code @JoinTable}, or {@code null} if none is written
 * @param orderBy the {@code @OrderBy} of a collection ({@code ""}: by primary key), or {@code null}
 * @param index how a map or an ordered list indexes its elements, or {@code null}
 * @param mapsId the {@code @MapsId} of a derived identity ({@code ""}: the whole identifier), or {@code null}
 */
public record AssociationAttribute(String name, Class<?> javaType, AccessKind access, Class<?> declaringClass, Kind kind,
        Class<?> targetEntity, String mappedBy, Set<CascadeType> cascade, boolean orphanRemoval, FetchType fetch, boolean optional,
        List<JoinColumnModel> joinColumns, JoinTableModel joinTable, String orderBy, CollectionIndex index, String mapsId)
        implements AttributeModel {

    public AssociationAttribute {
        cascade = cascade.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(cascade));
        joinColumns = List.copyOf(joinColumns);
    }

    /**
     * Whether operation {@code type} cascades through this relationship (§3.2: {@code ALL} cascades every one; §2.9:
     * {@code orphanRemoval} cascades the remove).
     */
    public boolean cascades(CascadeType type) {
        return cascade.contains(type) || cascade.contains(CascadeType.ALL) || type == CascadeType.REMOVE && orphanRemoval;
    }

    /** Whether it holds one instance ({@code @ManyToOne}, {@code @OneToOne}) rather than a collection or a map. */
    public boolean singleValued() {
        return kind == Kind.MANY_TO_ONE || kind == Kind.ONE_TO_ONE;
    }

    /** Whether this side owns the relationship (§2.10: the side without {@code mappedBy}). */
    public boolean owning() {
        return mappedBy == null;
    }

    /** The four relationship annotations. */
    public enum Kind { ONE_TO_ONE, MANY_TO_ONE, ONE_TO_MANY, MANY_TO_MANY }
}
