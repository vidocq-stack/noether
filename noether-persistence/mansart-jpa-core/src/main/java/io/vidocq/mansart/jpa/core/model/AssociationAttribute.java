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

/**
 * A relationship (§2.10). Recorded by the model; its mapping (join columns, join tables, fetching, cascades) is
 * milestone P5.
 *
 * @param genericSignature the generic signature of the attribute, to find the target entity of a collection
 * @param mappedBy the {@code mappedBy} of the inverse side, or {@code null} on the owning side
 */
public record AssociationAttribute(String name, Class<?> javaType, AccessKind access, Class<?> declaringClass, Kind kind,
        String genericSignature, String mappedBy) implements AttributeModel {

    /** The four relationship annotations. */
    public enum Kind { ONE_TO_ONE, MANY_TO_ONE, ONE_TO_MANY, MANY_TO_MANY }
}
