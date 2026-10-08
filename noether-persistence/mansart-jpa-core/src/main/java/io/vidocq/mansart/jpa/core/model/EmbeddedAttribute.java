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

import java.util.Map;
import java.util.Optional;

/**
 * An embedded attribute (§2.6, §11.1.15), or an embedded identifier (§11.1.17).
 *
 * @param columns the effective column of every basic attribute reached through the embeddable, by dotted path
 *        ({@code street}, {@code geo.lat}), attribute overrides applied
 */
public record EmbeddedAttribute(String name, Class<?> javaType, AccessKind access, Class<?> declaringClass, EmbeddableModel embeddable,
        Map<String, ColumnModel> columns) implements AttributeModel {

    public EmbeddedAttribute {
        columns = Map.copyOf(columns);
    }

    /** The column of the basic attribute at {@code path}, dotted for nested embeddables. */
    public Optional<ColumnModel> column(String path) {
        return Optional.ofNullable(columns.get(path));
    }
}
