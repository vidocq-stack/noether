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

/** Criteria nodes produce the same immutable syntax tree as the JPQL parser (§6.3). */
final class CriteriaExpressions {
    private CriteriaExpressions() {}

    static Ast.Expr ast(Object value) {
        if (value == null) return new Ast.Literal(null);
        if (value instanceof Node<?> node) return node.ast();
        if (value instanceof Expression<?>) throw new IllegalArgumentException("Criteria expression belongs to another provider");
        return new Ast.Literal(value);
    }
    static Node<?> node(Selection<?> value) {
        if (!(value instanceof Node<?> n)) throw new IllegalArgumentException("Selection belongs to another provider");
        return n;
    }

    static class Node<T> implements Expression<T> {
        final Class<T> type;
        final Supplier<Ast.Expr> expression;
        final List<Node<?>> children;
        String alias;
        Node(Class<T> type, Supplier<Ast.Expr> expression, List<Node<?>> children) {
            this.type = type; this.expression = expression; this.children = children;
        }
        Node(Class<T> type, Ast.Expr expression) { this(type, () -> expression, List.of()); }
        Ast.Expr ast() { return expression.get(); }
        void parameters(Set<ParameterExpression<?>> result) {
            if (this instanceof ParameterExpression<?> p) result.add(p);
            children.forEach(c -> c.parameters(result));
        }
        @Override public Class<? extends T> getJavaType() { return type; }
        @Override public String getAlias() { return alias; }
        @Override public Selection<T> alias(String name) { alias = name; return this; }
        @Override public boolean isCompoundSelection() { return false; }
        @Override public List<Selection<?>> getCompoundSelectionItems() { throw new IllegalStateException("Not a compound selection"); }
        @Override public Predicate isNull() { return new Pred(() -> new Ast.IsNull(ast(), false), List.of(this)); }
        @Override public Predicate isNotNull() { return new Pred(() -> new Ast.IsNull(ast(), true), List.of(this)); }
        @Override public Predicate equalTo(Expression<?> other) { return equalTo((Object) other); }
        @Override public Predicate equalTo(Object other) { return compare(Ast.Op.EQ, this, other); }
        @Override public Predicate notEqualTo(Expression<?> other) { return notEqualTo((Object) other); }
        @Override public Predicate notEqualTo(Object other) { return compare(Ast.Op.NE, this, other); }
        @Override public Predicate in(Object... values) { return in(Arrays.asList(values)); }
        @Override public Predicate in(Expression<?>... values) { return in(Arrays.asList(values)); }
        @Override public Predicate in(Collection<?> values) {
            List<?> copy = List.copyOf(values);
            var nodes = new ArrayList<Node<?>>(); nodes.add(this);
            copy.forEach(v -> { if (v instanceof Node<?> n) nodes.add(n); });
            return new Pred(() -> new Ast.In(ast(), copy.stream().map(CriteriaExpressions::ast).toList(), false), nodes);
        }
        @Override public Predicate in(Expression<Collection<?>> values) { return in(new Object[]{values}); }
        @Override public <X> Expression<X> as(Class<X> result) { return new Node<>(result, this::ast, List.of(this)); }
        @Override public <X> Expression<X> cast(Class<X> result) {
            Ast.CastType cast = Ast.CastType.valueOf(result.getSimpleName().toUpperCase(Locale.ROOT));
            return new Node<>(result, () -> new Ast.Cast(ast(), cast), List.of(this));
        }
    }
    static Pred compare(Ast.Op op, Object left, Object right) {
        return new Pred(() -> new Ast.Binary(ast(left), op, ast(right)), children(left, right));
    }
    static List<Node<?>> children(Object... values) {
        return Arrays.stream(values).filter(Node.class::isInstance).<Node<?>>map(v -> (Node<?>) v).toList();
    }

    static class Pred extends Node<Boolean> implements Predicate {
        final BooleanOperator operator;
        final List<Expression<Boolean>> operands;
        final boolean negated;
        Pred(Supplier<Ast.Expr> expression, List<Node<?>> children) {
            this(expression, children, BooleanOperator.AND, List.of(), false);
        }
        Pred(Supplier<Ast.Expr> expression, List<Node<?>> children, BooleanOperator operator,
                List<Expression<Boolean>> operands, boolean negated) {
            super(Boolean.class, expression, children);
            this.operator = operator; this.operands = operands; this.negated = negated;
        }
        @Override public BooleanOperator getOperator() { return operator; }
        @Override public boolean isNegated() { return negated; }
        @Override public List<Expression<Boolean>> getExpressions() { return new ArrayList<>(operands); }
        @Override public Predicate not() {
            return new Pred(() -> new Ast.Not(ast()), List.of(this), operator, operands, !negated);
        }
    }
    static final class Param<T> extends Node<T> implements ParameterExpression<T> {
        final String name;
        final int position;
        Param(Class<T> type, String name, int position) {
            super(type, new Ast.Parameter(name, position));
            this.name = name; this.position = position;
        }
        @Override public String getName() { return name; }
        @Override public Integer getPosition() { return null; }
        @Override public Class<T> getParameterType() { return type; }
    }
    static final class Compound<T> extends Node<T> implements CompoundSelection<T> {
        final List<Selection<?>> items;
        Compound(Class<T> type, List<Selection<?>> selections) {
            super(type, () -> new Ast.Constructor(type.getName(), selections.stream().map(s -> node(s).ast()).toList()),
                selections.stream().map(CriteriaExpressions::node).toList());
            items = List.copyOf(selections);
            Set<String> aliases = new HashSet<>();
            for (Selection<?> s : items) {
                if (s.isCompoundSelection() && (s.getJavaType().isArray() || s.getJavaType() == jakarta.persistence.Tuple.class))
                    throw new IllegalArgumentException("Nested tuple/array selection");
                if (s.getAlias() != null && !aliases.add(s.getAlias())) throw new IllegalArgumentException("Duplicate selection alias");
            }
        }
        @Override public boolean isCompoundSelection() { return true; }
        @Override public List<Selection<?>> getCompoundSelectionItems() { return new ArrayList<>(items); }
    }
    static final class Ordering implements Order {
        final Expression<?> expression;
        final boolean ascending;
        final Nulls nulls;
        Ordering(Expression<?> expression, boolean ascending, Nulls nulls) {
            this.expression = expression; this.ascending = ascending; this.nulls = nulls;
        }
        @Override public Order reverse() { return new Ordering(expression, !ascending, nulls == Nulls.FIRST ? Nulls.LAST : nulls == Nulls.LAST ? Nulls.FIRST : nulls); }
        @Override public boolean isAscending() { return ascending; }
        @Override public Expression<?> getExpression() { return expression; }
        @Override public Nulls getNullPrecedence() { return nulls; }
        Ast.OrderItem ast() { return new Ast.OrderItem(CriteriaExpressions.ast(expression), !ascending,
            nulls == Nulls.NONE ? null : nulls == Nulls.FIRST); }
    }

    static class PathNode<X> extends Node<X> implements Path<X> {
        final Metamodel metamodel;
        final Bindable<X> model;
        final Path<?> parent;
        Class<?> treatType;
        PathNode(Class<X> type, Supplier<Ast.Expr> expression, Metamodel metamodel, Bindable<X> model, Path<?> parent) {
            super(type, expression, List.of());
            this.metamodel = metamodel; this.model = model; this.parent = parent;
        }
        @Override public Bindable<X> getModel() { return model; }
        @Override public Path<?> getParentPath() { return parent; }
        PathNode<X> copy() { return new PathNode<>(type,this::ast,metamodel,model,parent); }
        @Override Ast.Expr ast() {
            Ast.Expr original = super.ast();
            if (treatType == null || !(original instanceof Ast.Path p)) return original;
            return new Ast.Path(p.segments(), metamodel.entity(treatType).getName(), p.segments().size());
        }
        @SuppressWarnings("unchecked") // TREAT has verified the exact subtype represented by this copied path.
        @Override public Class<? extends X> getJavaType() { return treatType == null ? type : (Class<? extends X>) treatType; }
        Attribute<? super X, ?> attribute(String name) {
            ManagedType<X> managed;
            try { managed = metamodel.managedType(navigationType()); }
            catch (IllegalArgumentException e) { throw new IllegalStateException("Cannot dereference a basic path", e); }
            return managed.getAttribute(name);
        }
        Ast.Expr append(String name) {
            Ast.Expr expression=ast();
            if (expression instanceof Ast.MapKeyPath key) {
                var attributes=new ArrayList<>(key.attributes()); attributes.add(name);
                return new Ast.MapKeyPath(key.variable(),attributes);
            }
            if (!(expression instanceof Ast.Path p)) throw new IllegalStateException("Not a navigable path");
            var segments = new ArrayList<>(p.segments()); segments.add(name);
            return new Ast.Path(segments, p.treatEntity(), p.treatAt());
        }
        @SuppressWarnings("unchecked") // Attribute type and owner are validated using the managed model before construction.
        private <Y> PathNode<Y> path(String name) {
            Attribute<?, ?> a = attribute(name);
            return new PathNode<>((Class<Y>) a.getJavaType(), () -> append(name), metamodel, (Bindable<Y>) a, this);
        }
        @Override public <Y> Path<Y> get(String name) { return path(name); }
        @Override public <Y> Path<Y> get(SingularAttribute<? super X,Y> a) { validate(a); return path(a.getName()); }
        void validate(Attribute<?, ?> a) {
            if (!a.getDeclaringType().getJavaType().isAssignableFrom(getJavaType())) throw new IllegalArgumentException("Attribute of unrelated type");
        }
        @SuppressWarnings("unchecked") // A treated path narrows its navigable managed type without mutating the original.
        private Class<X> navigationType() { return (Class<X>) getJavaType(); }
        @SuppressWarnings("unchecked") // A plural attribute's exact Java type is retained by the collection expression.
        @Override public <E,C extends Collection<E>> Expression<C> get(PluralAttribute<? super X,C,E> a) {
            validate(a); attribute(a.getName());
            return new Node<>((Class<C>) a.getJavaType(), () -> append(a.getName()), List.of(this));
        }
        @SuppressWarnings("unchecked") // A map attribute represents exactly the map-typed expression requested by this API.
        @Override public <K,V,M extends Map<K,V>> Expression<M> get(MapAttribute<? super X,K,V> a) {
            validate(a); attribute(a.getName());
            return new Node<>((Class<M>) a.getJavaType(), () -> append(a.getName()), List.of(this));
        }
        @SuppressWarnings("unchecked") // TYPE returns the Class of an instance of this path's Java type.
        @Override public Expression<Class<? extends X>> type() {
            return new Node<>((Class<Class<? extends X>>) (Class<?>) Class.class,
                () -> new Ast.Function("TYPE", List.of(ast())), List.of(this));
        }
    }
}
