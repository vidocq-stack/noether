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

import java.util.List;

/**
 * How a map or an ordered list indexes its elements (§2.7, §11.1.30 to §11.1.42): the key of each element of a map, or
 * the position of each element of a list kept by an {@code @OrderColumn}.
 */
public sealed interface CollectionIndex {

    /** {@code @MapKey}: an attribute of the target entity, its identifier when {@code name} is empty; no column. */
    record ByAttribute(String name) implements CollectionIndex {
    }

    /**
     * A basic key in a column (§11.1.33 {@code @MapKeyColumn}, default {@code <attribute>_KEY}), with its conversion
     * ({@code @MapKeyEnumerated}, {@code @MapKeyTemporal}, {@code @Convert(attributeName = "key")}).
     */
    record ByColumn(BasicAttribute key) implements CollectionIndex {
    }

    /** An embeddable key (§2.7): its basic columns, including nested overrides named {@code key.<attribute>}. */
    record ByEmbedded(EmbeddedAttribute key) implements CollectionIndex {
    }

    /** An entity key (§11.1.35 {@code @MapKeyJoinColumn}): a foreign key to {@code entity}, as written. */
    record ByEntity(Class<?> entity, List<JoinColumnModel> joinColumns) implements CollectionIndex {

        public ByEntity {
            joinColumns = List.copyOf(joinColumns);
        }
    }

    /**
     * The position in a list, from 0 (§11.1.42 {@code @OrderColumn}, default {@code <attribute>_ORDER}): an integer
     * attribute of the row, its column as written.
     */
    record ByPosition(BasicAttribute position) implements CollectionIndex {
    }

    /** A map key P5 does not map yet, such as a raw map without a declared key class. */
    record Unsupported(String feature) implements CollectionIndex {
    }
}
