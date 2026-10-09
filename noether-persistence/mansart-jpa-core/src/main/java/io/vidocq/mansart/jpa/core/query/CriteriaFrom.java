/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.query;

import io.vidocq.mansart.jpa.core.query.jpql.Ast;
import jakarta.persistence.criteria.*;
import jakarta.persistence.metamodel.*;
import java.util.*;
import java.util.function.Supplier;

final class CriteriaFrom {
    private CriteriaFrom() {}

    static class FromNode<Z,X> extends CriteriaExpressions.PathNode<X> implements From<Z,X> {
        final CriteriaQueries.State<?> state;
        final String variable;
        final List<JoinNode<X,?>> joins = new ArrayList<>();
        final List<JoinNode<X,?>> fetches = new ArrayList<>();
        final List<FromNode<?,?>> treatedViews = new ArrayList<>();
        From<Z,X> correlation;
        FromNode(Class<X> type, Bindable<X> model, CriteriaQueries.State<?> state, String variable, Path<?> parent) {
            super(type, () -> new Ast.Path(List.of(variable)), state.builder.metamodel, model, parent);
            this.state = state; this.variable = variable;
        }
        @Override public Set<Join<X,?>> getJoins() { return new LinkedHashSet<>(joins); }
        @Override public Set<Fetch<X,?>> getFetches() { return new LinkedHashSet<>(fetches); }
        @Override Ast.Path append(String name) { return (Ast.Path) super.append(name); }
        @Override public boolean isCorrelated() { return correlation != null; }
        @Override public From<Z,X> getCorrelationParent() {
            if (correlation == null) throw new IllegalStateException("Not correlated");
            return correlation;
        }
        List<Ast.Join> astJoins() {
            List<Ast.Join> result = new ArrayList<>();
            for (var join : joins) { result.add(join.joinAst(false)); result.addAll(join.astJoins()); }
            for (var fetch : fetches) { result.add(fetch.joinAst(true)); result.addAll(fetch.astJoins()); }
            treatedViews.forEach(view -> result.addAll(view.astJoins()));
            return result;
        }
        void parameters(Set<ParameterExpression<?>> result) {
            super.parameters(result);
            joins.forEach(j -> { if (j.on != null) CriteriaExpressions.node(j.on).parameters(result); j.parameters(result); });
            fetches.forEach(j -> j.parameters(result));
            treatedViews.forEach(view -> view.parameters(result));
        }
        @SuppressWarnings("unchecked") // Model attribute kind and target Java type select exactly the corresponding join class.
        private <Y> JoinNode<X,Y> create(Attribute<? super X,?> a, JoinType type, boolean fetch) {
            if (type==JoinType.RIGHT) throw new UnsupportedOperationException("RIGHT joins are not portable (§6.3.18)");
            if (!fetch) validate(a);
            Class<?> target = a instanceof PluralAttribute<?,?,?> p ? p.getBindableJavaType() : a.getJavaType();
            String variable = state.variable();
            JoinNode<X,?> joined;
            if (a instanceof MapAttribute<?,?,?> m) joined = new MapNode<>(this, m, type, variable);
            else if (a instanceof ListAttribute<?,?> l) joined = new ListNode<>(this, l, type, variable);
            else if (a instanceof SetAttribute<?,?> s) joined = new SetNode<>(this, s, type, variable);
            else if (a instanceof CollectionAttribute<?,?> c) joined = new CollectionNode<>(this, c, type, variable);
            else joined = new JoinNode<>(this, a, (Class<Y>) target, (Bindable<Y>) a, type, variable);
            (fetch ? fetches : joins).add(joined);
            return (JoinNode<X,Y>) joined;
        }
        @Override public <Y> Join<X,Y> join(SingularAttribute<? super X,Y> a) { return join(a, JoinType.INNER); }
        @Override public <Y> Join<X,Y> join(SingularAttribute<? super X,Y> a, JoinType t) { return create(a,t,false); }
        @Override public <Y> CollectionJoin<X,Y> join(CollectionAttribute<? super X,Y> a) { return join(a,JoinType.INNER); }
        @Override public <Y> SetJoin<X,Y> join(SetAttribute<? super X,Y> a) { return join(a,JoinType.INNER); }
        @Override public <Y> ListJoin<X,Y> join(ListAttribute<? super X,Y> a) { return join(a,JoinType.INNER); }
        @Override public <K,V> MapJoin<X,K,V> join(MapAttribute<? super X,K,V> a) { return join(a,JoinType.INNER); }
        @Override public <Y> CollectionJoin<X,Y> join(CollectionAttribute<? super X,Y> a, JoinType t) { return (CollectionJoin<X,Y>) create(a,t,false); }
        @Override public <Y> SetJoin<X,Y> join(SetAttribute<? super X,Y> a, JoinType t) { return (SetJoin<X,Y>) create(a,t,false); }
        @Override public <Y> ListJoin<X,Y> join(ListAttribute<? super X,Y> a, JoinType t) { return (ListJoin<X,Y>) create(a,t,false); }
        @SuppressWarnings("unchecked") // Map key and value tokens come from the supplied validated MapAttribute.
        @Override public <K,V> MapJoin<X,K,V> join(MapAttribute<? super X,K,V> a, JoinType t) { return (MapJoin<X,K,V>) create(a,t,false); }
        @Override public <A,Y> Join<A,Y> join(String n) { return join(n,JoinType.INNER); }
        @SuppressWarnings("unchecked") // String-based API lets the caller choose owner/target generics; metadata checks the named attribute.
        @Override public <A,Y> Join<A,Y> join(String n, JoinType t) { return (Join<A,Y>) create(attribute(n),t,false); }
        private <J> J typedJoin(String n, JoinType t, Class<?> kind) {
            Attribute<? super X,?> a = attribute(n);
            if (!kind.isInstance(a)) throw new IllegalArgumentException("Wrong collection kind: " + n);
            return castJoin(create(a,t,false));
        }
        @SuppressWarnings("unchecked") // typedJoin has checked the specific collection kind and its model.
        private <J> J castJoin(JoinNode<X,?> join) { return (J) join; }
        @Override public <A,Y> CollectionJoin<A,Y> joinCollection(String n) { return joinCollection(n,JoinType.INNER); }
        @Override public <A,Y> CollectionJoin<A,Y> joinCollection(String n,JoinType t) { return typedJoin(n,t,CollectionAttribute.class); }
        @Override public <A,Y> SetJoin<A,Y> joinSet(String n) { return joinSet(n,JoinType.INNER); }
        @Override public <A,Y> SetJoin<A,Y> joinSet(String n,JoinType t) { return typedJoin(n,t,SetAttribute.class); }
        @Override public <A,Y> ListJoin<A,Y> joinList(String n) { return joinList(n,JoinType.INNER); }
        @Override public <A,Y> ListJoin<A,Y> joinList(String n,JoinType t) { return typedJoin(n,t,ListAttribute.class); }
        @Override public <A,K,V> MapJoin<A,K,V> joinMap(String n) { return joinMap(n,JoinType.INNER); }
        @Override public <A,K,V> MapJoin<A,K,V> joinMap(String n,JoinType t) { return typedJoin(n,t,MapAttribute.class); }
        @Override public <Y> Join<X,Y> join(Class<Y> type) { return join(type,JoinType.INNER); }
        @Override public <Y> Join<X,Y> join(Class<Y> type,JoinType t) { return join(metamodel.entity(type),t); }
        @Override public <Y> Join<X,Y> join(EntityType<Y> type) { return join(type,JoinType.INNER); }
        @Override public <Y> Join<X,Y> join(EntityType<Y> type,JoinType t) {
            if (t==JoinType.RIGHT) throw new UnsupportedOperationException("RIGHT joins are not portable (§6.3.18)");
            var joined = new JoinNode<X,Y>(this,null,type.getJavaType(),type,t,state.variable());
            joins.add(joined);
            return joined;
        }
        @Override public <Y> Fetch<X,Y> fetch(SingularAttribute<? super X,Y> a) { return fetch(a,JoinType.INNER); }
        @Override public <Y> Fetch<X,Y> fetch(SingularAttribute<? super X,Y> a,JoinType t) { return create(a,t,true); }
        @Override public <Y> Fetch<X,Y> fetch(PluralAttribute<? super X,?,Y> a) { return fetch(a,JoinType.INNER); }
        @Override public <Y> Fetch<X,Y> fetch(PluralAttribute<? super X,?,Y> a,JoinType t) { return create(a,t,true); }
        @Override public <A,Y> Fetch<A,Y> fetch(String n) { return fetch(n,JoinType.INNER); }
        @SuppressWarnings("unchecked") // String-based fetch bounds are caller-supplied; the attribute is validated at creation.
        @Override public <A,Y> Fetch<A,Y> fetch(String n,JoinType t) { return (Fetch<A,Y>) create(attribute(n),t,true); }
    }
    static final class RootNode<X> extends FromNode<X,X> implements Root<X> {
        final EntityType<X> entity;
        RootNode(EntityType<X> entity, CriteriaQueries.State<?> state, String variable) {
            super(entity.getJavaType(),entity,state,variable,null);
            this.entity = entity;
        }
        @Override public EntityType<X> getModel() { return entity; }
        Ast.Range range() { return new Ast.Range(entity.getName(),null,variable,astJoins()); }
    }
    static class JoinNode<Z,X> extends FromNode<Z,X> implements Join<Z,X>, Fetch<Z,X> {
        final FromNode<?,Z> from;
        final Attribute<? super Z,?> attribute;
        final JoinType joinType;
        Predicate on;
        JoinNode(FromNode<?,Z> from,Attribute<? super Z,?> attribute,Class<X> type,Bindable<X> model,JoinType joinType,String variable) {
            super(type,model,from.state,variable,from);
            this.from = from; this.attribute = attribute; this.joinType = joinType;
        }
        @Override public Join<Z,X> on(Expression<Boolean> e) { on = state.builder.and(e); return this; }
        @Override public Join<Z,X> on(Predicate... e) { on = e.length == 0 ? null : state.builder.and(e); return this; }
        @Override public Predicate getOn() { return on; }
        @Override public Attribute<? super Z,?> getAttribute() { return attribute; }
        @Override public From<?,Z> getParent() { return from; }
        @Override public JoinType getJoinType() { return joinType; }
        Ast.Join joinAst(boolean fetch) {
            var path = attribute == null ? new Ast.Path(List.of(metamodel.entity(type).getName())) : from.append(attribute.getName());
            return new Ast.Join(joinType == JoinType.LEFT ? Ast.Join.Kind.LEFT : Ast.Join.Kind.INNER,
                fetch,path,variable,on == null ? null : CriteriaExpressions.ast(on));
        }
        @SuppressWarnings("unchecked") // A correlated copy preserves this node's exact model, owner and join kind.
        JoinNode<Z,X> correlatedCopy(CriteriaQueries.State<?> state) {
            FromNode<?,Z> parent=state==this.state?from:new FromNode<Object,Z>(from.type,from.model,state,from.variable,from.parent);
            JoinNode<Z,?> copy;
            if (attribute instanceof MapAttribute<?,?,?> m) copy=new MapNode<>(parent,m,joinType,variable);
            else if (attribute instanceof ListAttribute<?,?> l) copy=new ListNode<>(parent,l,joinType,variable);
            else if (attribute instanceof SetAttribute<?,?> s) copy=new SetNode<>(parent,s,joinType,variable);
            else if (attribute instanceof CollectionAttribute<?,?> c) copy=new CollectionNode<>(parent,c,joinType,variable);
            else copy=new JoinNode<>(parent,attribute,type,model,joinType,variable);
            ((JoinNode<Z,X>) copy).correlation=this;
            return (JoinNode<Z,X>) copy;
        }
    }
    static final class CollectionNode<Z,E> extends JoinNode<Z,E> implements CollectionJoin<Z,E> {
        final CollectionAttribute<? super Z,E> collection;
        @SuppressWarnings("unchecked") // Creation has checked the collection kind and target type.
        CollectionNode(FromNode<?,Z> from,CollectionAttribute<?,?> a,JoinType t,String v) {
            super(from,(Attribute<? super Z,?>) a,(Class<E>) a.getBindableJavaType(),(Bindable<E>) a,t,v);
            collection = (CollectionAttribute<? super Z,E>) a;
        }
        @Override public CollectionAttribute<? super Z,E> getModel() { return collection; }
        @Override public CollectionJoin<Z,E> on(Expression<Boolean> e) { super.on(e); return this; }
        @Override public CollectionJoin<Z,E> on(Predicate... e) { super.on(e); return this; }
    }
    static final class SetNode<Z,E> extends JoinNode<Z,E> implements SetJoin<Z,E> {
        final SetAttribute<? super Z,E> collection;
        @SuppressWarnings("unchecked") // Creation has checked the set kind and target type.
        SetNode(FromNode<?,Z> from,SetAttribute<?,?> a,JoinType t,String v) {
            super(from,(Attribute<? super Z,?>) a,(Class<E>) a.getBindableJavaType(),(Bindable<E>) a,t,v);
            collection = (SetAttribute<? super Z,E>) a;
        }
        @Override public SetAttribute<? super Z,E> getModel() { return collection; }
        @Override public SetJoin<Z,E> on(Expression<Boolean> e) { super.on(e); return this; }
        @Override public SetJoin<Z,E> on(Predicate... e) { super.on(e); return this; }
    }
    static final class ListNode<Z,E> extends JoinNode<Z,E> implements ListJoin<Z,E> {
        final ListAttribute<? super Z,E> collection;
        @SuppressWarnings("unchecked") // Creation has checked the list kind and target type.
        ListNode(FromNode<?,Z> from,ListAttribute<?,?> a,JoinType t,String v) {
            super(from,(Attribute<? super Z,?>) a,(Class<E>) a.getBindableJavaType(),(Bindable<E>) a,t,v);
            collection = (ListAttribute<? super Z,E>) a;
        }
        @Override public ListAttribute<? super Z,E> getModel() { return collection; }
        @Override public ListJoin<Z,E> on(Expression<Boolean> e) { super.on(e); return this; }
        @Override public ListJoin<Z,E> on(Predicate... e) { super.on(e); return this; }
        @Override public Expression<Integer> index() { return state.builder.function("INDEX",Integer.class,this); }
    }
    static final class MapNode<Z,K,V> extends JoinNode<Z,V> implements MapJoin<Z,K,V> {
        final MapAttribute<? super Z,K,V> collection;
        @SuppressWarnings("unchecked") // Creation has checked the map kind and key/value tokens.
        MapNode(FromNode<?,Z> from,MapAttribute<?,?,?> a,JoinType t,String v) {
            super(from,(Attribute<? super Z,?>) a,(Class<V>) a.getBindableJavaType(),(Bindable<V>) a,t,v);
            collection = (MapAttribute<? super Z,K,V>) a;
        }
        @Override public MapAttribute<? super Z,K,V> getModel() { return collection; }
        @Override public MapJoin<Z,K,V> on(Expression<Boolean> e) { super.on(e); return this; }
        @Override public MapJoin<Z,K,V> on(Predicate... e) { super.on(e); return this; }
        @Override public Path<K> key() {
            return new CriteriaExpressions.PathNode<>(collection.getKeyJavaType(),
                () -> new Ast.MapKeyPath(variable,List.of()),metamodel,null,this);
        }
        @Override public Path<V> value() { return this; }
        @SuppressWarnings("unchecked") // Map.Entry has no reified key/value class token.
        @Override public Expression<Map.Entry<K,V>> entry() {
            return state.builder.function("ENTRY",(Class<Map.Entry<K,V>>) (Class<?>) Map.Entry.class,this);
        }
    }
}
