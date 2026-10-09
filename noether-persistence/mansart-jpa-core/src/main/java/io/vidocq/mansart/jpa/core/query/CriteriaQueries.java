/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.query;

import io.vidocq.mansart.jpa.core.query.jpql.Ast;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.*;
import jakarta.persistence.metamodel.*;
import java.util.*;

final class CriteriaQueries {
    private CriteriaQueries() {}
    interface Statement { Ast.Statement statement(); }

    static final class State<T> {
        final CriteriaBuilderImpl builder;
        final Class<T> type;
        final State<?> containing;
        final Set<CriteriaFrom.RootNode<?>> roots = new LinkedHashSet<>();
        final List<Sub<?>> subqueries = new ArrayList<>();
        final List<CriteriaFrom.FromNode<?,?>> correlatedNodes = new ArrayList<>();
        Selection<? extends T> selection;
        Predicate restriction;
        Predicate having;
        List<Expression<?>> groups = List.of();
        List<Order> orders = List.of();
        boolean distinct;
        int nextVariable;
        State(CriteriaBuilderImpl builder,Class<T> type,State<?> containing) { this.builder=builder; this.type=type; this.containing=containing; }
        String variable() { return containing == null ? "c" + ++nextVariable : containing.variable(); }
        <X> Root<X> from(EntityType<X> entity) {
            var root = new CriteriaFrom.RootNode<>(entity,this,variable()); roots.add(root); return root;
        }
        Set<ParameterExpression<?>> parameters() {
            Set<ParameterExpression<?>> result = new LinkedHashSet<>();
            if (selection != null) CriteriaExpressions.node(selection).parameters(result);
            if (restriction != null) CriteriaExpressions.node(restriction).parameters(result);
            if (having != null) CriteriaExpressions.node(having).parameters(result);
            groups.forEach(e -> CriteriaExpressions.node(e).parameters(result));
            orders.forEach(e -> CriteriaExpressions.node(e.getExpression()).parameters(result));
            roots.forEach(r -> r.parameters(result));
            return result;
        }
        Ast.Select ast() {
            return ast(false);
        }
        Ast.Select ast(boolean existence) {
            Selection<?> selected = selection;
            if (selected == null && existence) selected = new CriteriaExpressions.Node<>(Integer.class, new Ast.Literal(1));
            if (selected == null) {
                if (roots.size() != 1 || !type.isAssignableFrom(roots.iterator().next().type))
                    throw new IllegalArgumentException("Query needs a selection");
                selected = roots.iterator().next();
            }
            List<Selection<?>> items = selected.isCompoundSelection() && (type == Tuple.class || type == Object[].class
                || type == Object.class) ? selected.getCompoundSelectionItems() : List.of(selected);
            List<Ast.Range> ranges=new ArrayList<>();
            roots.stream().filter(r -> !r.isCorrelated()).map(CriteriaFrom.RootNode::range).forEach(ranges::add);
            List<CriteriaFrom.FromNode<?,?>> correlated=new ArrayList<>(correlatedNodes);
            roots.stream().filter(CriteriaFrom.RootNode::isCorrelated).forEach(correlated::add);
            for (var root:correlated) {
                for (var join:root.joins) ranges.add(new Ast.Range(null,root.append(join.attribute.getName()),
                    join.variable,join.astJoins()));
            }
            return new Ast.Select(distinct,items.stream().map(s -> {
                Ast.Expr expression = CriteriaExpressions.node(s).ast();
                if (Collection.class.isAssignableFrom(s.getJavaType()) || Map.class.isAssignableFrom(s.getJavaType())) {
                    expression = new Ast.CollectionElements((Ast.Path) expression);
                }
                return new Ast.Item(expression,s.getAlias());
            }).toList(),
                ranges,
                restriction == null ? null : CriteriaExpressions.ast(restriction),
                groups.stream().map(CriteriaExpressions::ast).toList(), having == null ? null : CriteriaExpressions.ast(having),
                orders.stream().map(o -> ((CriteriaExpressions.Ordering) o).ast()).toList());
        }
    }
    static class Query<T> implements CriteriaQuery<T>, Statement {
        final State<T> state;
        Query(CriteriaBuilderImpl builder,Class<T> type) { state = new State<>(builder,type,null); }
        @Override public Ast.Statement statement() { return state.ast(); }
        @Override public <X> Root<X> from(Class<X> t) { return from(state.builder.metamodel.entity(t)); }
        @Override public <X> Root<X> from(EntityType<X> t) { return state.from(t); }
        @Override public CriteriaQuery<T> select(Selection<? extends T> s) { CriteriaExpressions.node(s); state.selection=s; return this; }
        @Override public CriteriaQuery<T> multiselect(Selection<?>... s) { return multiselect(Arrays.asList(s)); }
        @SuppressWarnings("unchecked") // §6.5.11: Object/array/tuple selects flatten; concrete result classes use constructor selection.
        @Override public CriteriaQuery<T> multiselect(List<Selection<?>> s) {
            Selection<?> selected = state.type == Object.class && s.size()==1 ? s.getFirst()
                : new CriteriaExpressions.Compound<>(state.type,s);
            return select((Selection<? extends T>) selected);
        }
        @Override public CriteriaQuery<T> where(Expression<Boolean> p) { state.restriction=p==null?null:state.builder.and(p); return this; }
        @Override public CriteriaQuery<T> where(Predicate... p) { state.restriction=p.length==0?null:state.builder.and(p); return this; }
        @Override public CriteriaQuery<T> where(List<Predicate> p) { return where(p.toArray(Predicate[]::new)); }
        @Override public CriteriaQuery<T> groupBy(Expression<?>... e) { return groupBy(Arrays.asList(e)); }
        @Override public CriteriaQuery<T> groupBy(List<Expression<?>> e) { state.groups=List.copyOf(e); return this; }
        @Override public CriteriaQuery<T> having(Expression<Boolean> p) { state.having=p==null?null:state.builder.and(p); return this; }
        @Override public CriteriaQuery<T> having(Predicate... p) { state.having=p.length==0?null:state.builder.and(p); return this; }
        @Override public CriteriaQuery<T> having(List<Predicate> p) { return having(p.toArray(Predicate[]::new)); }
        @Override public CriteriaQuery<T> orderBy(Order... e) { return orderBy(Arrays.asList(e)); }
        @Override public CriteriaQuery<T> orderBy(List<Order> e) { state.orders=List.copyOf(e); return this; }
        @Override public CriteriaQuery<T> distinct(boolean d) { state.distinct=d; return this; }
        @Override public List<Order> getOrderList() { return new ArrayList<>(state.orders); }
        @Override public Set<Root<?>> getRoots() { return new LinkedHashSet<>(state.roots); }
        @SuppressWarnings("unchecked") // Selection covariance is the API's explicit select(Selection<? extends T>) contract.
        @Override public Selection<T> getSelection() { return (Selection<T>) state.selection; }
        @Override public List<Expression<?>> getGroupList() { return new ArrayList<>(state.groups); }
        @Override public Predicate getGroupRestriction() { return state.having; }
        @Override public boolean isDistinct() { return state.distinct; }
        @Override public Class<T> getResultType() { return state.type; }
        @Override public Predicate getRestriction() { return state.restriction; }
        @Override public Set<ParameterExpression<?>> getParameters() { return state.parameters(); }
        @Override public <U> Subquery<U> subquery(Class<U> t) { var s=new Sub<>(state.builder,t,state,this); state.subqueries.add(s); return s; }
        @Override public <U> Subquery<U> subquery(EntityType<U> t) { return subquery(t.getJavaType()); }
    }
    static final class Sub<T> extends CriteriaExpressions.Node<T> implements Subquery<T> {
        final State<T> state;
        final CommonAbstractCriteria parent;
        final Set<Join<?,?>> correlatedJoins = new LinkedHashSet<>();
        Sub(CriteriaBuilderImpl builder,Class<T> type,State<?> containing,CommonAbstractCriteria parent) {
            super(type,new Ast.Literal(null));
            state=new State<>(builder,type,containing); this.parent=parent;
        }
        @Override Ast.Expr ast() { return new Ast.Subquery(state.ast()); }
        @Override void parameters(Set<ParameterExpression<?>> result) { result.addAll(state.parameters()); }
        @Override public <X> Root<X> from(Class<X> t) { return from(state.builder.metamodel.entity(t)); }
        @Override public <X> Root<X> from(EntityType<X> t) { return state.from(t); }
        @Override public Subquery<T> select(Expression<T> e) { state.selection=e; return this; }
        @Override public Subquery<T> where(Expression<Boolean> p) { state.restriction=p==null?null:state.builder.and(p); return this; }
        @Override public Subquery<T> where(Predicate... p) { state.restriction=p.length==0?null:state.builder.and(p); return this; }
        @Override public Subquery<T> where(List<Predicate> p) { return where(p.toArray(Predicate[]::new)); }
        @Override public Subquery<T> groupBy(Expression<?>... e) { return groupBy(Arrays.asList(e)); }
        @Override public Subquery<T> groupBy(List<Expression<?>> e) { state.groups=List.copyOf(e); return this; }
        @Override public Subquery<T> having(Expression<Boolean> p) { state.having=p==null?null:state.builder.and(p); return this; }
        @Override public Subquery<T> having(Predicate... p) { state.having=p.length==0?null:state.builder.and(p); return this; }
        @Override public Subquery<T> having(List<Predicate> p) { return having(p.toArray(Predicate[]::new)); }
        @Override public Subquery<T> distinct(boolean d) { state.distinct=d; return this; }
        @Override public Set<Root<?>> getRoots() { return new LinkedHashSet<>(state.roots); }
        @SuppressWarnings("unchecked") // Subquery select accepts precisely Expression<T>.
        @Override public Expression<T> getSelection() { return (Expression<T>) state.selection; }
        @Override public List<Expression<?>> getGroupList() { return new ArrayList<>(state.groups); }
        @Override public Predicate getGroupRestriction() { return state.having; }
        @Override public boolean isDistinct() { return state.distinct; }
        @Override public Class<T> getResultType() { return state.type; }
        @Override public Predicate getRestriction() { return state.restriction; }
        @Override public Set<ParameterExpression<?>> getParameters() { return state.parameters(); }
        @Override public <U> Subquery<U> subquery(Class<U> t) { var s=new Sub<>(state.builder,t,state,this); state.subqueries.add(s); return s; }
        @Override public <U> Subquery<U> subquery(EntityType<U> t) { return subquery(t.getJavaType()); }
        @Override public jakarta.persistence.criteria.AbstractQuery<?> getParent() {
            if (!(parent instanceof jakarta.persistence.criteria.AbstractQuery<?> q)) throw new IllegalStateException("Containing query is a bulk query");
            return q;
        }
        @Override public CommonAbstractCriteria getContainingQuery() { return parent; }
        @Override public Set<Join<?,?>> getCorrelatedJoins() { return new LinkedHashSet<>(correlatedJoins); }
        @Override public <Y> Root<Y> correlate(Root<Y> original) {
            var node=(CriteriaFrom.RootNode<Y>) original;
            var result=new CriteriaFrom.RootNode<>(original.getModel(),state,node.variable);
            result.correlation=original; state.roots.add(result); return result;
        }
        @SuppressWarnings("unchecked") // Correlation duplicates the original join kind, model and generic bounds.
        private <J extends Join<?,?>> J correlateJoin(J original) {
            var node=((CriteriaFrom.JoinNode<?,?>) original).correlatedCopy(state);
            state.correlatedNodes.add(node);
            correlatedJoins.add(node);
            return (J) node;
        }
        @Override public <X,Y> Join<X,Y> correlate(Join<X,Y> j) { return correlateJoin(j); }
        @Override public <X,Y> CollectionJoin<X,Y> correlate(CollectionJoin<X,Y> j) { return correlateJoin(j); }
        @Override public <X,Y> SetJoin<X,Y> correlate(SetJoin<X,Y> j) { return correlateJoin(j); }
        @Override public <X,Y> ListJoin<X,Y> correlate(ListJoin<X,Y> j) { return correlateJoin(j); }
        @Override public <X,K,V> MapJoin<X,K,V> correlate(MapJoin<X,K,V> j) { return correlateJoin(j); }
    }
    abstract static class Bulk<T> implements CommonAbstractCriteria, Statement {
        final State<T> state;
        CriteriaFrom.RootNode<T> root;
        Bulk(CriteriaBuilderImpl builder,Class<T> type) { state=new State<>(builder,type,null); }
        public Root<T> from(Class<T> t) { return from(state.builder.metamodel.entity(t)); }
        public Root<T> from(EntityType<T> t) {
            if (root != null) throw new IllegalStateException("Bulk query already has a root");
            root=(CriteriaFrom.RootNode<T>) state.from(t); return root;
        }
        public Root<T> getRoot() { return root; }
        @Override public Predicate getRestriction() { return state.restriction; }
        @Override public Set<ParameterExpression<?>> getParameters() { return state.parameters(); }
        @Override public <U> Subquery<U> subquery(Class<U> t) { return new Sub<>(state.builder,t,state,this); }
        @Override public <U> Subquery<U> subquery(EntityType<U> t) { return subquery(t.getJavaType()); }
        void requireRoot() { if (root==null) throw new IllegalArgumentException("Bulk query needs a root"); }
    }
    static final class Update<T> extends Bulk<T> implements CriteriaUpdate<T> {
        final Map<Path<?>,Object> assignments = new LinkedHashMap<>();
        Update(CriteriaBuilderImpl builder,Class<T> type) { super(builder,type); }
        @Override public <Y,X extends Y> CriteriaUpdate<T> set(SingularAttribute<? super T,Y> a,X value) { return set(root.get(a),value); }
        @Override public <Y> CriteriaUpdate<T> set(SingularAttribute<? super T,Y> a,Expression<? extends Y> value) { return set(root.get(a),value); }
        @Override public <Y,X extends Y> CriteriaUpdate<T> set(Path<Y> a,X value) { assignments.put(a,value); return this; }
        @Override public <Y> CriteriaUpdate<T> set(Path<Y> a,Expression<? extends Y> value) { assignments.put(a,value); return this; }
        @Override public CriteriaUpdate<T> set(String a,Object value) { assignments.put(root.get(a),value); return this; }
        @Override public CriteriaUpdate<T> where(Expression<Boolean> p) { state.restriction=p==null?null:state.builder.and(p); return this; }
        @Override public CriteriaUpdate<T> where(Predicate... p) { state.restriction=p.length==0?null:state.builder.and(p); return this; }
        @Override public Set<ParameterExpression<?>> getParameters() {
            var result=super.getParameters();
            assignments.values().forEach(v -> { if (v instanceof CriteriaExpressions.Node<?> n) n.parameters(result); });
            return result;
        }
        @Override public Ast.Statement statement() {
            requireRoot();
            return new Ast.Update(root.entity.getName(),root.variable,assignments.entrySet().stream()
                .map(e -> new Ast.Assignment((Ast.Path) CriteriaExpressions.ast(e.getKey()),CriteriaExpressions.ast(e.getValue()))).toList(),
                state.restriction==null?null:CriteriaExpressions.ast(state.restriction));
        }
    }
    static final class Delete<T> extends Bulk<T> implements CriteriaDelete<T> {
        Delete(CriteriaBuilderImpl builder,Class<T> type) { super(builder,type); }
        @Override public CriteriaDelete<T> where(Expression<Boolean> p) { state.restriction=p==null?null:state.builder.and(p); return this; }
        @Override public CriteriaDelete<T> where(Predicate... p) { state.restriction=p.length==0?null:state.builder.and(p); return this; }
        @Override public Ast.Statement statement() {
            requireRoot();
            return new Ast.Delete(root.entity.getName(),root.variable,state.restriction==null?null:CriteriaExpressions.ast(state.restriction));
        }
    }
}
