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
package io.vidocq.mansart.jpa.core.mapping;

import io.vidocq.mansart.jpa.core.model.AssociationAttribute;
import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.EntityModel;
import io.vidocq.mansart.jpa.core.model.IdModel;
import io.vidocq.mansart.jpa.core.spi.ManagedAccess;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * An entity of a mapped unit, as the persistence context and the flush engine use it: its model, its access, the root
 * of its hierarchy (identities are unique per root, §2.4), and how to read its identifier from an instance.
 */
public final class MappedEntity {

    private final EntityModel model;
    private final ManagedAccess access;
    private final Class<?> root;
    private final int[] idAttributes;
    private final ManagedAccess embeddedId;
    private final ManagedAccess idClass;
    private final StatePolicy state;
    private final EntityStatements statements;
    private final int rank;
    private final Map<String, int[]> callbacks;
    private final Function<Class<?>, MappedEntity> entities;
    private final List<KeyPart> parts;

    /** @param entities the mapped entities of the unit, by class: the parents of a derived identity, looked up when used */
    MappedEntity(EntityModel model, ManagedAccess access, Class<?> root, ManagedAccess embeddedId, ManagedAccess idClass,
            StatePolicy state, Function<EntityModel, EntityStatements> statements, int rank, Function<Class<?>, MappedEntity> entities) {
        this.model = model;
        this.entities = entities;
        this.parts = parts(model, embeddedId, idClass);
        this.idClass = idClass;
        this.state = state;
        this.access = access;
        this.root = root;
        this.embeddedId = embeddedId;
        this.idAttributes = idIndexes(model);
        this.statements = statements.apply(model);
        this.rank = rank;
        Map<String, List<Integer>> byKind = new HashMap<>();
        for (int i = 0; i < model.callbacks().size(); i++) {
            byKind.computeIfAbsent(model.callbacks().get(i).kind(), k -> new ArrayList<>()).add(i);
        }
        Map<String, int[]> indexes = new HashMap<>();
        byKind.forEach((kind, list) -> indexes.put(kind, list.stream().mapToInt(Integer::intValue).toArray()));
        this.callbacks = Map.copyOf(indexes);
    }

    /**
     * Its place in the insert order of the unit: an entity comes after the entities its owned to-one relationships
     * reference, so that foreign keys are satisfied; deletes go in reverse order.
     */
    public int rank() {
        return rank;
    }

    /** Its insert, update, delete and select by identifier, and their parameters. */
    public EntityStatements statements() {
        return statements;
    }

    /** The indexes, in {@link EntityModel#attributes()}, of the attributes that make the identifier of {@code model}. */
    static int[] idIndexes(EntityModel model) {
        List<AttributeModel> parts = switch (model.id()) {
            case IdModel.Single single -> List.of(single.attribute());
            case IdModel.Embedded embedded -> List.of(embedded.attribute());
            case IdModel.ByIdClass byIdClass -> byIdClass.attributes();
            case IdModel.Derived derived -> List.of(derived.relationship());
        };
        List<Integer> indexes = new ArrayList<>();
        for (AttributeModel part : parts) {
            indexes.add(model.attributes().indexOf(part));
        }
        return indexes.stream().mapToInt(Integer::intValue).toArray();
    }

    public EntityModel model() {
        return model;
    }

    public ManagedAccess access() {
        return access;
    }

    /** Invokes the lifecycle callbacks of {@code kind} on {@code instance}, in the order of §3.6.4. */
    public void callback(String kind, Object instance) {
        int[] indexes = callbacks.get(kind);
        if (indexes != null) {
            for (int index : indexes) {
                access.callback(instance, index);
            }
        }
    }

    /** How its state is kept in snapshots and compared at flush. */
    public StatePolicy state() {
        return state;
    }

    /** The root entity class of the hierarchy: identities are unique per root. */
    public Class<?> root() {
        return root;
    }

    /** The indexes, in {@link EntityModel#attributes()}, of the attributes that make the identifier. */
    public int[] idAttributes() {
        return idAttributes.clone();
    }

    // ---- identity (§2.4) ---------------------------------------------------------------------------------

    /**
     * A part of the identity, in the order of the key columns: the value of an attribute, or of a component of the
     * embedded identifier ({@code component} ≥ 0); or, for a derived identity (§2.4.1), the key of the parent the
     * relationship {@code attribute} references — several values when the parent's key has several columns.
     */
    private record KeyPart(int attribute, int component, boolean parent) {
    }

    /**
     * The parts of the identity of {@code model}, from its identifier and its {@code @MapsId} relationships: a part an
     * {@code @MapsId} names, or the whole identifier for an {@code @MapsId} without value, is the key of the parent;
     * so is an {@code @Id} relationship, and the only {@code @Id} of an entity sharing the id class of its parent.
     */
    private static List<KeyPart> parts(EntityModel model, ManagedAccess embeddedId, ManagedAccess idClass) {
        int whole = mapsId(model, "");
        List<KeyPart> parts = new ArrayList<>();
        switch (model.id()) {
            case IdModel.Single single -> parts.add(whole >= 0 ? new KeyPart(whole, -1, true)
                : new KeyPart(model.attributes().indexOf(single.attribute()), -1, false));
            case IdModel.Derived derived -> parts.add(new KeyPart(model.attributes().indexOf(derived.relationship()), -1, true));
            case IdModel.Embedded embedded -> {
                if (whole >= 0) {
                    parts.add(new KeyPart(whole, -1, true));
                } else {
                    int attribute = model.attributes().indexOf(embedded.attribute());
                    List<AttributeModel> components = embedded.attribute().embeddable().attributes();
                    for (int c = 0; c < components.size(); c++) {
                        int mapped = mapsId(model, components.get(c).name());
                        parts.add(mapped >= 0 ? new KeyPart(mapped, c, true) : new KeyPart(attribute, c, false));
                    }
                }
            }
            case IdModel.ByIdClass byIdClass -> {
                List<AttributeModel> attributes = byIdClass.attributes();
                for (AttributeModel attribute : attributes) {
                    parts.add(new KeyPart(model.attributes().indexOf(attribute), -1, attribute instanceof AssociationAttribute));
                }
                if (attributes.size() == 1 && parts.getFirst().parent() && idClass == null) {
                    // §2.4.1.3 example 5a: the id class is the parent's, the relationship its whole identifier
                    parts.set(0, new KeyPart(parts.getFirst().attribute(), -1, true));
                }
            }
        }
        return List.copyOf(parts);
    }

    /** The relationship whose {@code @MapsId} is {@code value} ({@code ""}: the whole identifier), or -1. */
    private static int mapsId(EntityModel model, String value) {
        for (int i = 0; i < model.attributes().size(); i++) {
            if (model.attributes().get(i) instanceof AssociationAttribute association && value.equals(association.mapsId())) {
                return i;
            }
        }
        return -1;
    }

    /** Whether the identity is the parent's whole key: a relationship identifier, or an {@code @MapsId} without value. */
    private boolean derivedWhole() {
        return parts.size() == 1 && parts.getFirst().parent() && parts.getFirst().component() < 0;
    }

    /** The entity of the parent a relationship part references. */
    private MappedEntity parent(KeyPart part, Object target) {
        MappedEntity type = target == null ? null : entities.apply(target.getClass());
        if (type == null) {
            type = entities.apply(((AssociationAttribute) model.attributes().get(part.attribute())).targetEntity());
        }
        return type;
    }

    /** Whether a find keeps the identifier object it was given (§3.2.8): a simple identifier only. */
    public boolean keepsFoundIdentifier() {
        return model.id() instanceof IdModel.Single && !derivedWhole();
    }

    /**
     * The identity of {@code instance}: the values of its key columns — the identifier value for a single column, a
     * {@link CompositeId} of them otherwise, the key of the parent for the parts of a derived identity; {@code null}
     * while it is not assigned (a {@code null} part, or a generated primitive still 0).
     */
    public Object id(Object instance) {
        List<Object> values = new ArrayList<>();
        boolean generated = model.id() instanceof IdModel.Single single && single.generation().isPresent();
        for (KeyPart part : parts) {
            Object value = access.get(instance, part.attribute());
            if (part.parent()) {
                Object parentId = value == null ? null : parent(part, value).id(value);
                if (parentId == null) {
                    return null;
                }
                values.addAll(Arrays.asList(parent(part, value).statements().keyValues(parentId)));
                continue;
            }
            if (part.component() >= 0) {
                value = value == null ? null : embeddedId.get(value, part.component());
            }
            value = assigned(value, generated);
            if (value == null) {
                return null;
            }
            values.add(value);
        }
        return values.size() == 1 ? values.getFirst() : CompositeId.of(values.toArray());
    }

    /**
     * The identifier object an application sees on {@code instance}: its {@code @Id} or {@code @EmbeddedId} value, an
     * instance of its id class, or, for a relationship identifier, the parent's.
     */
    public Object idObject(Object instance) {
        if (derivedWhole() && !(model.id() instanceof IdModel.Single) && !(model.id() instanceof IdModel.Embedded)) {
            Object target = access.get(instance, parts.getFirst().attribute());
            return target == null ? null : parent(parts.getFirst(), target).idObject(target);
        }
        return switch (model.id()) {
            case IdModel.Single single -> access.get(instance, model.attributes().indexOf(single.attribute()));
            case IdModel.Embedded embedded -> access.get(instance, model.attributes().indexOf(embedded.attribute()));
            case IdModel.ByIdClass _ -> {
                Object[] components = new Object[parts.size()];
                for (int i = 0; i < components.length; i++) {
                    Object value = access.get(instance, parts.get(i).attribute());
                    components[i] = parts.get(i).parent() && value != null ? parent(parts.get(i), value).idObject(value) : value;
                }
                if (idClass.type().isRecord()) {
                    yield idClass.construct(components);
                }
                Object key = idClass.instantiate();
                idClass.write(key, components);
                yield key;
            }
            case IdModel.Derived _ -> throw new IllegalStateException("a relationship identifier is its parent's");
        };
    }

    /**
     * §2.4.1: writes into {@code instance} the identifier attributes an {@code @MapsId} relationship maps — the
     * {@code @Id}, the {@code @EmbeddedId}, or the parts of it named — from the parent it references, as the provider
     * is to assign them. Nothing for a parent not set yet, nor for an entity without {@code @MapsId}.
     */
    public void deriveId(Object instance) {
        switch (model.id()) {
            case IdModel.Single single when derivedWhole() -> {
                Object target = access.get(instance, parts.getFirst().attribute());
                if (target != null) {
                    access.set(instance, model.attributes().indexOf(single.attribute()), parent(parts.getFirst(), target).idObject(target));
                }
            }
            case IdModel.Embedded embedded when derivedWhole() -> {
                Object target = access.get(instance, parts.getFirst().attribute());
                Object parentId = target == null ? null : parent(parts.getFirst(), target).id(target);
                if (parentId != null) {
                    // a copy of the parent's key, component by component, never the parent's own embeddable
                    Object[] components = parent(parts.getFirst(), target).statements().keyValues(parentId);
                    Object key = embeddedId.type().isRecord() ? embeddedId.construct(components) : embeddedId.instantiate();
                    if (!embeddedId.type().isRecord()) {
                        embeddedId.write(key, components);
                    }
                    access.set(instance, model.attributes().indexOf(embedded.attribute()), key);
                }
            }
            case IdModel.Embedded embedded when parts.stream().anyMatch(KeyPart::parent) && !embeddedId.type().isRecord() -> {
                int attribute = model.attributes().indexOf(embedded.attribute());
                Object key = access.get(instance, attribute);
                if (key == null) {
                    key = embeddedId.instantiate();
                    access.set(instance, attribute, key);
                }
                for (KeyPart part : parts) {
                    Object target = part.parent() ? access.get(instance, part.attribute()) : null;
                    if (target != null) {
                        embeddedId.set(key, part.component(), parent(part, target).idObject(target));
                    }
                }
            }
            default -> {
                // nothing an @MapsId maps
            }
        }
    }

    /**
     * The identity of the identifier an application gives (§3.2.8 find, getReference): a value of the identifier type
     * for a single identifier, an instance of the embeddable for an {@code @EmbeddedId}, of the id class for an
     * {@code @IdClass}, the parent's identifier for a relationship identifier; anything else is an
     * {@link IllegalArgumentException}.
     */
    public Object key(Object primaryKey) {
        if (primaryKey == null) {
            throw new IllegalArgumentException("The identifier of " + model.entityName() + " cannot be null");
        }
        if (derivedWhole()) {
            return parent(parts.getFirst(), null).key(primaryKey);
        }
        List<Object> values = new ArrayList<>();
        switch (model.id()) {
            case IdModel.Single single -> {
                Class<?> type = boxed(single.attribute().javaType());
                if (!type.isInstance(primaryKey)) {
                    throw invalid(primaryKey, type);
                }
                return primaryKey;
            }
            case IdModel.Embedded embedded -> {
                if (!embedded.attribute().javaType().isInstance(primaryKey)) {
                    throw invalid(primaryKey, embedded.attribute().javaType());
                }
                for (KeyPart part : parts) {
                    values(part, embeddedId.get(primaryKey, part.component()), values);
                }
            }
            case IdModel.ByIdClass byIdClass -> {
                if (!byIdClass.idClass().isInstance(primaryKey)) {
                    throw invalid(primaryKey, byIdClass.idClass());
                }
                for (int i = 0; i < parts.size(); i++) {
                    values(parts.get(i), idClass.get(primaryKey, i), values);
                }
            }
            case IdModel.Derived _ -> throw new IllegalStateException("a relationship identifier is its parent's");
        }
        return values.size() == 1 ? values.getFirst() : CompositeId.of(values.toArray());
    }

    /** Adds the key column values of a part of an identifier object: the value, or the key of the parent's identifier. */
    private void values(KeyPart part, Object value, List<Object> values) {
        if (part.parent() && value != null) {
            MappedEntity parent = parent(part, null);
            values.addAll(Arrays.asList(parent.statements().keyValues(parent.key(value))));
        } else {
            values.add(value);
        }
    }

    private IllegalArgumentException invalid(Object primaryKey, Class<?> expected) {
        return new IllegalArgumentException("The identifier of " + model.entityName() + " is a " + expected.getName() + ", not a "
            + primaryKey.getClass().getName());
    }

    private static Class<?> boxed(Class<?> type) {
        return MethodType.methodType(type).wrap().returnType();
    }

    /** A generated integral identifier still 0 is a primitive the database has not assigned yet. */
    private static Object assigned(Object value, boolean generated) {
        return switch (value) {
            case null -> null;
            case Long l when generated && l == 0L -> null;
            case Integer i when generated && i == 0 -> null;
            case Short s when generated && s == 0 -> null;
            case Byte b when generated && b == 0 -> null;
            default -> value;
        };
    }

    private static CompositeId parts(Object[] parts) {
        for (Object part : parts) {
            if (part == null) {
                return null;
            }
        }
        return CompositeId.of(parts);
    }
}
