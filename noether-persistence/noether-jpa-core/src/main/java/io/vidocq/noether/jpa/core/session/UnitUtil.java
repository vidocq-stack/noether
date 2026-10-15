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
package io.vidocq.noether.jpa.core.session;

import io.vidocq.noether.jpa.core.mapping.LazyCollection;
import io.vidocq.noether.jpa.core.mapping.MappedEntity;
import io.vidocq.noether.jpa.core.mapping.MappedUnit;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.metamodel.Attribute;

/**
 * The {@link PersistenceUnitUtil} of a factory (§7.11): exact lazy collection/reference load state without SQL,
 * explicit loading and the canonical mapped class of generated references. Immutable, shared by the factory.
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

    /** The index of the persistent attribute {@code attributeName} of {@code type}, else -1. */
    static int index(MappedEntity type, String attributeName) {
        return type.model().attribute(attributeName).map(type.model().attributes()::indexOf).orElse(-1);
    }

    /** The value of the persistent attribute {@code attributeName} of {@code entity}; checks that it is one. */
    private Object attribute(Object entity, String attributeName) {
        MappedEntity type = entity(entity);
        int index = index(type, attributeName);
        if (index < 0) {
            throw new IllegalArgumentException(type.model().entityName() + " has no persistent attribute " + attributeName);
        }
        return type.access().get(entity, index);
    }

    /** §7.11: false only for a LAZY collection not loaded yet; reads no row. */
    @Override
    public boolean isLoaded(Object entity, String attributeName) {
        MappedEntity type = entity(entity);
        int index = index(type, attributeName);
        if (index < 0) throw new IllegalArgumentException("Unknown persistent attribute " + attributeName);
        if (io.vidocq.noether.jpa.core.spi.LazyReference.unloaded(entity)) {
            return java.util.Arrays.stream(type.idAttributes()).anyMatch(i -> i == index);
        }
        Object value = attribute(entity, attributeName);
        return !LazyCollection.unloaded(value) && !io.vidocq.noether.jpa.core.spi.LazyReference.unloaded(value);
    }

    @Override
    public <E> boolean isLoaded(E entity, Attribute<? super E, ?> attribute) {
        return isLoaded(entity, attribute.getName());
    }

    @Override
    public boolean isLoaded(Object entity) {
        entity(entity);
        return !io.vidocq.noether.jpa.core.spi.LazyReference.unloaded(entity);
    }

    @Override
    public void load(Object entity, String attributeName) {
        io.vidocq.noether.jpa.core.spi.LazyReference.initialize(entity);
        io.vidocq.noether.jpa.core.spi.LazyReference.initialize(attribute(entity, attributeName));
        // §7.11: a LAZY collection loads through its persistence context, a PersistenceException once it is detached
        if (attribute(entity, attributeName) instanceof LazyCollection lazy) {
            lazy.initialize();
        }
    }

    @Override
    public <E> void load(E entity, Attribute<? super E, ?> attribute) {
        load(entity, attribute.getName());
    }

    @Override
    public void load(Object entity) {
        entity(entity);
        io.vidocq.noether.jpa.core.spi.LazyReference.initialize(entity);
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
        return (Class<? extends T>) entity(entity).model().javaType();
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
