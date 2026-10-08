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
import java.util.Optional;

/**
 * An entity (§2.1) as mapped: name, table, access type, identifier and persistent attributes, those of its mapped
 * superclasses and entity superclasses first.
 *
 * @param superEntity the closest entity superclass, if any (its mapping strategy is milestone P6)
 */
public record EntityModel(Class<?> javaType, String entityName, TableModel table, AccessKind access, IdModel id,
        List<AttributeModel> attributes, Optional<BasicAttribute> version, Optional<Class<?>> superEntity) {

    public EntityModel {
        attributes = List.copyOf(attributes);
    }

    public Optional<AttributeModel> attribute(String name) {
        return attributes.stream().filter(a -> a.name().equals(name)).findFirst();
    }
}
