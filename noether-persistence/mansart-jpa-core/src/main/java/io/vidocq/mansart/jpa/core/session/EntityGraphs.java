/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.session;

import io.vidocq.mansart.jpa.core.model.build.Types;
import io.vidocq.mansart.jpa.core.model.source.AnnotationInfo;
import io.vidocq.mansart.jpa.core.model.source.ClassInfos;
import jakarta.persistence.*;
import jakarta.persistence.metamodel.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** §3.8 metadata and immutable named snapshots. Eager loading satisfies either fetch or load semantics. */
final class EntityGraphs {
    private final Metamodel model;
    private final Map<String,Root<?>> named = new ConcurrentHashMap<>();
    EntityGraphs(Metamodel model,ClassInfos source,ClassLoader loader) {
        this.model=model;
        for (var entity:model.getEntities()) {
            var info=source.read(entity.getJavaType().getName()).orElseThrow();
            List<AnnotationInfo> declarations=new ArrayList<>();
            info.annotation("jakarta.persistence.NamedEntityGraph").ifPresent(declarations::add);
            info.annotation("jakarta.persistence.NamedEntityGraphs").ifPresent(a -> declarations.addAll(a.annotations("value")));
            for (var declaration:declarations) {
                String name=declaration.string("name");
                if (name==null || name.isEmpty()) name=entity.getName();
                var graph=create(entity.getJavaType());
                if (declaration.bool("includeAllAttributes")) entity.getAttributes().forEach(a -> graph.addAttributeNode(a.getName()));
                Map<String,AnnotationInfo> subgraphs=new LinkedHashMap<>();
                declaration.annotations("subgraphs").forEach(a -> subgraphs.put(a.string("name"),a));
                populate(graph,declaration.annotations("attributeNodes"),subgraphs,loader,new HashSet<>());
                for (var subclass:declaration.annotations("subclassSubgraphs")) {
                    Class<?> type=Types.load(subclass.type("type"),loader);
                    populate(graph.treated(type),subclass.annotations("attributeNodes"),subgraphs,loader,new HashSet<>());
                }
                if (named.putIfAbsent(name,graph.copy(name,false))!=null) throw new PersistenceException("Duplicate named entity graph " + name);
            }
        }
    }
    private void populate(Base<?> graph,List<AnnotationInfo> nodes,Map<String,AnnotationInfo> declarations,
            ClassLoader loader,Set<String> active) {
        for (var a:nodes) {
            String name=a.string("value"); graph.addAttributeNode(name);
            for (String member:List.of("subgraph","keySubgraph")) {
                String reference=a.string(member);
                if (reference==null || reference.isEmpty()) continue;
                AnnotationInfo sub=declarations.get(reference);
                if (sub==null || !active.add(reference)) throw new PersistenceException("Invalid named subgraph " + reference);
                Class<?> declared=Types.load(sub.type("type"),loader);
                var node=graph.node(name);
                Class<?> target=member.equals("keySubgraph")?node.keyType():node.valueType();
                if (declared==void.class) declared=target;
                Base<?> child=node.subgraph(declared,member.equals("keySubgraph"));
                populate(child,sub.annotations("attributeNodes"),declarations,loader,active);
                active.remove(reference);
            }
        }
    }
    <T> Root<T> create(Class<T> type) { model.entity(type); return new Root<>(model,type,null,true); }
    EntityGraph<?> get(String name) {
        Root<?> graph=named.get(name);
        if (graph==null) throw new IllegalArgumentException("Unknown entity graph " + name);
        return graph;
    }
    EntityGraph<?> copy(String name) { Root<?> graph=named.get(name); return graph==null?null:graph.copy(name,true); }
    <T> void add(String name,EntityGraph<T> graph) {
        if (!(graph instanceof Root<?> root) || root.model!=model) throw new IllegalArgumentException("Graph belongs to another unit");
        named.put(Objects.requireNonNull(name),root.copy(name,false));
    }
    @SuppressWarnings("unchecked") // Filtering by assignability establishes the named graph root bound.
    <T> List<EntityGraph<? super T>> applicable(Class<T> type) {
        model.entity(type);
        return named.values().stream().filter(g -> g.type.isAssignableFrom(type))
            .<EntityGraph<? super T>>map(g -> (EntityGraph<? super T>) g).toList();
    }
    @SuppressWarnings("unchecked") // Filtering selects exactly roots whose Java type extends the requested entity type.
    <T> Map<String,EntityGraph<? extends T>> typed(Class<T> type) {
        model.entity(type);
        Map<String,EntityGraph<? extends T>> result=new LinkedHashMap<>();
        named.forEach((name,g) -> { if (type.isAssignableFrom(g.type)) result.put(name,(EntityGraph<? extends T>) g); });
        return Collections.unmodifiableMap(result);
    }
    static void validateHint(Object value,Class<?> root,Metamodel model) {
        if (!(value instanceof Root<?> graph) || graph.model!=model || root!=null && !graph.type.isAssignableFrom(root))
            throw new IllegalArgumentException("Graph hint must belong to this persistence unit and match the entity type");
    }
    static abstract class Base<T> implements Graph<T> {
        final Metamodel model;
        final Class<T> type;
        final boolean mutable;
        final Map<String,Node<?>> nodes=new LinkedHashMap<>();
        Base(Metamodel model,Class<T> type,boolean mutable) { this.model=model; this.type=type; this.mutable=mutable; }
        void writable() { if (!mutable) throw new IllegalStateException("Named entity graphs are immutable"); }
        Attribute<? super T,?> attribute(String name) { return model.managedType(type).getAttribute(name); }
        void validate(Attribute<?,?> a) {
            if (!a.getDeclaringType().getJavaType().isAssignableFrom(type) || attribute(a.getName())!=a)
                throw new IllegalArgumentException("Attribute does not belong to this graph");
        }
        Node<?> node(String name) {
            writable();
            var attribute=attribute(name);
            return nodes.computeIfAbsent(name,n -> new Node<>(this,attribute));
        }
        @SuppressWarnings("unchecked") // Named attribute metadata supplies its exact AttributeNode<Y> type.
        @Override public <Y> AttributeNode<Y> addAttributeNode(String name) { return (AttributeNode<Y>) node(name); }
        @Override public <Y> AttributeNode<Y> addAttributeNode(Attribute<? super T,Y> a) { validate(a); return addAttributeNode(a.getName()); }
        @Override public boolean hasAttributeNode(String n) { attribute(n); return nodes.containsKey(n); }
        @Override public boolean hasAttributeNode(Attribute<? super T,?> a) { validate(a); return hasAttributeNode(a.getName()); }
        @SuppressWarnings("unchecked") // String API's caller-supplied type is checked through the mapped attribute.
        @Override public <Y> AttributeNode<Y> getAttributeNode(String n) {
            attribute(n);
            Node<?> result=nodes.get(n);
            if (result==null) throw new NoSuchElementException("Attribute not present in graph: " + n);
            return (AttributeNode<Y>) result;
        }
        @Override public <Y> AttributeNode<Y> getAttributeNode(Attribute<? super T,Y> a) { validate(a); return getAttributeNode(a.getName()); }
        @Override public void removeAttributeNode(String n) { writable(); attribute(n); nodes.remove(n); }
        @Override public void removeAttributeNode(Attribute<? super T,?> a) { validate(a); removeAttributeNode(a.getName()); }
        @Override public void removeAttributeNodes(Attribute.PersistentAttributeType type) { writable(); nodes.values().removeIf(n -> n.attribute.getPersistentAttributeType()==type); }
        @Override public void addAttributeNodes(String... names) { writable(); for (String n:names) addAttributeNode(n); }
        @Override public void addAttributeNodes(Attribute<? super T,?>... attributes) { writable(); for (var a:attributes) addAttributeNode(a); }
        @Override public List<AttributeNode<?>> getAttributeNodes() { return new ArrayList<>(nodes.values()); }
        @SuppressWarnings("unchecked") // Node checks the target's assignability and managed type before creating its typed subgraph.
        private <X> Subgraph<X> sub(String name,Class<X> requested,boolean key,boolean elements) {
            Node<?> node=node(name);
            if (elements && !(node.attribute instanceof PluralAttribute)) throw new IllegalArgumentException("Not a plural attribute");
            Class<?> target=key?node.keyType():node.valueType();
            return (Subgraph<X>) node.subgraph(requested==null?target:requested,key);
        }
        @Override public <X> Subgraph<X> addSubgraph(String n) { return sub(n,null,false,false); }
        @Override public <X> Subgraph<X> addSubgraph(String n,Class<X> t) { return sub(n,t,false,false); }
        @Override public <X> Subgraph<X> addSubgraph(Attribute<? super T,X> a) { validate(a); return sub(a.getName(),null,false,false); }
        @Override public <Y> Subgraph<Y> addTreatedSubgraph(Attribute<? super T,? super Y> a,Class<Y> t) { validate(a); return sub(a.getName(),t,false,false); }
        @Override public <X> Subgraph<? extends X> addSubgraph(Attribute<? super T,X> a,Class<? extends X> t) { validate(a); return sub(a.getName(),t,false,false); }
        @Override public <E> Subgraph<E> addElementSubgraph(PluralAttribute<? super T,?,E> a) { validate(a); return sub(a.getName(),null,false,true); }
        @Override public <E> Subgraph<E> addTreatedElementSubgraph(PluralAttribute<? super T,?,? super E> a,Class<E> t) { validate(a); return sub(a.getName(),t,false,true); }
        @Override public <X> Subgraph<X> addElementSubgraph(String n) { return sub(n,null,false,true); }
        @Override public <X> Subgraph<X> addElementSubgraph(String n,Class<X> t) { return sub(n,t,false,true); }
        @Override public <K> Subgraph<K> addMapKeySubgraph(MapAttribute<? super T,K,?> a) { validate(a); return sub(a.getName(),null,true,true); }
        @Override public <K> Subgraph<K> addTreatedMapKeySubgraph(MapAttribute<? super T,? super K,?> a,Class<K> t) { validate(a); return sub(a.getName(),t,true,true); }
        @Override public <X> Subgraph<X> addKeySubgraph(Attribute<? super T,X> a) { validate(a); return sub(a.getName(),null,true,true); }
        @Override public <X> Subgraph<? extends X> addKeySubgraph(Attribute<? super T,X> a,Class<? extends X> t) { validate(a); return sub(a.getName(),t,true,true); }
        @Override public <X> Subgraph<X> addKeySubgraph(String n) { return sub(n,null,true,true); }
        @Override public <X> Subgraph<X> addKeySubgraph(String n,Class<X> t) { return sub(n,t,true,true); }
        void copyInto(Base<?> copy) {
            nodes.forEach((name,node) -> {
                var target=new Node<>(copy,node.attribute); copy.nodes.put(name,target);
                node.subgraphs.forEach((type,g) -> target.subgraphs.put(type,g.copy(copy.mutable)));
                node.keys.forEach((type,g) -> target.keys.put(type,g.copy(copy.mutable)));
            });
        }
    }
    static final class Root<T> extends Base<T> implements EntityGraph<T> {
        final String name;
        final Map<Class<?>,Sub<?>> treated=new LinkedHashMap<>();
        Root(Metamodel model,Class<T> type,String name,boolean mutable) { super(model,type,mutable); this.name=name; }
        @Override public String getName() { return name; }
        Root<T> copy(String name,boolean mutable) {
            var copy=new Root<>(model,type,name,mutable); copyInto(copy);
            treated.forEach((type,graph) -> copy.treated.put(type,graph.copy(mutable))); return copy;
        }
        Sub<?> treated(Class<?> subtype) {
            writable();
            if (!type.isAssignableFrom(subtype)) throw new IllegalArgumentException("Not an entity subtype");
            model.entity(subtype);
            return treated.computeIfAbsent(subtype,t -> new Sub<>(model,t,mutable));
        }
        @SuppressWarnings("unchecked") // Subtype class token validates the graph's exact typed root.
        @Override public <S extends T> Subgraph<S> addTreatedSubgraph(Class<S> subtype) { return (Subgraph<S>) treated(subtype); }
        @SuppressWarnings("unchecked") // Deprecated unbounded API is validated by the same root-subtype check.
        @Override public <X> Subgraph<? extends X> addSubclassSubgraph(Class<? extends X> subtype) { return (Subgraph<? extends X>) treated(subtype); }
    }
    static final class Sub<T> extends Base<T> implements Subgraph<T> {
        Sub(Metamodel model,Class<T> type,boolean mutable) { super(model,type,mutable); }
        @Override public Class<T> getClassType() { return type; }
        Sub<T> copy(boolean mutable) { var copy=new Sub<>(model,type,mutable); copyInto(copy); return copy; }
    }
    static final class Node<T> implements AttributeNode<T> {
        final Base<?> owner;
        final Attribute<?,?> attribute;
        final Map<Class<?>,Sub<?>> subgraphs=new LinkedHashMap<>();
        final Map<Class<?>,Sub<?>> keys=new LinkedHashMap<>();
        Node(Base<?> owner,Attribute<?,?> attribute) { this.owner=owner; this.attribute=attribute; }
        @Override public String getAttributeName() { return attribute.getName(); }
        Class<?> valueType() { return attribute instanceof PluralAttribute<?,?,?> p?p.getBindableJavaType():attribute.getJavaType(); }
        Class<?> keyType() {
            if (!(attribute instanceof MapAttribute<?,?,?> m)) throw new IllegalArgumentException("Not a map attribute");
            return m.getKeyJavaType();
        }
        Sub<?> subgraph(Class<?> requested,boolean key) {
            owner.writable();
            Class<?> expected=key?keyType():valueType();
            if (!expected.isAssignableFrom(requested)) throw new IllegalArgumentException("Not a graph target subtype");
            owner.model.managedType(requested);
            return (key?keys:subgraphs).computeIfAbsent(requested,t -> new Sub<>(owner.model,t,owner.mutable));
        }
        @SuppressWarnings("rawtypes") // AttributeNode's Jakarta API itself declares raw Class and Subgraph keys/values.
        @Override public Map<Class,Subgraph> getSubgraphs() { return new LinkedHashMap<>(subgraphs); }
        @SuppressWarnings("rawtypes") // AttributeNode's Jakarta API itself declares raw Class and Subgraph keys/values.
        @Override public Map<Class,Subgraph> getKeySubgraphs() { return new LinkedHashMap<>(keys); }
    }
}
