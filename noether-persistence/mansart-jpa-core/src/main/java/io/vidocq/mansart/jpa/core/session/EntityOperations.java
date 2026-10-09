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

import io.vidocq.mansart.jpa.core.context.EntityKey;
import io.vidocq.mansart.jpa.core.context.ManagedEntity;
import io.vidocq.mansart.jpa.core.context.PersistenceContext;
import io.vidocq.mansart.jpa.core.generation.IdGenerators;
import io.vidocq.mansart.jpa.core.mapping.CollectionMapping;
import io.vidocq.mansart.jpa.core.mapping.IndexMapping;
import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import io.vidocq.mansart.jpa.core.mapping.MappedUnit;
import io.vidocq.mansart.jpa.core.model.AssociationAttribute;
import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.ElementCollectionAttribute;
import io.vidocq.mansart.jpa.core.model.EmbeddableModel;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.spi.ManagedAccess;
import jakarta.persistence.CascadeType;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import java.sql.Connection;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The entity operations of §3.2 on the persistence context of one entity manager — persist, remove, merge, refresh,
 * detach — with their cascades through the relationships of the object graph (§3.2.2 to §3.2.7), and the orphan
 * removal (§2.9).
 */
final class EntityOperations {

    /** How the entity manager runs work on a connection: the transaction's, or one of its own. */
    interface Connections {
        <T> T on(Function<Connection, T> work);
    }

    private final EntityManagerFactoryImpl factory;
    private final PersistenceContext context;
    private final Connections connections;

    EntityOperations(EntityManagerFactoryImpl factory, PersistenceContext context, Connections connections) {
        this.factory = factory;
        this.context = context;
        this.connections = connections;
    }

    MappedEntity type(Object instance) {
        if (instance == null) {
            throw new IllegalArgumentException("The entity cannot be null");
        }
        return factory.mapping().entity(instance.getClass()).orElseThrow(() -> new IllegalArgumentException(
            instance.getClass().getName() + " is not an entity of persistence unit " + factory.getName()));
    }

    // ---- persist (§3.2.2) ---------------------------------------------------------------------------------

    void persist(Object entity) {
        persist(entity, visited());
    }

    private void persist(Object entity, Set<Object> visited) {
        if (!visited.add(entity)) {
            return;
        }
        MappedEntity type = type(entity);
        Optional<ManagedEntity> known = context.entry(entity);
        if (known.isEmpty()) {
            type.callback("PrePersist", entity); // §3.6.3: before the persist operation, so before the identifier
            generateId(type, entity);
            type.deriveId(entity); // §2.4.1: what an @MapsId maps is the parent's key
            context.persist(entity, type); // a detached instance fails at flush: its identity exists (§3.2.2)
        } else if (known.get().status() == ManagedEntity.Status.REMOVED) {
            type.callback("PrePersist", entity);
            context.persist(entity, type);
        }
        cascade(entity, type, CascadeType.PERSIST, target -> persist(target, visited));
    }

    /** §11.1.20: a generated identifier not assigned yet is assigned now, unless the insert generates it (IDENTITY). */
    private void generateId(MappedEntity type, Object entity) {
        if (IdGenerators.generatesBeforeInsert(type) && type.id(entity) == null) {
            Object id = connections.on(connection -> factory.idGenerators(connection).next(type, connection));
            type.access().set(entity, type.idAttributes()[0], id);
        }
    }

    // ---- remove (§3.2.3) ----------------------------------------------------------------------------------

    void remove(Object entity) {
        remove(entity, visited());
    }

    private void remove(Object entity, Set<Object> visited) {
        if (!visited.add(entity)) {
            return;
        }
        MappedEntity type = type(entity);
        Optional<ManagedEntity> known = context.entry(entity);
        if (known.isPresent()) {
            if (known.get().status() == ManagedEntity.Status.MANAGED) {
                type.callback("PreRemove", entity);
                context.remove(entity);
                cascade(entity, type, CascadeType.REMOVE, target -> remove(target, visited));
            }
            return; // removing a removed instance changes nothing
        }
        Object id = type.id(entity);
        if (id != null && connections.on(connection -> factory.loader(connection).exists(type, id, connection))) {
            throw new IllegalArgumentException("The instance of " + type.model().entityName() + " " + id
                + " is detached: merge it before removing it (§3.2.3)");
        }
        // a new instance is ignored, but the remove still cascades from it
        cascade(entity, type, CascadeType.REMOVE, target -> remove(target, visited));
    }

    // ---- merge (§3.2.7.1) ---------------------------------------------------------------------------------

    <T> T merge(T entity) {
        @SuppressWarnings("unchecked")
        T merged = (T) merge(entity, new IdentityHashMap<>());
        return merged;
    }

    private Object merge(Object entity, Map<Object, Object> merged) {
        Object done = merged.get(entity);
        if (done != null) {
            return done;
        }
        MappedEntity type = type(entity);
        Optional<ManagedEntity> known = context.entry(entity);
        if (known.isPresent()) {
            if (known.get().status() == ManagedEntity.Status.REMOVED) {
                throw new IllegalArgumentException("The instance of " + type.model().entityName() + " is removed (§3.2.7.1)");
            }
            merged.put(entity, entity);
            cascadeMerge(entity, entity, type, merged);
            return entity;
        }
        Object id = type.id(entity);
        Object managed = null;
        if (id != null) {
            Optional<ManagedEntity> sameIdentity = context.find(new EntityKey(type.root(), id));
            if (sameIdentity.isPresent()) {
                if (sameIdentity.get().status() == ManagedEntity.Status.REMOVED) {
                    throw new IllegalArgumentException("The instance of " + type.model().entityName() + " " + id + " is removed");
                }
                managed = sameIdentity.get().instance();
            } else {
                managed = connections.on(connection -> factory.loader(connection).load(type, id, connection, context));
            }
        }
        if (managed == null) {
            // a new instance: a new managed copy (§3.2.7.1)
            managed = type.access().instantiate();
            copy(type, entity, managed);
            type.callback("PrePersist", managed); // §3.6.3: the managed copy is the instance persisted
            generateId(type, managed);
            type.deriveId(managed);
            context.persist(managed, type);
        } else {
            checkVersion(type, entity, managed);
            copy(type, entity, managed);
        }
        merged.put(entity, managed);
        cascadeMerge(entity, managed, type, merged);
        return managed;
    }

    /** §3.4.2: a detached instance older than its row cannot be merged. */
    private static void checkVersion(MappedEntity type, Object detached, Object managed) {
        type.model().version().ifPresent(version -> {
            int index = type.model().attributes().indexOf(version);
            Object theirs = type.access().get(detached, index);
            Object ours = type.access().get(managed, index);
            if (!Objects.equals(theirs, ours)) {
                throw new OptimisticLockException("The instance of " + type.model().entityName() + " was read at version " + theirs
                    + ", its row is at version " + ours, null, detached);
            }
        });
    }

    /** The relationships of {@code managed} cascading MERGE point to the merged targets of {@code entity}'s. */
    private void cascadeMerge(Object entity, Object managed, MappedEntity type, Map<Object, Object> merged) {
        List<AttributeModel> attributes = type.model().attributes();
        for (int i = 0; i < attributes.size(); i++) {
            if (attributes.get(i) instanceof AssociationAttribute association && association.cascades(CascadeType.MERGE)) {
                Object value = type.access().get(entity, i);
                if (value instanceof Collection<?> collection) {
                    Collection<Object> elements = mapped(association, collection, target -> merge(target, merged));
                    if (managed != entity) {
                        type.access().set(managed, i, elements);
                    } else if (!sameElements(collection, elements)) {
                        replace(collection, elements); // a managed instance keeps its collection, with the managed elements
                    }
                } else if (value instanceof Map<?, ?> map) {
                    Map<Object, Object> entries = mappedMap(association, map, this::managedKey, target -> merge(target, merged));
                    if (managed != entity) {
                        type.access().set(managed, i, entries);
                    } else {
                        replace(map, entries); // a managed instance keeps its map, with the managed keys and values
                    }
                } else if (value != null) {
                    type.access().set(managed, i, merge(value, merged));
                }
            }
        }
    }

    /**
     * Copies the state of {@code from} onto {@code to}: values as they are, embeddables and element collections as new
     * copies, the target of a
     * single-valued relationship that does not cascade merge replaced by the managed instance of its identity
     * (§3.2.7.1); the relationships cascading merge are set by {@link #cascadeMerge}.
     */
    private void copy(MappedEntity type, Object from, Object to) {
        Object[] state = new Object[type.model().attributes().size()];
        type.access().read(from, state);
        List<AttributeModel> attributes = type.model().attributes();
        for (int i = 0; i < state.length; i++) {
            if (state[i] == null) {
                continue;
            }
            if (attributes.get(i) instanceof EmbeddedAttribute embedded) {
                state[i] = copy(embedded.embeddable(), state[i]);
            } else if (attributes.get(i) instanceof ElementCollectionAttribute elements) {
                // values, not instances: a collection of its own, embeddables copied
                Function<Object, Object> value = v -> v != null && elements.element() instanceof EmbeddedAttribute embedded
                    ? copy(embedded.embeddable(), v) : v;
                if (state[i] instanceof Collection<?> values) {
                    Collection<Object> copy = CollectionMapping.newCollection(elements.javaType());
                    values.forEach(v -> copy.add(value.apply(v)));
                    state[i] = copy;
                } else if (state[i] instanceof Map<?, ?> map) {
                    Map<Object, Object> copy = IndexMapping.newMap(elements.javaType());
                    map.forEach((k, v) -> copy.put(k, value.apply(v)));
                    state[i] = copy;
                }
            } else if (attributes.get(i) instanceof AssociationAttribute association) {
                if (association.singleValued()) {
                    if (!association.cascades(CascadeType.MERGE)) {
                        state[i] = managedReference(state[i]);
                    }
                } else if (state[i] instanceof Collection<?> collection) {
                    // a collection of its own: the managed instances of its elements, or the merged ones (cascadeMerge)
                    state[i] = mapped(association, collection,
                        association.cascades(CascadeType.MERGE) ? element -> element : this::managedReference);
                } else if (state[i] instanceof Map<?, ?> map) {
                    state[i] = mappedMap(association, map, this::managedKey,
                        association.cascades(CascadeType.MERGE) ? element -> element : this::managedReference);
                }
            }
        }
        type.access().write(to, state);
    }

    /** A new collection of the declared type of {@code association}, of the elements of {@code collection} mapped. */
    private static Collection<Object> mapped(AssociationAttribute association, Collection<?> collection,
            Function<Object, Object> mapping) {
        Collection<Object> mapped = CollectionMapping.newCollection(association.javaType());
        for (Object element : List.copyOf(collection.stream().filter(Objects::nonNull).toList())) {
            mapped.add(mapping.apply(element));
        }
        return mapped;
    }

    /**
     * A new map of the declared type of {@code association}, of the entries of {@code map}, keys and values mapped.
     */
    private static Map<Object, Object> mappedMap(AssociationAttribute association, Map<?, ?> map, Function<Object, Object> keys,
            Function<Object, Object> values) {
        Map<Object, Object> mapped = IndexMapping.newMap(association.javaType());
        for (Map.Entry<?, ?> entry : List.copyOf(map.entrySet())) {
            mapped.put(keys.apply(entry.getKey()), entry.getValue() == null ? null : values.apply(entry.getValue()));
        }
        return mapped;
    }

    /** A map key as the managed map holds it: the managed instance of an entity key, any other value as it is. */
    private Object managedKey(Object key) {
        return key != null && factory.mapping().entity(key.getClass()).isPresent() ? managedReference(key) : key;
    }

    private static boolean sameElements(Collection<?> collection, Collection<Object> elements) {
        List<?> before = List.copyOf(collection.stream().filter(Objects::nonNull).toList());
        List<Object> after = List.copyOf(elements);
        for (int i = 0; i < before.size(); i++) {
            if (before.get(i) != after.get(i)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static void replace(Map<?, ?> map, Map<Object, Object> entries) {
        map.clear();
        ((Map<Object, Object>) map).putAll(entries);
    }

    @SuppressWarnings("unchecked")
    private static void replace(Collection<?> collection, Collection<Object> elements) {
        collection.clear();
        ((Collection<Object>) collection).addAll(elements);
    }

    /**
     * The managed instance with the identity of {@code target}: itself if managed, else the context's or the one
     * loaded; {@code target} unchanged if it is new (the flush then tells, §3.2.4).
     */
    private Object managedReference(Object target) {
        if (context.entry(target).isPresent()) {
            return target;
        }
        MappedEntity type = type(target);
        Object id = type.id(target);
        if (id == null) {
            return target;
        }
        Optional<ManagedEntity> known = context.find(new EntityKey(type.root(), id));
        if (known.isPresent()) {
            return known.get().instance();
        }
        Object loaded = connections.on(connection -> factory.loader(connection).load(type, id, connection, context));
        return loaded != null ? loaded : target;
    }

    private Object copy(EmbeddableModel embeddable, Object value) {
        MappedUnit unit = factory.mapping();
        ManagedAccess access = unit.access(embeddable);
        Object[] state = new Object[embeddable.attributes().size()];
        access.read(value, state);
        for (int i = 0; i < state.length; i++) {
            if (embeddable.attributes().get(i) instanceof EmbeddedAttribute nested && state[i] != null) {
                state[i] = copy(nested.embeddable(), state[i]);
            }
        }
        if (embeddable.isRecord()) {
            return access.construct(state);
        }
        Object copy = access.instantiate();
        access.write(copy, state);
        return copy;
    }

    // ---- refresh (§3.2.5) and detach (§3.2.6) -------------------------------------------------------------

    void refresh(Object entity) {
        refresh(entity, visited());
    }

    private void refresh(Object entity, Set<Object> visited) {
        if (!visited.add(entity)) {
            return;
        }
        MappedEntity type = type(entity);
        ManagedEntity entry = context.entry(entity).filter(e -> e.status() == ManagedEntity.Status.MANAGED)
            .orElseThrow(() -> new IllegalArgumentException("Only a managed instance can be refreshed (§3.2.5)"));
        Object id = entry.key().id();
        Object[] snapshot = connections.on(connection -> factory.loader(connection).refresh(type, id, entity, connection, context));
        if (snapshot == null) {
            throw new EntityNotFoundException("The row of " + type.model().entityName() + " " + id + " no longer exists");
        }
        context.updated(entry, snapshot);
        cascade(entity, type, CascadeType.REFRESH, target -> {
            if (context.contains(target)) {
                refresh(target, visited);
            }
        });
    }

    void detach(Object entity) {
        detach(entity, visited());
    }

    private void detach(Object entity, Set<Object> visited) {
        if (!visited.add(entity)) {
            return;
        }
        MappedEntity type = type(entity);
        context.detach(entity);
        cascade(entity, type, CascadeType.DETACH, target -> detach(target, visited));
    }

    // ---- flush (§3.2.4) -----------------------------------------------------------------------------------

    /**
     * What a flush does before writing (§3.2.4): the persist cascades again from every managed instance, reaching what
     * its relationships gained since; an instance it reaches through a relationship, owning or inverse, that does not
     * cascade persist must be managed or detached, a new one is an {@link IllegalStateException}.
     */
    void beforeFlush() {
        removeOrphans();
        Set<Object> visited = visited();
        for (ManagedEntity entry : context.entries()) {
            if (entry.status() != ManagedEntity.Status.MANAGED) {
                continue;
            }
            Object entity = entry.instance();
            MappedEntity type = entry.type();
            cascade(entity, type, CascadeType.PERSIST, target -> persist(target, visited));
            List<AttributeModel> attributes = type.model().attributes();
            for (int i = 0; i < attributes.size(); i++) {
                // any relationship from X, the inverse side as well as the owning one
                if (attributes.get(i) instanceof AssociationAttribute association && !association.cascades(CascadeType.PERSIST)) {
                    targets(type.access().get(entity, i), target -> requireNotNew(entity, association, target));
                }
            }
        }
    }

    /**
     * §2.9: what a relationship with {@code orphanRemoval} held at the last flush, and no longer holds — its target, or
     * elements of its collection — is removed. Done before the persist cascade, so that an orphan given to another
     * owner that cascades persist is managed again.
     */
    private void removeOrphans() {
        Set<Object> visited = visited();
        for (ManagedEntity entry : context.entries()) {
            if (entry.status() != ManagedEntity.Status.MANAGED || entry.snapshot() == null) {
                continue;
            }
            List<AttributeModel> attributes = entry.type().model().attributes();
            for (int i = 0; i < attributes.size(); i++) {
                if (!(attributes.get(i) instanceof AssociationAttribute association) || !association.orphanRemoval()) {
                    continue;
                }
                Object current = entry.type().access().get(entry.instance(), i);
                if (association.singleValued()) {
                    Object previous = entry.snapshot()[i];
                    if (previous != null && previous != current && context.contains(previous)) {
                        remove(previous, visited);
                    }
                } else {
                    Set<Object> kept = visited();
                    kept.addAll(CollectionMapping.elements(current));
                    for (Object previous : CollectionMapping.elements(entry.snapshot()[i])) {
                        if (previous != null && !kept.contains(previous) && context.contains(previous)) {
                            remove(previous, visited);
                        }
                    }
                }
            }
        }
    }

    private void requireNotNew(Object owner, AssociationAttribute association, Object target) {
        if (context.contains(target) || factory.mapping().entity(target.getClass()).isEmpty()) {
            return;
        }
        MappedEntity type = type(target);
        Object id = type.id(target);
        boolean detached = id != null && connections.on(connection -> factory.loader(connection).exists(type, id, connection));
        if (!detached) {
            throw new IllegalStateException("The relationship " + association.name() + " of " + owner.getClass().getName()
                + " references a new instance of " + type.model().entityName() + " and does not cascade persist (§3.2.4)");
        }
    }

    // ---- cascades -----------------------------------------------------------------------------------------

    /** Applies {@code operation} to the instances that the relationships of {@code entity} cascading it reach. */
    private void cascade(Object entity, MappedEntity type, CascadeType operation, Consumer<Object> apply) {
        List<AttributeModel> attributes = type.model().attributes();
        for (int i = 0; i < attributes.size(); i++) {
            if (attributes.get(i) instanceof AssociationAttribute association && association.cascades(operation)) {
                targets(type.access().get(entity, i), apply);
            }
        }
    }

    /** The instances a relationship value holds: itself, the elements of a collection, the values of a map. */
    private static void targets(Object value, Consumer<Object> apply) {
        switch (value) {
            case null -> {
            }
            case Collection<?> collection -> List.copyOf(collection.stream().filter(Objects::nonNull).toList()).forEach(apply);
            case Map<?, ?> map -> List.copyOf(map.values().stream().filter(Objects::nonNull).toList()).forEach(apply);
            default -> apply.accept(value);
        }
    }

    private static Set<Object> visited() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
