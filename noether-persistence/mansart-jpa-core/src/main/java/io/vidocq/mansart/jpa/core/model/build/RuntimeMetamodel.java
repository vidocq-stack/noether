/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.model.build;

import io.vidocq.mansart.jpa.core.model.*;
import io.vidocq.mansart.jpa.core.model.source.ClassFileSource;
import io.vidocq.mansart.jpa.core.model.source.ClassInfos;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.metamodel.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Member;
import java.util.*;
import java.util.function.Predicate;

/** The chapter 5 model is a view of the mapped attributes, never a second access planner. */
public final class RuntimeMetamodel implements Metamodel {
    private final Map<Class<?>, Managed<?>> managed = new LinkedHashMap<>();
    private final ClassInfos source;
    private final ClassLoader loader;

    public RuntimeMetamodel(PersistenceUnitModel unit, ClassLoader loader) {
        this(unit, new ClassFileSource(loader), loader);
    }

    /** The metamodel of {@code unit}, whose classes {@code source} describes as its model was built from them. */
    public RuntimeMetamodel(PersistenceUnitModel unit, ClassInfos source, ClassLoader loader) {
        this.loader = loader;
        this.source = source;
        for (EntityModel entity : unit.entities()) {
            managed.put(entity.javaType(), new Entity<>(entity));
        }
        for (EntityModel entity : unit.entities()) {
            for (Class<?> superclass = entity.javaType().getSuperclass(); superclass != null;
                    superclass = superclass.getSuperclass()) {
                if (source.read(superclass.getName()).map(c -> c.isAnnotated("jakarta.persistence.MappedSuperclass")).orElse(false)) {
                    Class<?> type = superclass;
                    managed.computeIfAbsent(type, t -> new MappedSuperclass<>(t,
                        entity.attributes().stream().filter(a -> a.declaringClass().isAssignableFrom(type)).toList(), entity.id()));
                }
            }
            collectEmbeddables(entity.attributes());
        }
        for (Managed<?> type : managed.values()) {
            type.initialize();
        }
        Set<Class<?>> generated = new HashSet<>();
        ServiceLoader.load(io.vidocq.mansart.jpa.core.spi.ManagedAccessProvider.class, loader)
            .forEach(provider -> generated.addAll(provider.populateMetamodel(this)));
        for (Managed<?> type : managed.values()) if (!generated.contains(type.javaType)) populate(type);
    }

    private void collectEmbeddables(List<AttributeModel> attributes) {
        for (AttributeModel attribute : attributes) {
            if (attribute instanceof EmbeddedAttribute embedded) {
                EmbeddableModel model = embedded.embeddable();
                if (!managed.containsKey(model.javaType())) {
                    managed.put(model.javaType(), new Embeddable<>(model));
                    collectEmbeddables(model.attributes());
                }
            } else if (attribute instanceof ElementCollectionAttribute collection && collection.element() != null) {
                collectEmbeddables(List.of(collection.element()));
            }
            CollectionIndex index = attribute instanceof ElementCollectionAttribute c ? c.index()
                : attribute instanceof AssociationAttribute a ? a.index() : null;
            if (index instanceof CollectionIndex.ByEmbedded key) collectEmbeddables(List.of(key.key()));
        }
    }

    /** §6.2.1.1: public static canonical fields need exports, not private entity-package opens. */
    private void populate(Managed<?> type) {
        String name = type.javaType.getName() + "_";
        Class<?> canonical;
        try {
            canonical = Class.forName(name, true, loader);
        } catch (ClassNotFoundException absent) {
            return;
        }
        var info = source.read(name).orElseThrow();
        for (var field : info.fields()) {
            if (!field.isStatic()) continue;
            Object value = field.name().equals("class_") ? type : type.attributes.get(field.name());
            if (value == null) continue;
            Class<?> fieldType = Types.load(field.type(), loader);
            if (!fieldType.isInstance(value)) continue;
            try {
                MethodHandles.Lookup lookup=MethodHandles.publicLookup();
                if (!canonical.getModule().isExported(canonical.getPackageName())
                        && canonical.getModule().isOpen(canonical.getPackageName(),RuntimeMetamodel.class.getModule()))
                    lookup=MethodHandles.privateLookupIn(canonical,MethodHandles.lookup());
                lookup.findStaticSetter(canonical, field.name(), fieldType).invoke(value);
            } catch (Throwable failure) {
                throw new PersistenceException("Cannot populate canonical metamodel " + name + "." + field.name(), failure);
            }
        }
    }

    @Override public EntityType<?> entity(String name) {
        return getEntities().stream().filter(e -> e.getName().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Not an entity: " + name));
    }
    @Override public <X> EntityType<X> entity(Class<X> type) { return checked(type, EntityType.class); }
    @Override public <X> ManagedType<X> managedType(Class<X> type) { return checked(type, ManagedType.class); }
    @Override public <X> EmbeddableType<X> embeddable(Class<X> type) { return checked(type, EmbeddableType.class); }
    @Override public Set<ManagedType<?>> getManagedTypes() { return new LinkedHashSet<>(managed.values()); }
    @Override public Set<EntityType<?>> getEntities() {
        Set<EntityType<?>> result = new LinkedHashSet<>();
        managed.values().forEach(t -> { if (t instanceof EntityType<?> e) result.add(e); });
        return result;
    }
    @Override public Set<EmbeddableType<?>> getEmbeddables() {
        Set<EmbeddableType<?>> result = new LinkedHashSet<>();
        managed.values().forEach(t -> { if (t instanceof EmbeddableType<?> e) result.add(e); });
        return result;
    }
    @SuppressWarnings("unchecked") // Registry key is the exact Java type represented by this managed type.
    private <X, M> M checked(Class<X> type, Class<?> kind) {
        Managed<?> result = managed.get(type);
        if (!kind.isInstance(result)) throw new IllegalArgumentException("Not a " + kind.getSimpleName() + ": " + type.getName());
        return (M) result;
    }
    @SuppressWarnings("unchecked") // A Type is constructed from, and retains, this exact Class token.
    private <X> Type<X> type(Class<X> javaType) {
        Managed<?> result = managed.get(javaType);
        return result == null ? new Basic<>(javaType) : (Type<X>) result;
    }
    private record Basic<X>(Class<X> getJavaType) implements Type<X> {
        @Override public PersistenceType getPersistenceType() { return PersistenceType.BASIC; }
    }

    private class Managed<X> implements ManagedType<X> {
        final Class<X> javaType;
        final PersistenceType persistenceType;
        final List<AttributeModel> models;
        final Map<String, Attribute<? super X, ?>> attributes = new LinkedHashMap<>();
        @SuppressWarnings("unchecked") // The class token originates in the immutable mapped model.
        Managed(Class<?> javaType, PersistenceType kind, List<AttributeModel> models) {
            this.javaType = (Class<X>) javaType;
            persistenceType = kind;
            this.models = models;
        }
        void initialize() {
            for (AttributeModel model : models) {
                attributes.put(model.name(), makeAttribute(model));
            }
        }
        @SuppressWarnings("unchecked") // The declaring model owns the attribute; inherited attributes retain that owner.
        private Attribute<? super X, ?> makeAttribute(AttributeModel model) {
            Managed<?> owner = managed.get(model.declaringClass());
            if (owner == null) owner = this;
            if (owner != this && owner.attributes.containsKey(model.name())) {
                return (Attribute<? super X, ?>) owner.attributes.get(model.name());
            }
            Attribute<?, ?> result;
            if (model instanceof ElementCollectionAttribute || model instanceof AssociationAttribute a && !a.singleValued()) {
                if (Map.class.isAssignableFrom(model.javaType())) result = new MapAttr<>(owner, model);
                else if (Set.class.isAssignableFrom(model.javaType())) result = new SetAttr<>(owner, model);
                else if (List.class.isAssignableFrom(model.javaType())) result = new ListAttr<>(owner, model);
                else result = new CollectionAttr<>(owner, model);
            } else {
                result = new Singular<>(owner, model);
            }
            if (owner != this) owner.store(model.name(), result);
            return (Attribute<? super X, ?>) result;
        }
        @SuppressWarnings("unchecked") // Only an attribute constructed with this owner reaches this helper.
        private void store(String name, Attribute<?, ?> attribute) {
            attributes.put(name, (Attribute<? super X, ?>) attribute);
        }
        @Override public Class<X> getJavaType() { return javaType; }
        @Override public PersistenceType getPersistenceType() { return persistenceType; }
        private boolean declared(Attribute<?, ?> a) { return a.getDeclaringType() == this; }
        @SuppressWarnings("unchecked") // Declared/inherited filtering establishes the owner bounds of the API's sets.
        private <A> Set<A> attributes(boolean declaredOnly, Predicate<Attribute<?, ?>> filter) {
            Set<A> result = new LinkedHashSet<>();
            attributes.values().stream().filter(a -> !declaredOnly || declared(a)).filter(filter)
                .forEach(a -> result.add((A) a));
            return result;
        }
        @SuppressWarnings("unchecked") // The attribute kind and supplied Class tokens are checked before this cast.
        private <A> A attribute(String name, boolean declaredOnly, Class<?> kind, Class<?> value, Class<?> key) {
            Attribute<?, ?> a = attributes.get(name);
            if (!kind.isInstance(a) || declaredOnly && !declared(a)) throw new IllegalArgumentException("No " + kind.getSimpleName()
                + " " + javaType.getName() + "." + name);
            Class<?> actual = a instanceof PluralAttribute<?, ?, ?> p ? p.getBindableJavaType() : a.getJavaType();
            if (value != null && Types.box(value) != Types.box(actual) || key != null && (!(a instanceof MapAttribute<?, ?, ?> m)
                    || Types.box(m.getKeyJavaType()) != Types.box(key))) throw new IllegalArgumentException("Wrong type for " + name);
            return (A) a;
        }
        @Override public Set<Attribute<? super X, ?>> getAttributes() { return attributes(false, a -> true); }
        @Override public Set<Attribute<X, ?>> getDeclaredAttributes() { return attributes(true, a -> true); }
        @Override public Attribute<? super X, ?> getAttribute(String n) { return attribute(n, false, Attribute.class, null, null); }
        @Override public Attribute<X, ?> getDeclaredAttribute(String n) { return attribute(n, true, Attribute.class, null, null); }
        @Override public Set<SingularAttribute<? super X, ?>> getSingularAttributes() { return attributes(false, a -> a instanceof SingularAttribute); }
        @Override public Set<SingularAttribute<X, ?>> getDeclaredSingularAttributes() { return attributes(true, a -> a instanceof SingularAttribute); }
        @Override public Set<PluralAttribute<? super X, ?, ?>> getPluralAttributes() { return attributes(false, a -> a instanceof PluralAttribute); }
        @Override public Set<PluralAttribute<X, ?, ?>> getDeclaredPluralAttributes() { return attributes(true, a -> a instanceof PluralAttribute); }
        @Override public SingularAttribute<? super X, ?> getSingularAttribute(String n) { return attribute(n, false, SingularAttribute.class, null, null); }
        @Override public SingularAttribute<X, ?> getDeclaredSingularAttribute(String n) { return attribute(n, true, SingularAttribute.class, null, null); }
        @Override public <Y> SingularAttribute<? super X, Y> getSingularAttribute(String n, Class<Y> t) { return attribute(n, false, SingularAttribute.class, t, null); }
        @Override public <Y> SingularAttribute<X, Y> getDeclaredSingularAttribute(String n, Class<Y> t) { return attribute(n, true, SingularAttribute.class, t, null); }
        @Override public CollectionAttribute<? super X, ?> getCollection(String n) { return attribute(n, false, CollectionAttribute.class, null, null); }
        @Override public CollectionAttribute<X, ?> getDeclaredCollection(String n) { return attribute(n, true, CollectionAttribute.class, null, null); }
        @Override public <E> CollectionAttribute<? super X, E> getCollection(String n, Class<E> t) { return attribute(n, false, CollectionAttribute.class, t, null); }
        @Override public <E> CollectionAttribute<X, E> getDeclaredCollection(String n, Class<E> t) { return attribute(n, true, CollectionAttribute.class, t, null); }
        @Override public SetAttribute<? super X, ?> getSet(String n) { return attribute(n, false, SetAttribute.class, null, null); }
        @Override public SetAttribute<X, ?> getDeclaredSet(String n) { return attribute(n, true, SetAttribute.class, null, null); }
        @Override public <E> SetAttribute<? super X, E> getSet(String n, Class<E> t) { return attribute(n, false, SetAttribute.class, t, null); }
        @Override public <E> SetAttribute<X, E> getDeclaredSet(String n, Class<E> t) { return attribute(n, true, SetAttribute.class, t, null); }
        @Override public ListAttribute<? super X, ?> getList(String n) { return attribute(n, false, ListAttribute.class, null, null); }
        @Override public ListAttribute<X, ?> getDeclaredList(String n) { return attribute(n, true, ListAttribute.class, null, null); }
        @Override public <E> ListAttribute<? super X, E> getList(String n, Class<E> t) { return attribute(n, false, ListAttribute.class, t, null); }
        @Override public <E> ListAttribute<X, E> getDeclaredList(String n, Class<E> t) { return attribute(n, true, ListAttribute.class, t, null); }
        @Override public MapAttribute<? super X, ?, ?> getMap(String n) { return attribute(n, false, MapAttribute.class, null, null); }
        @Override public MapAttribute<X, ?, ?> getDeclaredMap(String n) { return attribute(n, true, MapAttribute.class, null, null); }
        @Override public <K,V> MapAttribute<? super X, K,V> getMap(String n, Class<K> k, Class<V> v) { return attribute(n, false, MapAttribute.class, v, k); }
        @Override public <K,V> MapAttribute<X, K,V> getDeclaredMap(String n, Class<K> k, Class<V> v) { return attribute(n, true, MapAttribute.class, v, k); }
    }

    private class Identifiable<X> extends Managed<X> implements IdentifiableType<X> {
        final IdModel id;
        Identifiable(Class<?> type, PersistenceType kind, List<AttributeModel> attributes, IdModel id) {
            super(type, kind, attributes);
            this.id = id;
        }
        boolean isId(String name) {
            return switch (id) {
                case IdModel.Single s -> s.attribute().name().equals(name);
                case IdModel.Embedded e -> e.attribute().name().equals(name);
                case IdModel.Derived d -> d.relationship().name().equals(name);
                case IdModel.ByIdClass c -> c.attributes().stream().anyMatch(a -> a.name().equals(name));
            };
        }
        @Override public boolean hasSingleIdAttribute() { return !(id instanceof IdModel.ByIdClass); }
        @Override public boolean hasVersionAttribute() { return getSingularAttributes().stream().anyMatch(SingularAttribute::isVersion); }
        private SingularAttribute<? super X, ?> single(boolean version) {
            if (!version && !hasSingleIdAttribute()) throw new IllegalArgumentException("IdClass has no single id");
            return getSingularAttributes().stream().filter(a -> version ? a.isVersion() : a.isId()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No " + (version ? "version" : "identifier")));
        }
        @SuppressWarnings("unchecked") // The requested Class is validated against the attribute type before the cast.
        private <Y> SingularAttribute<? super X,Y> identity(Class<Y> type, boolean version) {
            var a = single(version);
            if (Types.box(a.getJavaType()) != Types.box(type)) throw new IllegalArgumentException("Wrong identity attribute type");
            return (SingularAttribute<? super X,Y>) a;
        }
        @Override public <Y> SingularAttribute<? super X,Y> getId(Class<Y> t) { return identity(t, false); }
        @Override public <Y> SingularAttribute<? super X,Y> getVersion(Class<Y> t) { return identity(t, true); }
        @Override public <Y> SingularAttribute<X,Y> getDeclaredId(Class<Y> t) { return getDeclaredSingularAttribute(single(false).getName(), t); }
        @Override public <Y> SingularAttribute<X,Y> getDeclaredVersion(Class<Y> t) { return getDeclaredSingularAttribute(single(true).getName(), t); }
        @SuppressWarnings("unchecked") // The superclass chain establishes the IdentifiableType<? super X> bound.
        @Override public IdentifiableType<? super X> getSupertype() {
            for (Class<?> c = javaType.getSuperclass(); c != null; c = c.getSuperclass()) {
                if (managed.get(c) instanceof IdentifiableType<?> t) return (IdentifiableType<? super X>) t;
            }
            return null;
        }
        @Override public Set<SingularAttribute<? super X, ?>> getIdClassAttributes() {
            if (hasSingleIdAttribute()) throw new IllegalArgumentException("Not an IdClass type");
            Set<SingularAttribute<? super X, ?>> result = new LinkedHashSet<>();
            getSingularAttributes().stream().filter(SingularAttribute::isId).forEach(result::add);
            return result;
        }
        @Override public Type<?> getIdType() {
            return id instanceof IdModel.ByIdClass c ? type(c.idClass()) : single(false).getType();
        }
    }
    private final class Entity<X> extends Identifiable<X> implements EntityType<X> {
        private final String name;
        Entity(EntityModel model) {
            super(model.javaType(), PersistenceType.ENTITY, model.attributes(), model.id());
            name = model.entityName();
        }
        @Override public String getName() { return name; }
        @Override public BindableType getBindableType() { return BindableType.ENTITY_TYPE; }
        @Override public Class<X> getBindableJavaType() { return javaType; }
    }
    private final class MappedSuperclass<X> extends Identifiable<X> implements MappedSuperclassType<X> {
        MappedSuperclass(Class<?> type, List<AttributeModel> attributes, IdModel id) {
            super(type, PersistenceType.MAPPED_SUPERCLASS, attributes, id);
        }
    }
    private final class Embeddable<X> extends Managed<X> implements EmbeddableType<X> {
        Embeddable(EmbeddableModel model) { super(model.javaType(), PersistenceType.EMBEDDABLE, model.attributes()); }
    }

    private abstract class Attr<X,Y> implements Attribute<X,Y> {
        final Managed<X> owner;
        final AttributeModel model;
        @SuppressWarnings("unchecked") // Owner is selected by the mapped declaring class, not by its accessor's visibility.
        Attr(Managed<?> owner, AttributeModel model) { this.owner = (Managed<X>) owner; this.model = model; }
        @Override public String getName() { return model.name(); }
        @Override public ManagedType<X> getDeclaringType() { return owner; }
        @SuppressWarnings("unchecked") // Attribute Java type is the exact mapped member type.
        @Override public Class<Y> getJavaType() { return (Class<Y>) model.javaType(); }
        @Override public PersistentAttributeType getPersistentAttributeType() {
            return switch (model) {
                case AssociationAttribute a -> PersistentAttributeType.valueOf(a.kind().name());
                case EmbeddedAttribute e -> PersistentAttributeType.EMBEDDED;
                case ElementCollectionAttribute e -> PersistentAttributeType.ELEMENT_COLLECTION;
                default -> PersistentAttributeType.BASIC;
            };
        }
        @Override public boolean isAssociation() { return model instanceof AssociationAttribute; }
        @Override public boolean isCollection() { return this instanceof PluralAttribute; }
        @Override public Member getJavaMember() {
            // The spec explicitly returns java.lang.reflect.Member metadata; no reflective entity access occurs.
            try {
                if (model.access() == AccessKind.FIELD) return model.declaringClass().getDeclaredField(model.name());
                var info = source.read(model.declaringClass().getName()).orElseThrow();
                var planner = new AccessPlanner(source);
                var members = planner.members(info, model.access());
                String method = members.stream().filter(m -> m.name().equals(model.name()))
                    .map(m -> ((io.vidocq.mansart.jpa.core.model.source.MethodInfo) m.element()).name())
                    .findFirst().orElseThrow();
                return model.declaringClass().getDeclaredMethod(method);
            } catch (ReflectiveOperationException e) {
                throw new PersistenceException("Missing mapped member " + model.name(), e);
            }
        }
    }
    private final class Singular<X,Y> extends Attr<X,Y> implements SingularAttribute<X,Y> {
        Singular(Managed<?> owner, AttributeModel model) { super(owner, model); }
        @Override public boolean isId() { return owner instanceof Identifiable<?> i && i.isId(model.name()); }
        @Override public boolean isVersion() { return model instanceof BasicAttribute b && b.version(); }
        @Override public boolean isOptional() {
            return !isId() && (model instanceof BasicAttribute b ? b.optional()
                : model instanceof AssociationAttribute a ? a.optional() : true);
        }
        @Override public Type<Y> getType() { return type(getJavaType()); }
        @Override public BindableType getBindableType() { return BindableType.SINGULAR_ATTRIBUTE; }
        @Override public Class<Y> getBindableJavaType() { return getJavaType(); }
    }
    private abstract class Plural<X,C,E> extends Attr<X,C> implements PluralAttribute<X,C,E> {
        final Class<E> element;
        @SuppressWarnings("unchecked") // Model target/element or erased signature supplies the collection's element class.
        Plural(Managed<?> owner, AttributeModel model) {
            super(owner, model);
            Class<?> value = model instanceof AssociationAttribute a ? a.targetEntity()
                : ((ElementCollectionAttribute) model).element().javaType();
            element = (Class<E>) value;
        }
        @Override public Type<E> getElementType() { return type(element); }
        @Override public Class<E> getBindableJavaType() { return element; }
        @Override public BindableType getBindableType() { return BindableType.PLURAL_ATTRIBUTE; }
    }
    private final class CollectionAttr<X,E> extends Plural<X,Collection<E>,E> implements CollectionAttribute<X,E> {
        CollectionAttr(Managed<?> owner, AttributeModel model) { super(owner, model); }
        @Override public CollectionType getCollectionType() { return CollectionType.COLLECTION; }
    }
    private final class SetAttr<X,E> extends Plural<X,Set<E>,E> implements SetAttribute<X,E> {
        SetAttr(Managed<?> owner, AttributeModel model) { super(owner, model); }
        @Override public CollectionType getCollectionType() { return CollectionType.SET; }
    }
    private final class ListAttr<X,E> extends Plural<X,List<E>,E> implements ListAttribute<X,E> {
        ListAttr(Managed<?> owner, AttributeModel model) { super(owner, model); }
        @Override public CollectionType getCollectionType() { return CollectionType.LIST; }
    }
    private final class MapAttr<X,K,V> extends Plural<X,Map<K,V>,V> implements MapAttribute<X,K,V> {
        final Class<K> key;
        @SuppressWarnings("unchecked") // The collection index model fixes the map key's Java class.
        MapAttr(Managed<?> owner, AttributeModel model) {
            super(owner, model);
            CollectionIndex index = model instanceof AssociationAttribute a ? a.index() : ((ElementCollectionAttribute) model).index();
            Class<?> keyType = switch (index) {
                case CollectionIndex.ByColumn c -> c.key().javaType();
                case CollectionIndex.ByEmbedded e -> e.key().javaType();
                case CollectionIndex.ByEntity e -> e.entity();
                case CollectionIndex.ByAttribute a -> {
                    var target = managed.get(element);
                    if (a.name().isEmpty() && target instanceof Identifiable<?> i) {
                        yield i.models.stream().filter(m -> i.isId(m.name())).findFirst().orElseThrow().javaType();
                    }
                    yield target.models.stream().filter(m -> m.name().equals(a.name())).findFirst().orElseThrow().javaType();
                }
                default -> {
                    var info = source.read(model.declaringClass().getName()).orElseThrow();
                    var planner = new AccessPlanner(source);
                    var members = planner.members(info, model.access());
                    var member = members.stream().filter(m -> m.name().equals(model.name())).findFirst().orElseThrow();
                    var arguments = GenericSignatures.memberTypeArguments(member.signature(),
                        model.access() == AccessKind.PROPERTY, loader);
                    yield arguments == null ? Object.class : arguments.getFirst();
                }
            };
            key = (Class<K>) keyType;
        }
        @Override public CollectionType getCollectionType() { return CollectionType.MAP; }
        @Override public Class<K> getKeyJavaType() { return key; }
        @Override public Type<K> getKeyType() { return type(key); }
    }
}
