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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Everything a persistence unit maps: entities, the embeddables they use, converters. Immutable. */
public final class PersistenceUnitModel {

    private final List<EntityModel> entities;
    private final List<ConverterModel> converters;
    private final List<NamedQueryModel> namedQueries;
    private final List<SqlResultSetMappingModel> sqlResultSetMappings;
    private final Map<Class<?>, EntityModel> byClass = new LinkedHashMap<>();
    private final Map<String, EntityModel> byName = new LinkedHashMap<>();

    public PersistenceUnitModel(List<EntityModel> entities, List<ConverterModel> converters) {
        this(entities, converters, List.of());
    }

    public PersistenceUnitModel(List<EntityModel> entities, List<ConverterModel> converters, List<NamedQueryModel> namedQueries) {
        this(entities, converters, namedQueries, List.of());
    }

    public PersistenceUnitModel(List<EntityModel> entities, List<ConverterModel> converters, List<NamedQueryModel> namedQueries,
            List<SqlResultSetMappingModel> sqlResultSetMappings) {
        this.entities = List.copyOf(entities);
        this.converters = List.copyOf(converters);
        this.namedQueries = List.copyOf(namedQueries);
        this.sqlResultSetMappings = List.copyOf(sqlResultSetMappings);
        for (EntityModel entity : this.entities) {
            byClass.put(entity.javaType(), entity);
            byName.put(entity.entityName(), entity);
        }
    }

    public List<EntityModel> entities() {
        return entities;
    }

    public List<ConverterModel> converters() {
        return converters;
    }

    /** The named queries the managed classes declare, in the order of the classes. */
    public List<NamedQueryModel> namedQueries() {
        return namedQueries;
    }

    public List<SqlResultSetMappingModel> sqlResultSetMappings() {
        return sqlResultSetMappings;
    }

    public Optional<SqlResultSetMappingModel> sqlResultSetMapping(String name) {
        return sqlResultSetMappings.stream().filter(mapping -> mapping.name().equals(name)).findFirst();
    }

    public Optional<EntityModel> entity(Class<?> type) {
        return Optional.ofNullable(byClass.get(type));
    }

    /** By entity name (§4.3.1), as queries name entities. */
    public Optional<EntityModel> entity(String entityName) {
        return Optional.ofNullable(byName.get(entityName));
    }
}
