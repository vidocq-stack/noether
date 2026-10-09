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

import jakarta.persistence.FetchType;

/**
 * An element collection (§2.7, §11.1.16) of basic or embeddable values, stored in its collection table.
 *
 * @param genericSignature the generic signature of the attribute
 * @param element the element as an attribute of the collection table: a {@link BasicAttribute} (its column, its
 *        conversion, read from the annotations of the collection) or an {@link EmbeddedAttribute} (its columns with the
 *        {@code @AttributeOverride}s of the collection); {@code null} for a map, whose keys come later in P5
 * @param table its {@code @CollectionTable}, or the defaults
 * @param orderBy the {@code @OrderBy} ({@code ""}: by value), or {@code null}
 * @param orderColumn the {@code @OrderColumn} of a list, or {@code null}
 */
public record ElementCollectionAttribute(String name, Class<?> javaType, AccessKind access, Class<?> declaringClass,
        String genericSignature, AttributeModel element, CollectionTableModel table, String orderBy, OrderColumnModel orderColumn,
        FetchType fetch) implements AttributeModel {
}
