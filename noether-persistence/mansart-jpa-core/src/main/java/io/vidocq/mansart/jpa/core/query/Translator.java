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
package io.vidocq.mansart.jpa.core.query;

import io.vidocq.mansart.jpa.core.jdbc.type.ValueBinder;
import io.vidocq.mansart.jpa.core.mapping.CollectionMapping;
import io.vidocq.mansart.jpa.core.mapping.EntityStatements;
import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import io.vidocq.mansart.jpa.core.mapping.MappedUnit;
import io.vidocq.mansart.jpa.core.model.AssociationAttribute;
import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.BasicAttribute;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.model.EntityModel;
import io.vidocq.mansart.jpa.core.query.jpql.Ast;
import io.vidocq.mansart.jpa.core.session.NotYet;
import io.vidocq.mansart.jpa.dialect.sql.Expression;
import io.vidocq.mansart.jpa.dialect.sql.Expression.Binary;
import io.vidocq.mansart.jpa.dialect.sql.Expression.Operator;
import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import io.vidocq.mansart.jpa.dialect.sql.Query;
import io.vidocq.mansart.jpa.dialect.sql.Table;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Translates a select statement (§4) to a SQL {@link Query} against the entity model: identification variables become
 * table aliases, paths columns, single-valued path navigation inner joins (§4.4.4), declared joins joins (§4.4.5), and
 * entities their key columns — compared key part by key part, read back as managed instances. One translator per
 * translation: it holds the aliases and joins it made.
 */
final class Translator {

    /** An identification variable: its entity and the alias of its table; {@code root} the FROM item it joins to. */
    private record Variable(MappedEntity type, String alias, int root) {
    }

    /** What an expression denotes: a SQL value, or an entity by its key columns. */
    private sealed interface Value permits Scalar, Entity {
    }

    /** A SQL value; {@code binder} binds and reads it when it is an attribute, {@code type} its Java type if known. */
    private record Scalar(Expression sql, ValueBinder binder, Class<?> type) implements Value {
    }

    /** An entity of {@code type}: the SQL of its key columns, in the order of its key, and their binders. */
    private record Entity(MappedEntity type, List<Expression> key, List<ValueBinder> binders) implements Value {
    }

    private final MappedUnit unit;
    private final Function<Ast.Parameter, Object> bindings;
    private final Map<String, Variable> variables = new HashMap<>();
    private final Map<String, Value> resultVariables = new HashMap<>();
    private final Map<String, Variable> navigations = new HashMap<>();
    private final List<Table> roots = new ArrayList<>();
    private final List<String> rootAliases = new ArrayList<>();
    private final List<List<Query.Join>> joins = new ArrayList<>();
    private boolean selecting;
    private int aliases;

    /** @param bindings the values bound to the parameters, to expand those holding collections; {@code null} to validate */
    private Translator(MappedUnit unit, Function<Ast.Parameter, Object> bindings) {
        this.unit = unit;
        this.bindings = bindings;
    }

    /**
     * Translates {@code select}. Without {@code bindings} (at {@code createQuery}) a collection-valued parameter counts
     * one element: the translation checks the query; with them, it is the one executed.
     */
    static Compiled translate(Ast.Select select, MappedUnit unit, Function<Ast.Parameter, Object> bindings, Integer offset,
            Integer limit) {
        return new Translator(unit, bindings).select(select, offset, limit);
    }

    // ---- statements ---------------------------------------------------------------------------------------

    private Compiled select(Ast.Select select, Integer offset, Integer limit) {
        for (Ast.Range range : select.from()) {
            declare(range);
        }
        List<Expression> sqlItems = new ArrayList<>();
        List<Compiled.Item> items = new ArrayList<>();
        Class<?> resultType = null;
        for (Ast.Item item : select.items()) {
            selecting = true; // §4.4.4: a selected single-valued path is navigated, with inner join semantics
            Value value = value(item.expression());
            selecting = false;
            switch (value) {
                case Entity entity -> {
                    sqlItems.addAll(entity.key());
                    items.add(new Compiled.EntityItem(entity.type(), entity.binders()));
                    resultType = entity.type().model().javaType();
                }
                case Scalar scalar -> {
                    sqlItems.add(scalar.sql());
                    items.add(new Compiled.ValueItem(scalar.binder(), scalar.type()));
                    resultType = scalar.type();
                }
            }
            if (item.alias() != null) {
                resultVariables.put(item.alias().toLowerCase(Locale.ROOT), value);
            }
        }
        if (items.size() > 1) {
            resultType = Object[].class;
        }
        Expression where = select.where() == null ? null : condition(select.where());
        List<Expression> groupBy = new ArrayList<>();
        for (Ast.Expr key : select.groupBy()) {
            groupBy.addAll(sql(value(key)));
        }
        Expression having = select.having() == null ? null : condition(select.having());
        List<Query.Order> orderBy = new ArrayList<>();
        for (Ast.OrderItem order : select.orderBy()) {
            for (Expression key : sql(value(order.expression()))) {
                orderBy.add(new Query.Order(key, order.descending()));
            }
        }
        List<Query.From> from = new ArrayList<>();
        for (int r = 0; r < roots.size(); r++) {
            from.add(new Query.From(roots.get(r), rootAliases.get(r), joins.get(r)));
        }
        Query sql = new Query(select.distinct(), sqlItems, from, where, groupBy, having, orderBy, offset, limit);
        // the slots in the order of the SQL parameters
        List<Compiled.Slot> ordered = new ArrayList<>();
        for (Expression.Parameter parameter : sql.parameters()) {
            ordered.add((Compiled.Slot) parameter.slot());
        }
        return new Compiled(sql, List.copyOf(items), resultType == null ? null : boxed(resultType), ordered);
    }

    /** The SQL expressions of a value: itself, or the key columns of an entity. */
    private static List<Expression> sql(Value value) {
        return switch (value) {
            case Scalar scalar -> List.of(scalar.sql());
            case Entity entity -> entity.key();
        };
    }

    // ---- FROM (§4.4) --------------------------------------------------------------------------------------

    private void declare(Ast.Range range) {
        Variable variable;
        if (range.entity() != null) {
            MappedEntity type = entityNamed(range.entity());
            int root = roots.size();
            String alias = alias();
            roots.add(table(type));
            rootAliases.add(alias);
            joins.add(new ArrayList<>());
            variable = new Variable(type, alias, root);
        } else { // IN(path) var: the members of a collection, an inner join (§4.4.6)
            variable = join(range.collection(), Ast.Join.Kind.INNER);
        }
        define(range.variable(), variable);
        for (Ast.Join join : range.joins()) {
            Variable joined = join(join.path(), join.kind());
            if (join.variable() != null) {
                define(join.variable(), joined);
            }
            if (join.on() != null) {
                List<Query.Join> list = joins.get(joined.root());
                Query.Join last = list.getLast();
                list.set(list.size() - 1, new Query.Join(last.kind(), last.table(), last.alias(),
                    new Binary(last.on(), Operator.AND, condition(join.on()))));
            }
        }
    }

    private void define(String name, Variable variable) {
        if (variables.putIfAbsent(name.toLowerCase(Locale.ROOT), variable) != null) {
            throw new IllegalArgumentException("The identification variable " + name + " is declared twice (§4.4.2)");
        }
    }

    private MappedEntity entityNamed(String name) {
        EntityModel model = unit.model().entity(name).orElseThrow(() -> new IllegalArgumentException(
            "The query names " + name + ", which is not an entity of the persistence unit (§4.3)"));
        return unit.entity(model.javaType()).orElseThrow();
    }

    /** A declared join to the entity at the end of {@code path}: a single-valued or a collection-valued relationship. */
    private Variable join(Ast.Path path, Ast.Join.Kind kind) {
        Variable from = variable(path.segments().getFirst());
        List<String> segments = path.segments();
        for (int s = 1; s < segments.size() - 1; s++) {
            from = navigate(from, attribute(from.type(), segments.get(s)), Query.Join.Kind.INNER);
        }
        if (segments.size() < 2) {
            throw new IllegalArgumentException("A join names a relationship, not " + String.join(".", segments));
        }
        int attribute = attribute(from.type(), segments.getLast());
        Query.Join.Kind sqlKind = kind == Ast.Join.Kind.LEFT ? Query.Join.Kind.LEFT : Query.Join.Kind.INNER;
        AttributeModel model = from.type().model().attributes().get(attribute);
        if (model instanceof AssociationAttribute association && association.singleValued()) {
            return relationshipJoin(from, attribute, association, sqlKind);
        }
        if (model instanceof AssociationAttribute association) {
            return collectionJoin(from, attribute, association, sqlKind);
        }
        if (model instanceof io.vidocq.mansart.jpa.core.model.ElementCollectionAttribute) {
            throw NotYet.milestone("P7", "joins over element collections");
        }
        throw new IllegalArgumentException("A join names a relationship, not the attribute " + segments.getLast() + " of "
            + from.type().model().entityName() + " (§4.4.5)");
    }

    /** The variable a single-valued path navigates to: one inner join per path (§4.4.4), shared by its uses. */
    private Variable navigate(Variable from, int attribute, Query.Join.Kind kind) {
        AttributeModel model = from.type().model().attributes().get(attribute);
        if (!(model instanceof AssociationAttribute association) || !association.singleValued()) {
            throw new IllegalArgumentException("The path goes through " + model.name() + " of " + from.type().model().entityName()
                + ", which is not a single-valued relationship (§4.4.4)");
        }
        String key = from.alias() + "." + attribute + "." + kind;
        Variable known = navigations.get(key);
        if (known == null) {
            known = relationshipJoin(from, attribute, association, kind);
            navigations.put(key, known);
        }
        return known;
    }

    private Variable relationshipJoin(Variable from, int attribute, AssociationAttribute association, Query.Join.Kind kind) {
        MappedEntity target = entity(association.targetEntity());
        String alias = alias();
        Expression on;
        var owned = from.type().statements().reference(attribute);
        if (owned.isPresent()) { // from.fk = target.key
            on = equal(columns(from.alias(), from.type().statements(), owned.get().columns()), key(alias, target));
        } else if (!association.owning()) { // the owner's foreign key holds this key: target.fk = from.key
            int mappedBy = attribute(target, association.mappedBy());
            var reference = target.statements().reference(mappedBy).orElseThrow(() -> NotYet.milestone("P7",
                "a join through an inverse side that its owner does not map by a foreign key"));
            on = equal(columns(alias, target.statements(), reference.columns()), key(from.alias(), from.type()));
        } else {
            throw NotYet.milestone("P7", "joins over a relationship through a join table");
        }
        joins.get(from.root()).add(new Query.Join(kind, table(target), alias, on));
        return new Variable(target, alias, from.root());
    }

    private Variable collectionJoin(Variable from, int attribute, AssociationAttribute association, Query.Join.Kind kind) {
        MappedEntity target = entity(association.targetEntity());
        CollectionMapping mapping = from.type().statements().collection(attribute).orElseThrow();
        String alias = alias();
        List<Query.Join> list = joins.get(from.root());
        switch (mapping) {
            case CollectionMapping.JoinTable table -> {
                String link = alias();
                list.add(new Query.Join(kind, table.table(), link, equal(names(link, table.ownerColumns()), key(from.alias(), from.type()))));
                list.add(new Query.Join(kind, table(target), alias, equal(key(alias, target), names(link, table.targetColumns()))));
            }
            case CollectionMapping.MappedBy _ -> {
                int mappedBy = attribute(target, association.mappedBy());
                var reference = target.statements().reference(mappedBy);
                var owner = target.statements().collection(mappedBy);
                if (reference.isPresent()) {
                    list.add(new Query.Join(kind, table(target), alias, equal(columns(alias, target.statements(),
                        reference.get().columns()), key(from.alias(), from.type()))));
                } else if (owner.isPresent() && owner.get() instanceof CollectionMapping.JoinTable table) {
                    String link = alias();
                    list.add(new Query.Join(kind, table.table(), link, equal(names(link, table.targetColumns()),
                        key(from.alias(), from.type()))));
                    list.add(new Query.Join(kind, table(target), alias, equal(key(alias, target), names(link, table.ownerColumns()))));
                } else {
                    throw NotYet.milestone("P7", "a join over an inverse side mapped by a shape not mapped yet");
                }
            }
            case CollectionMapping.Unsupported unsupported -> throw NotYet.milestone("P5", unsupported.feature());
        }
        return new Variable(target, alias, from.root());
    }

    // ---- paths (§4.4.4) -----------------------------------------------------------------------------------

    private Variable variable(String name) {
        Variable variable = variables.get(name.toLowerCase(Locale.ROOT));
        if (variable == null) {
            throw new IllegalArgumentException("The identification variable " + name + " is not declared (§4.4.2)");
        }
        return variable;
    }

    /** What a path denotes: an entity, an attribute's column, or the foreign key of a single-valued relationship. */
    private Value path(Ast.Path path) {
        List<String> segments = path.segments();
        String head = segments.getFirst().toLowerCase(Locale.ROOT);
        if (segments.size() == 1 && !variables.containsKey(head) && resultVariables.containsKey(head)) {
            return resultVariables.get(head);
        }
        Variable variable = variable(segments.getFirst());
        if (segments.size() == 1) {
            return entityValue(variable.alias(), variable.type());
        }
        for (int s = 1; s < segments.size() - 1; s++) {
            int attribute = attribute(variable.type(), segments.get(s));
            if (variable.type().model().attributes().get(attribute) instanceof EmbeddedAttribute) {
                return embeddedPath(variable, attribute, segments.subList(s + 1, segments.size()));
            }
            variable = navigate(variable, attribute, Query.Join.Kind.INNER);
        }
        int attribute = attribute(variable.type(), segments.getLast());
        AttributeModel model = variable.type().model().attributes().get(attribute);
        return switch (model) {
            case BasicAttribute basic -> column(variable, attribute, new int[0], basic.javaType());
            case AssociationAttribute association when association.singleValued() -> {
                var owned = variable.type().statements().reference(attribute);
                if (owned.isPresent() && !selecting) { // compared and tested by its foreign key, without a join
                    EntityStatements statements = variable.type().statements();
                    yield new Entity(entity(association.targetEntity()), columns(variable.alias(), statements, owned.get().columns()),
                        binders(statements, owned.get().columns()));
                }
                Variable target = navigate(variable, attribute, selecting ? Query.Join.Kind.INNER : Query.Join.Kind.LEFT);
                yield entityValue(target.alias(), target.type());
            }
            case EmbeddedAttribute _ -> throw NotYet.milestone("P7", "embeddables compared as a whole");
            default -> throw new IllegalArgumentException("The path " + String.join(".", segments) + " ends at a collection; it is "
                + "joined, or used with IS EMPTY, MEMBER OF or SIZE (§4.4.4)");
        };
    }

    /** A path that ends in an embeddable: {@code e.address.city}. */
    private Value embeddedPath(Variable variable, int attribute, List<String> rest) {
        EmbeddedAttribute embedded = (EmbeddedAttribute) variable.type().model().attributes().get(attribute);
        List<AttributeModel> components = embedded.embeddable().attributes();
        int[] path = new int[rest.size()];
        AttributeModel model = embedded;
        for (int p = 0; p < rest.size(); p++) {
            int index = -1;
            for (int c = 0; c < components.size(); c++) {
                if (components.get(c).name().equals(rest.get(p))) {
                    index = c;
                }
            }
            if (index < 0) {
                throw new IllegalArgumentException(embedded.embeddable().javaType().getName() + " has no attribute " + rest.get(p));
            }
            path[p] = index;
            model = components.get(index);
            if (model instanceof EmbeddedAttribute nested) {
                components = nested.embeddable().attributes();
            } else if (p < rest.size() - 1) {
                throw new IllegalArgumentException("The path goes through " + rest.get(p) + ", which is not an embeddable");
            }
        }
        if (!(model instanceof BasicAttribute basic)) {
            throw NotYet.milestone("P7", "embeddables compared as a whole");
        }
        return column(variable, attribute, path, basic.javaType());
    }

    /** The column of attribute {@code attribute} (at {@code path} in its embeddable) of {@code variable}. */
    private Scalar column(Variable variable, int attribute, int[] path, Class<?> type) {
        List<EntityStatements.Column> columns = variable.type().statements().columns();
        for (EntityStatements.Column column : columns) {
            if (column.attribute() == attribute && column.foreignKey() == null && java.util.Arrays.equals(column.path(), path)) {
                if (column.table() != 0) {
                    throw NotYet.milestone("P7", "a column of a secondary table in a query");
                }
                return new Scalar(new Expression.Column(variable.alias(), column.name()), column.binder(), type);
            }
        }
        throw new IllegalArgumentException("The attribute " + variable.type().model().attributes().get(attribute).name() + " of "
            + variable.type().model().entityName() + " has no column");
    }

    private int attribute(MappedEntity type, String name) {
        List<AttributeModel> attributes = type.model().attributes();
        for (int i = 0; i < attributes.size(); i++) {
            if (attributes.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException(type.model().entityName() + " has no persistent attribute " + name);
    }

    // ---- expressions (§4.6) -------------------------------------------------------------------------------

    private Value value(Ast.Expr expr) {
        return switch (expr) {
            case Ast.Path path -> path(path);
            case Ast.Literal literal -> new Scalar(new Expression.Literal(literal.value()), null,
                literal.value() == null ? null : literal.value().getClass());
            case Ast.Parameter parameter -> new Scalar(parameter(parameter, null, null, -1, null).getFirst(), null, null);
            case Ast.Binary binary when arithmetic(binary.op()) -> {
                Value left = value(binary.left());
                Value right = binary.right() instanceof Ast.Parameter parameter ? parameterLike(parameter, left) : value(binary.right());
                yield new Scalar(new Binary(scalar(left).sql(), operator(binary.op()), scalar(right).sql()), null,
                    binary.op() == Ast.Op.CONCAT ? String.class : null);
            }
            case Ast.Negate negate -> new Scalar(new Binary(new Expression.Literal(0), Operator.MINUS, scalar(value(negate.operand())).sql()),
                null, null);
            case Ast.Aggregate aggregate -> aggregate(aggregate);
            default -> new Scalar(condition(expr), null, Boolean.class);
        };
    }

    private static boolean arithmetic(Ast.Op op) {
        return op == Ast.Op.PLUS || op == Ast.Op.MINUS || op == Ast.Op.TIMES || op == Ast.Op.DIVIDE || op == Ast.Op.CONCAT;
    }

    private static Scalar scalar(Value value) {
        if (value instanceof Scalar scalar) {
            return scalar;
        }
        throw new IllegalArgumentException("An entity is used where a value is expected");
    }

    /** §4.8.5: COUNT is a Long, AVG a Double, SUM a Long, a Double or the big number of its argument, MIN and MAX theirs. */
    private Scalar aggregate(Ast.Aggregate aggregate) {
        if (aggregate.argument() == null) {
            return new Scalar(new Expression.Aggregate("COUNT", false, null), null, Long.class);
        }
        Value argument = value(aggregate.argument());
        Expression sql = switch (argument) {
            case Scalar scalar -> scalar.sql();
            case Entity entity -> {
                if (entity.key().size() > 1 && (aggregate.distinct() || !aggregate.function().equals("COUNT"))) {
                    throw NotYet.milestone("P7", aggregate.function() + " of an entity with a composite key");
                }
                yield entity.key().getFirst();
            }
        };
        Expression.Aggregate sqlAggregate = new Expression.Aggregate(aggregate.function(), aggregate.distinct(), sql);
        return switch (aggregate.function()) {
            case "COUNT" -> new Scalar(sqlAggregate, null, Long.class);
            case "AVG" -> new Scalar(sqlAggregate, null, Double.class);
            case "SUM" -> new Scalar(sqlAggregate, null, sumType(argument instanceof Scalar scalar ? scalar.type() : null));
            default -> argument instanceof Scalar scalar ? new Scalar(sqlAggregate, scalar.binder(), scalar.type())
                : new Scalar(sqlAggregate, null, null);
        };
    }

    private static Class<?> sumType(Class<?> argument) {
        Class<?> type = argument == null ? null : boxed(argument);
        if (type == null) {
            return null;
        }
        if (type == BigDecimal.class || type == BigInteger.class) {
            return type;
        }
        if (type == Float.class || type == Double.class) {
            return Double.class;
        }
        return Long.class;
    }

    // ---- conditions (§4.6) --------------------------------------------------------------------------------

    private Expression condition(Ast.Expr expr) {
        return switch (expr) {
            case Ast.Binary binary when binary.op() == Ast.Op.AND || binary.op() == Ast.Op.OR ->
                new Binary(condition(binary.left()), binary.op() == Ast.Op.AND ? Operator.AND : Operator.OR, condition(binary.right()));
            case Ast.Binary binary when !arithmetic(binary.op()) -> comparison(binary);
            case Ast.Not not -> new Expression.Not(condition(not.operand()));
            case Ast.IsNull isNull -> isNull(value(isNull.operand()), isNull.negated());
            case Ast.Between between -> {
                Scalar operand = scalar(value(between.operand()));
                yield new Expression.Between(operand.sql(), operand(between.low(), operand), operand(between.high(), operand),
                    between.negated());
            }
            case Ast.Like like -> {
                Scalar operand = scalar(value(like.operand()));
                yield new Expression.Like(operand.sql(), operand(like.pattern(), operand),
                    like.escape() == null ? null : operand(like.escape(), null), like.negated());
            }
            case Ast.In in -> in(in);
            case Ast.Path _, Ast.Literal _, Ast.Parameter _ -> scalar(value(expr)).sql(); // a boolean attribute or value
            default -> throw NotYet.milestone("P7", describe(expr));
        };
    }

    private static String describe(Ast.Expr expr) {
        return switch (expr) {
            case Ast.Function function -> "the function " + function.name();
            case Ast.Subquery _, Ast.Exists _ -> "subqueries";
            case Ast.IsEmpty _ -> "IS EMPTY";
            case Ast.MemberOf _ -> "MEMBER OF";
            default -> expr.getClass().getSimpleName();
        };
    }

    /** {@code left op right}: values compared, or entities compared key part by key part (§4.6.11). */
    private Expression comparison(Ast.Binary binary) {
        Operator op = operator(binary.op());
        Value left = binary.left() instanceof Ast.Parameter ? null : value(binary.left());
        Value right = binary.right() instanceof Ast.Parameter parameter ? parameterLike(parameter, left) : value(binary.right());
        if (left == null) {
            left = parameterLike((Ast.Parameter) binary.left(), right);
        }
        if (left instanceof Entity || right instanceof Entity) {
            if (!(left instanceof Entity l) || !(right instanceof Entity r) || l.key().size() != r.key().size()) {
                throw new IllegalArgumentException("An entity is compared with something that is not an entity of its type");
            }
            if (op != Operator.EQ && op != Operator.NE) {
                throw new IllegalArgumentException("Entities are compared with = and <> only (§4.6.11)");
            }
            Expression result = null;
            for (int k = 0; k < l.key().size(); k++) {
                Expression part = new Binary(l.key().get(k), op, r.key().get(k));
                result = result == null ? part : new Binary(result, op == Operator.EQ ? Operator.AND : Operator.OR, part);
            }
            return result;
        }
        return new Binary(((Scalar) left).sql(), op, ((Scalar) right).sql());
    }

    /** A parameter where {@code like} is expected: bound through its binder, or as the key parts of its entity. */
    private Value parameterLike(Ast.Parameter parameter, Value like) {
        if (like instanceof Entity entity) {
            List<Expression> key = new ArrayList<>();
            for (int part = 0; part < entity.key().size(); part++) {
                key.addAll(parameter(parameter, entity.type(), entity.binders().get(part), part, entity.type().model().javaType()));
            }
            return new Entity(entity.type(), key, entity.binders());
        }
        Scalar scalar = (Scalar) like;
        return new Scalar(parameter(parameter, null, scalar == null ? null : scalar.binder(), -1, scalar == null ? null : scalar.type())
            .getFirst(), null, scalar == null ? null : scalar.type());
    }

    /** The SQL of an operand compared with {@code with}: a parameter takes its binder. */
    private Expression operand(Ast.Expr expr, Scalar with) {
        if (expr instanceof Ast.Parameter parameter) {
            return parameter(parameter, null, with == null ? null : with.binder(), -1, with == null ? null : with.type()).getFirst();
        }
        return scalar(value(expr)).sql();
    }

    private Expression isNull(Value value, boolean negated) {
        Expression result = null;
        for (Expression part : sql(value)) {
            Expression test = new Expression.IsNull(part, negated);
            result = result == null ? test : new Binary(result, negated ? Operator.OR : Operator.AND, test);
        }
        return result;
    }

    /** {@code IN} a list of values, or a collection-valued parameter expanded to its elements (§4.6.9). */
    private Expression in(Ast.In in) {
        Value operand = value(in.operand());
        if (in.values().size() == 1 && in.values().getFirst() instanceof Ast.Subquery) {
            throw NotYet.milestone("P7", "subqueries");
        }
        List<Expression> values = new ArrayList<>();
        for (Ast.Expr value : in.values()) {
            if (value instanceof Ast.Parameter parameter) {
                Object bound = bindings == null ? null : bindings.apply(parameter);
                int size = bound instanceof Collection<?> collection ? collection.size() : -1;
                if (size == 0) {
                    return new Binary(new Expression.Literal(1), in.negated() ? Operator.EQ : Operator.NE, new Expression.Literal(1));
                }
                Scalar like = operand instanceof Scalar scalar ? scalar : null;
                if (operand instanceof Entity) {
                    throw NotYet.milestone("P7", "IN with entities");
                }
                for (int element = 0; element < Math.max(size, 1); element++) {
                    values.add(slot(new Compiled.Slot(parameter, size < 0 ? -1 : element, null, -1, like == null ? null : like.binder(),
                        like == null ? null : like.type())));
                }
            } else {
                values.add(scalar(value(value)).sql());
            }
        }
        return new Expression.In(scalar(operand).sql(), values, in.negated());
    }

    /** The SQL parameters of a query parameter: one, or one per key part of an entity. */
    private List<Expression> parameter(Ast.Parameter parameter, MappedEntity entity, ValueBinder binder, int part, Class<?> type) {
        return List.of(slot(new Compiled.Slot(parameter, -1, entity, part, binder, type)));
    }

    private static Expression slot(Compiled.Slot slot) {
        return new Expression.Parameter(slot);
    }

    private static Operator operator(Ast.Op op) {
        return switch (op) {
            case OR -> Operator.OR;
            case AND -> Operator.AND;
            case EQ -> Operator.EQ;
            case NE -> Operator.NE;
            case LT -> Operator.LT;
            case LE -> Operator.LE;
            case GT -> Operator.GT;
            case GE -> Operator.GE;
            case PLUS -> Operator.PLUS;
            case MINUS -> Operator.MINUS;
            case TIMES -> Operator.TIMES;
            case DIVIDE -> Operator.DIVIDE;
            case CONCAT -> Operator.CONCAT;
        };
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private String alias() {
        return "t" + aliases++;
    }

    private MappedEntity entity(Class<?> type) {
        return unit.entity(type).orElseThrow(() -> new IllegalArgumentException(type.getName() + " is not an entity of the unit"));
    }

    private static Table table(MappedEntity type) {
        return type.statements().tables().getFirst().select().table();
    }

    private static Entity entityValue(String alias, MappedEntity type) {
        List<EntityStatements.Column> keyColumns = type.statements().keyColumns();
        return new Entity(type, key(alias, type), keyColumns.stream().map(EntityStatements.Column::binder).toList());
    }

    private static List<Expression> key(String alias, MappedEntity type) {
        return type.statements().keyColumns().stream().<Expression>map(c -> new Expression.Column(alias, c.name())).toList();
    }

    private static List<Expression> columns(String alias, EntityStatements statements, int[] indexes) {
        List<Expression> columns = new ArrayList<>();
        for (int index : indexes) {
            columns.add(new Expression.Column(alias, statements.columns().get(index).name()));
        }
        return columns;
    }

    private static List<ValueBinder> binders(EntityStatements statements, int[] indexes) {
        List<ValueBinder> binders = new ArrayList<>();
        for (int index : indexes) {
            binders.add(statements.columns().get(index).binder());
        }
        return binders;
    }

    private static List<Expression> names(String alias, List<Identifier> names) {
        return names.stream().<Expression>map(n -> new Expression.Column(alias, n)).toList();
    }

    private static Expression equal(List<Expression> left, List<Expression> right) {
        Expression result = null;
        for (int k = 0; k < left.size(); k++) {
            Expression part = new Binary(left.get(k), Operator.EQ, right.get(k));
            result = result == null ? part : new Binary(result, Operator.AND, part);
        }
        return result;
    }

    private static Class<?> boxed(Class<?> type) {
        return MethodType.methodType(type).wrap().returnType();
    }
}
