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
package io.vidocq.mansart.jpa.core.session;

import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import io.vidocq.mansart.jpa.core.mapping.MappedUnit;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.metamodel.Attribute;

/**
 * The {@link PersistenceUnitUtil} of a factory (§7.11). Mansart loads the state of an entity, its relationships
 * included, when it loads the entity ({@code FetchType.LAZY} is a hint, §11.1.6): an entity of the unit is always
 * loaded, and loading it again does nothing. No proxies: the class of an instance is its entity class. Immutable,
 * shared by the threads of the factory.
 */
final class UnitUtil implements PersistenceUnitUtil {

    private final MappedUnit unit;

    UnitUtil(MappedUnit unit) {
        this.unit = unit;
    }

    /** The entity of {@code entity}, an instance of an entity class of the unit; else an {@link IllegalArgumentException}. */
    private MappedEntity entity(Object entity) {
        if (entity == null) {
            throw new IllegalArgumentException("The entity cannot be null");
        }
        return unit.entity(entity.getClass()).orElseThrow(() -> new IllegalArgumentException(
            entity.getClass().getName() + " is not an entity class of the persistence unit"));
    }

    /** Checks that {@code attributeName} is a persistent attribute of {@code entity}. */
    private void attribute(Object entity, String attributeName) {
        MappedEntity type = entity(entity);
        if (type.model().attribute(attributeName).isEmpty()) {
            throw new IllegalArgumentException(type.model().entityName() + " has no persistent attribute " + attributeName);
        }
    }

    @Override
    public boolean isLoaded(Object entity, String attributeName) {
        attribute(entity, attributeName);
        return true;
    }

    @Override
    public <E> boolean isLoaded(E entity, Attribute<? super E, ?> attribute) {
        return isLoaded(entity, attribute.getName());
    }

    @Override
    public boolean isLoaded(Object entity) {
        entity(entity);
        return true;
    }

    @Override
    public void load(Object entity, String attributeName) {
        attribute(entity, attributeName); // loaded with its entity already
    }

    @Override
    public <E> void load(E entity, Attribute<? super E, ?> attribute) {
        load(entity, attribute.getName());
    }

    @Override
    public void load(Object entity) {
        entity(entity);
    }

    @Override
    public boolean isInstance(Object entity, Class<?> entityClass) {
        entity(entity);
        if (entityClass == null || unit.entity(entityClass).isEmpty()) {
            throw new IllegalArgumentException(entityClass + " is not an entity class of the persistence unit");
        }
        return entityClass.isInstance(entity);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Class<? extends T> getClass(T entity) {
        entity(entity);
        return (Class<? extends T>) entity.getClass();
    }

    /** §7.11: the identifier the application sees — its id class instance for an {@code @IdClass} — or null if it has none yet. */
    @Override
    public Object getIdentifier(Object entity) {
        MappedEntity type = entity(entity);
        return type.id(entity) == null ? null : type.idObject(entity);
    }

    /** The value of the version attribute, or null for an entity without one. */
    @Override
    public Object getVersion(Object entity) {
        MappedEntity type = entity(entity);
        return type.model().version().map(v -> type.access().get(entity, type.model().attributes().indexOf(v))).orElse(null);
    }
}
