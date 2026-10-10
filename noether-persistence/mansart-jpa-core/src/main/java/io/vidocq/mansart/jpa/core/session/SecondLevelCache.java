/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.session;

import io.vidocq.mansart.jpa.core.context.EntityKey;
import io.vidocq.mansart.jpa.core.context.ManagedEntity;
import io.vidocq.mansart.jpa.core.context.PersistenceContext;
import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import io.vidocq.mansart.jpa.core.mapping.MappedUnit;
import io.vidocq.mansart.jpa.core.model.AssociationAttribute;
import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.BasicAttribute;
import io.vidocq.mansart.jpa.core.model.ElementCollectionAttribute;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.model.EmbeddableModel;
import jakarta.persistence.Cache;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.SharedCacheMode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** A factory-scoped, thread-safe cache of immutable entity-state graphs (§3.10). */
final class SecondLevelCache implements Cache {

    private static final System.Logger LOG = System.getLogger(SecondLevelCache.class.getName());

    private record EntityReference(EntityKey key) {
    }

    private record CollectionState(boolean map, boolean set, List<Object> keys, List<Object> values) {
    }

    private record Node(Class<?> type, List<Object> attributes) {
    }

    private record Graph(EntityKey root, Map<EntityKey, Node> nodes) {
    }

    record Publication(Map<EntityKey, Graph> graphs, Set<EntityKey> changed, long expectedGeneration) {
    }

    private final MappedUnit mapping;
    private final SharedCacheMode mode;
    private final ConcurrentMap<EntityKey, Graph> entries = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    SecondLevelCache(MappedUnit mapping, SharedCacheMode mode) {
        this.mapping = mapping;
        this.mode = mode == SharedCacheMode.UNSPECIFIED ? SharedCacheMode.ENABLE_SELECTIVE : mode;
    }

    boolean enabled(MappedEntity type) {
        if (mode == SharedCacheMode.NONE) {
            return false;
        }
        if (mode == SharedCacheMode.ALL) {
            return true;
        }
        Boolean explicit = inheritedCacheable(type.model().javaType());
        return mode == SharedCacheMode.DISABLE_SELECTIVE ? !Boolean.FALSE.equals(explicit) : Boolean.TRUE.equals(explicit);
    }

    private Boolean inheritedCacheable(Class<?> type) {
        var model = mapping.entity(type).orElseThrow().model();
        if (model.cacheable() != null) {
            return model.cacheable();
        }
        return model.superEntity().map(this::inheritedCacheable).orElse(null);
    }

    Optional<Object> restore(MappedEntity requested, Object id, PersistenceContext context) {
        if (!enabled(requested)) {
            return Optional.empty();
        }
        EntityKey key = new EntityKey(requested.root(), id);
        Graph graph = entries.get(key);
        if (graph == null || !matches(requested, graph)) {
            return Optional.empty();
        }
        try {
            Map<EntityKey, Object> instances = new LinkedHashMap<>();
            Map<EntityKey, MappedEntity> types = new LinkedHashMap<>();
            Set<EntityKey> created = new LinkedHashSet<>();
            for (Map.Entry<EntityKey, Node> entry : graph.nodes().entrySet()) {
                MappedEntity type = mapping.entity(entry.getValue().type()).orElseThrow();
                Optional<ManagedEntity> known = context.find(entry.getKey())
                    .filter(managed -> managed.status() == ManagedEntity.Status.MANAGED);
                Object instance = known.map(ManagedEntity::instance).orElseGet(type.access()::instantiate);
                instances.put(entry.getKey(), instance);
                types.put(entry.getKey(), type);
                if (known.isEmpty()) {
                    created.add(entry.getKey());
                }
            }
            for (Map.Entry<EntityKey, Node> entry : graph.nodes().entrySet()) {
                if (!created.contains(entry.getKey())) {
                    continue;
                }
                MappedEntity type = types.get(entry.getKey());
                Object instance = instances.get(entry.getKey());
                List<AttributeModel> attributes = type.model().attributes();
                for (int i = 0; i < attributes.size(); i++) {
                    type.access().set(instance, i, decode(attributes.get(i), entry.getValue().attributes().get(i), instances));
                }
            }
            for (Map.Entry<EntityKey, Node> entry : graph.nodes().entrySet()) {
                if (!created.contains(entry.getKey())) {
                    continue;
                }
                MappedEntity type = types.get(entry.getKey());
                Object instance = instances.get(entry.getKey());
                Object[] state = new Object[type.model().attributes().size()];
                type.access().read(instance, state);
                context.loaded(instance, type, entry.getKey().id(), type.state().snapshot(state));
            }
            for (Map.Entry<EntityKey, Node> entry : graph.nodes().entrySet()) {
                if (created.contains(entry.getKey())) {
                    types.get(entry.getKey()).callback("PostLoad", instances.get(entry.getKey()));
                }
            }
            Object result = instances.get(graph.root());
            return result != null && requested.model().javaType().isInstance(result) ? Optional.of(result) : Optional.empty();
        } catch (UnsupportedState unsupported) {
            entries.remove(key, graph);
            LOG.log(System.Logger.Level.DEBUG, "Discarding unsupported cached state for " + key, unsupported);
            return Optional.empty();
        }
    }

    long generation() {
        return generation.get();
    }

    void store(MappedEntity type, Object instance, long expectedGeneration) {
        try {
            type = mapped(instance);
            if (!enabled(type)) {
                return;
            }
            store(graph(type, instance), expectedGeneration);
        } catch (UnsupportedState unsupported) {
            LOG.log(System.Logger.Level.DEBUG, "Skipping unsupported cache state for "
                + instance.getClass().getName(), unsupported);
        }
    }

    private Graph graph(MappedEntity type, Object instance) {
        Map<EntityKey, Node> nodes = new LinkedHashMap<>();
        capture(type, instance, nodes);
        return new Graph(key(type, instance), Map.copyOf(nodes));
    }

    private void store(Graph graph, long expectedGeneration) {
        if (generation.get() == expectedGeneration) {
            entries.put(graph.root(), graph);
            if (generation.get() != expectedGeneration) {
                entries.remove(graph.root(), graph);
            }
        }
    }

    /** Captures the flushed state before JDBC/JTA commit, without making it visible to other sessions. */
    Publication stage(PersistenceContext context, CacheStoreModeSetting storeMode, Set<EntityKey> bypassStore,
            Set<EntityKey> changed, Set<EntityKey> candidates, long expectedGeneration) {
        Map<EntityKey, Graph> graphs = new LinkedHashMap<>();
        List<ManagedEntity> managed = context.entries();
        for (ManagedEntity entry : managed) {
            EntityKey key = entry.key();
            if (key == null || !candidates.contains(key) && !changed.contains(key)) {
                continue;
            }
            if (entry.status() == ManagedEntity.Status.MANAGED
                    && storeMode != CacheStoreModeSetting.BYPASS && !bypassStore.contains(key) && enabled(entry.type())) {
                try {
                    graphs.put(key, graph(entry.type(), entry.instance()));
                } catch (UnsupportedState unsupported) {
                    LOG.log(System.Logger.Level.DEBUG, "Skipping unsupported staged cache state for " + key, unsupported);
                }
            }
        }
        return new Publication(Map.copyOf(graphs), Set.copyOf(changed), expectedGeneration);
    }

    void publish(Publication publication) {
        if (publication == null) {
            return;
        }
        if (!publication.changed().isEmpty()) {
            generation.incrementAndGet();
            entries.entrySet().removeIf(entry -> entry.getValue().nodes().keySet().stream()
                .anyMatch(publication.changed()::contains));
        }
        long publishGeneration = publication.expectedGeneration() + (publication.changed().isEmpty() ? 0 : 1);
        publication.graphs().values().forEach(graph -> store(graph, publishGeneration));
    }

    void invalidate(EntityKey key) {
        generation.incrementAndGet();
        entries.entrySet().removeIf(entry -> entry.getValue().nodes().containsKey(key));
    }

    void invalidateAll() {
        generation.incrementAndGet();
        entries.clear();
    }

    @Override
    public boolean contains(Class<?> cls, Object primaryKey) {
        MappedEntity type = entity(cls);
        if (type == null || primaryKey == null || !enabled(type)) {
            return false;
        }
        Graph graph = entries.get(new EntityKey(type.root(), type.key(primaryKey)));
        return graph != null && matches(type, graph);
    }

    private boolean matches(MappedEntity requested, Graph graph) {
        Node root = graph.nodes().get(graph.root());
        return root != null && requested.model().javaType().isAssignableFrom(root.type())
            && graph.nodes().values().stream().allMatch(node -> enabled(mapping.entity(node.type()).orElseThrow()));
    }

    @Override
    public void evict(Class<?> cls, Object primaryKey) {
        MappedEntity type = entity(cls);
        if (type != null && primaryKey != null) {
            invalidate(new EntityKey(type.root(), type.key(primaryKey)));
        }
    }

    @Override
    public void evict(Class<?> cls) {
        MappedEntity type = entity(cls);
        if (type == null) {
            return;
        }
        Class<?> root = type.root();
        generation.incrementAndGet();
        entries.entrySet().removeIf(entry -> entry.getValue().nodes().keySet().stream()
            .anyMatch(key -> key.root() == root));
    }

    @Override
    public void evictAll() {
        invalidateAll();
    }

    @Override
    public <T> T unwrap(Class<T> cls) {
        if (cls.isInstance(this)) {
            return cls.cast(this);
        }
        throw new PersistenceException("Unsupported unwrap type " + cls.getName());
    }

    private MappedEntity entity(Class<?> type) {
        return type == null ? null : mapping.entity(type).orElse(null);
    }

    private EntityKey key(MappedEntity type, Object instance) {
        Object id = type.id(instance);
        if (id == null) {
            throw new UnsupportedState();
        }
        return new EntityKey(type.root(), id);
    }

    private void capture(MappedEntity type, Object instance, Map<EntityKey, Node> nodes) {
        if (!enabled(type)) {
            throw new UnsupportedState();
        }
        EntityKey key = key(type, instance);
        if (nodes.containsKey(key)) {
            return;
        }
        List<Object> values = new ArrayList<>(type.model().attributes().size());
        for (int i = 0; i < type.model().attributes().size(); i++) {
            values.add(null);
        }
        nodes.put(key, new Node(type.model().javaType(), values));
        for (int i = 0; i < values.size(); i++) {
            AttributeModel attribute = type.model().attributes().get(i);
            Object value = type.access().get(instance, i);
            values.set(i, encode(attribute, value, nodes));
        }
        nodes.put(key, new Node(type.model().javaType(), immutableList(values)));
    }

    private Object encode(AttributeModel attribute, Object value, Map<EntityKey, Node> nodes) {
        if (value == null) {
            return null;
        }
        return switch (attribute) {
            case AssociationAttribute association -> encodeAssociation(association, value, nodes);
            case EmbeddedAttribute embedded -> encodeEmbeddable(embedded.embeddable(), value, nodes);
            case ElementCollectionAttribute collection -> encodeCollection(collection, value, nodes);
            case BasicAttribute _ -> copyBasic(value);
        };
    }

    private Object encodeEmbeddable(EmbeddableModel model, Object value, Map<EntityKey, Node> nodes) {
        var access = mapping.access(model);
        if (access == null) {
            throw new UnsupportedState();
        }
        List<Object> values = new ArrayList<>(model.attributes().size());
        for (int i = 0; i < model.attributes().size(); i++) {
            values.add(encode(model.attributes().get(i), access.get(value, i), nodes));
        }
        return immutableList(values);
    }

    private Object encodeAssociation(AssociationAttribute association, Object value, Map<EntityKey, Node> nodes) {
        if (association.singleValued()) {
            MappedEntity target = mapped(value);
            EntityKey key = key(target, value);
            capture(target, value, nodes);
            return new EntityReference(key);
        }
        return collectionState(value, element -> {
            MappedEntity target = mapped(element);
            EntityKey key = key(target, element);
            capture(target, element, nodes);
            return new EntityReference(key);
        }, this::copyKey);
    }

    private Object encodeCollection(ElementCollectionAttribute collection, Object value, Map<EntityKey, Node> nodes) {
        AttributeModel element = collection.element();
        return collectionState(value, item -> encode(element, item, nodes), this::copyKey);
    }

    private CollectionState collectionState(Object value, java.util.function.Function<Object, Object> values,
            java.util.function.Function<Object, Object> keys) {
        if (value instanceof Map<?, ?> map) {
            List<Object> copiedKeys = new ArrayList<>(map.size());
            List<Object> copiedValues = new ArrayList<>(map.size());
            map.forEach((key, item) -> {
                copiedKeys.add(keys.apply(key));
                copiedValues.add(values.apply(item));
            });
            return new CollectionState(true, false, immutableList(copiedKeys), immutableList(copiedValues));
        }
        List<Object> copied = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> copied.add(values.apply(item)));
        } else if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                copied.add(values.apply(Array.get(value, i)));
            }
        } else {
            throw new UnsupportedState();
        }
        return new CollectionState(false, value instanceof Set<?>, List.of(), immutableList(copied));
    }

    private Object decode(AttributeModel attribute, Object encoded, Map<EntityKey, Object> instances) {
        if (encoded == null) {
            return null;
        }
        return switch (attribute) {
            case AssociationAttribute association -> decodeAssociation(association, encoded, instances);
            case EmbeddedAttribute embedded -> decodeEmbeddable(embedded.embeddable(), encoded, instances);
            case ElementCollectionAttribute collection -> decodeCollection(collection, encoded, instances);
            case BasicAttribute _ -> copyBasic(encoded);
        };
    }

    private Object decodeEmbeddable(EmbeddableModel model, Object encoded, Map<EntityKey, Object> instances) {
        var access = mapping.access(model);
        if (access == null) {
            throw new UnsupportedState();
        }
        List<?> encodedValues = (List<?>) encoded;
        Object[] values = new Object[encodedValues.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = decode(model.attributes().get(i), encodedValues.get(i), instances);
        }
        if (access.type().isRecord()) {
            return access.construct(values);
        }
        Object copy = access.instantiate();
        access.write(copy, values);
        return copy;
    }

    private Object decodeAssociation(AssociationAttribute association, Object encoded, Map<EntityKey, Object> instances) {
        if (association.singleValued()) {
            return instances.get(((EntityReference) encoded).key());
        }
        return collection((CollectionState) encoded, item -> instances.get(((EntityReference) item).key()),
            key -> decodeKey(key, instances));
    }

    private Object decodeCollection(ElementCollectionAttribute collection, Object encoded, Map<EntityKey, Object> instances) {
        return collection((CollectionState) encoded, item -> decode(collection.element(), item, instances),
            key -> decodeKey(key, instances));
    }

    private Object collection(CollectionState state, java.util.function.Function<Object, Object> values,
            java.util.function.Function<Object, Object> keys) {
        if (state.map()) {
            Map<Object, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < state.keys().size(); i++) {
                map.put(keys.apply(state.keys().get(i)), values.apply(state.values().get(i)));
            }
            return map;
        }
        List<Object> items = new ArrayList<>(state.values().size());
        state.values().forEach(item -> items.add(values.apply(item)));
        return state.set() ? new LinkedHashSet<>(items) : items;
    }

    private Object copyKey(Object key) {
        if (key != null && mapping.entity(key.getClass()).isPresent()) {
            MappedEntity type = mapped(key);
            EntityKey entityKey = key(type, key);
            return new EntityReference(entityKey);
        }
        return copyBasic(key);
    }

    private Object decodeKey(Object key, Map<EntityKey, Object> instances) {
        return key instanceof EntityReference reference ? instances.get(reference.key()) : copyBasic(key);
    }

    private MappedEntity mapped(Object instance) {
        return mapping.entity(instance.getClass()).orElseThrow(UnsupportedState::new);
    }

    private static Object copyBasic(Object value) {
        if (value == null || immutable(value.getClass())) {
            return value;
        }
        if (value instanceof Date date) {
            return date.clone();
        }
        if (value instanceof Calendar calendar) {
            return calendar.clone();
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            Object copy = Array.newInstance(value.getClass().getComponentType(), length);
            if (value.getClass().getComponentType().isPrimitive()) {
                System.arraycopy(value, 0, copy, 0, length);
            } else {
                for (int i = 0; i < length; i++) {
                    Array.set(copy, i, copyBasic(Array.get(value, i)));
                }
            }
            return copy;
        }
        if (value instanceof Serializable serializable) {
            return serializedCopy(serializable);
        }
        throw new UnsupportedState();
    }

    private static boolean immutable(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || type == String.class || type == Boolean.class
            || type == Character.class || type == Byte.class || type == Short.class || type == Integer.class
            || type == Long.class || type == Float.class || type == Double.class || type == UUID.class
            || Temporal.class.isAssignableFrom(type) && type.getPackageName().equals("java.time")
            || type == Class.class || type == BigDecimal.class || type == BigInteger.class;
    }

    private static Object serializedCopy(Serializable value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(value);
            }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                return in.readObject();
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new UnsupportedState();
        }
    }

    private static <T> List<T> immutableList(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    enum CacheStoreModeSetting {
        USE, REFRESH, BYPASS
    }

    private static final class UnsupportedState extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
