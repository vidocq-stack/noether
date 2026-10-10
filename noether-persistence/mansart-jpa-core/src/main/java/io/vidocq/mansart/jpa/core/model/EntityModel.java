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
 * @param superEntity the closest entity superclass, if any (its relational strategy is mapped at unit bootstrap)
 * @param secondaryTables the secondary tables of the entity (§11.1.46), in declaration order
 * @param callbacks its lifecycle callbacks, in the order §3.6.4 invokes those of a same event
 * @param cacheable the explicit {@code @Cacheable} setting, or {@code null} when unspecified
 */
public record EntityModel(Class<?> javaType, String entityName, TableModel table, AccessKind access, IdModel id,
        List<AttributeModel> attributes, Optional<BasicAttribute> version, Optional<Class<?>> superEntity,
        List<SecondaryTableModel> secondaryTables, List<CallbackModel> callbacks, Boolean cacheable) {

    public EntityModel {
        attributes = List.copyOf(attributes);
        secondaryTables = List.copyOf(secondaryTables);
        callbacks = List.copyOf(callbacks);
    }

    public Optional<AttributeModel> attribute(String name) {
        return attributes.stream().filter(a -> a.name().equals(name)).findFirst();
    }
}
