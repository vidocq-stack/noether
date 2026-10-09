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
import io.vidocq.mansart.jpa.core.mapping.ElementCollectionMapping;
import io.vidocq.mansart.jpa.core.mapping.EntityStatements;
import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import io.vidocq.mansart.jpa.core.mapping.MappedUnit;
import io.vidocq.mansart.jpa.core.model.AssociationAttribute;
import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.BasicAttribute;
import io.vidocq.mansart.jpa.core.model.ElementCollectionAttribute;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Translates a select statement (§4) to a SQL {@link Query} against the entity model: identification variables become
 * table aliases, paths columns, single-valued path navigation inner joins (§4.4.4), declared joins joins (§4.4.5), and
 * entities their key columns — compared key part by key part, read back as managed instances. A subquery is translated
 * by a translator of its own that sees the variables of the enclosing one (§4.5.10). One translator per translation:
 * it holds the aliases and joins it made.
 */
final class Translator {

    /**
     * An identification variable: the alias of its table, the FROM item it joins to, the translator that declared it;
     * the entity it ranges over, or the element collection whose elements it ranges over (§4.4.5).
     */
    private record Variable(MappedEntity type, String alias, int root, Translator owner, ElementCollectionMapping element,
            Expression position) {
        Variable(MappedEntity type, String alias, int root, Translator owner) {
            this(type, alias, root, owner, null, null);
        }

        Variable(MappedEntity type, String alias, int root, Translator owner, ElementCollectionMapping element) {
            this(type, alias, root, owner, element, null);
        }
    }

    /** What an expression denotes. */
    private sealed interface Value permits Scalar, Entity, Constant {
    }

    /** A SQL value; {@code binder} binds and reads it when it is an attribute, {@code type} its Java type if known. */
    private record Scalar(Expression sql, ValueBinder binder, Class<?> type) implements Value {
    }

    /** An entity of {@code type}: the SQL of its key columns, in the order of its key, and their binders. */
    private record Entity(MappedEntity type, List<Expression> key, List<ValueBinder> binders) implements Value {
    }

    /** A literal the SQL does not write — an enum constant, a date (§4.6.1) — bound like what it meets. */
    private record Constant(Object value) implements Value {
    }

    private final MappedUnit unit;
    private final Function<Ast.Parameter, Object> bindings;
    private final Translator parent;
    private final int[] aliases;
    private final Map<String, Variable> variables = new HashMap<>();
    private final Map<String, Value> resultVariables = new HashMap<>();
    private final Map<String, Variable> navigations = new HashMap<>();
    private final List<Table> roots = new ArrayList<>();
    private final List<String> rootAliases = new ArrayList<>();
    private final List<List<Query.Join>> joins = new ArrayList<>();
    private final List<Expression> correlations = new ArrayList<>();
    private boolean selecting;

    /** @param bindings the values bound to the parameters, to expand those holding collections; {@code null} to validate */
    private Translator(MappedUnit unit, Function<Ast.Parameter, Object> bindings, Translator parent) {
        this.unit = unit;
        this.bindings = bindings;
        this.parent = parent;
        this.aliases = parent == null ? new int[1] : parent.aliases;
    }

    /**
     * Translates {@code select}. Without {@code bindings} (at {@code createQuery}) a collection-valued parameter counts
     * one element: the translation checks the query; with them, it is the one executed.
     */
    static Compiled translate(Ast.Select select, MappedUnit unit, Function<Ast.Parameter, Object> bindings, Integer offset,
            Integer limit) {
        Translator translator = new Translator(unit, bindings, null);
        List<Compiled.Item> items = new ArrayList<>();
        Query sql = translator.query(select, offset, limit, items);
        Class<?> resultType = items.size() > 1 ? Object[].class : type(items.getFirst());
        List<Compiled.Slot> slots = new ArrayList<>();
        for (Expression.Parameter parameter : sql.parameters()) {
            slots.add((Compiled.Slot) parameter.slot());
        }
        return new Compiled(sql, List.copyOf(items), resultType == null ? null : boxed(resultType), slots);
    }

    private static Class<?> type(Compiled.Item item) {
        return switch (item) {
            case Compiled.EntityItem entity -> entity.type().model().javaType();
            case Compiled.ValueItem value -> value.type();
            case Compiled.ConstructorItem constructor -> constructor.type();
        };
    }

    // ---- statements ---------------------------------------------------------------------------------------

    /** The SQL of {@code select}; {@code items} receives how each select item is read. */
    private Query query(Ast.Select select, Integer offset, Integer limit, List<Compiled.Item> items) {
        for (Ast.Range range : select.from()) {
            declare(range);
        }
        List<Expression> sqlItems = new ArrayList<>();
        for (Ast.Item item : select.items()) {
            selecting = true; // §4.4.4: a selected single-valued path is navigated, with inner join semantics
            Value value = item.expression() instanceof Ast.Constructor constructor ? null : value(item.expression());
            if (value == null) {
                items.add(constructor((Ast.Constructor) item.expression(), sqlItems));
            } else {
                items.add(selectItem(value, sqlItems));
            }
            selecting = false;
            if (item.alias() != null && value != null) {
                resultVariables.put(item.alias().toLowerCase(Locale.ROOT), value);
            }
        }
        Expression where = select.where() == null ? null : condition(select.where());
        for (Expression correlation : correlations) {
            where = where == null ? correlation : new Binary(correlation, Operator.AND, where);
        }
        List<Expression> groupBy = new ArrayList<>();
        for (Ast.Expr key : select.groupBy()) {
            groupBy.addAll(sql(value(key)));
        }
        Expression having = select.having() == null ? null : condition(select.having());
        List<Query.Order> orderBy = new ArrayList<>();
        for (Ast.OrderItem order : select.orderBy()) {
            for (Expression key : sql(value(order.expression()))) {
                orderBy.add(new Query.Order(key, order.descending(), order.nullsFirst()));
            }
        }
        List<Query.From> from = new ArrayList<>();
        for (int r = 0; r < roots.size(); r++) {
            from.add(new Query.From(roots.get(r), rootAliases.get(r), joins.get(r)));
        }
        return new Query(select.distinct(), sqlItems, from, where, groupBy, having, orderBy, offset, limit);
    }

    private Compiled.Item selectItem(Value value, List<Expression> sqlItems) {
        return switch (value) {
            case Entity entity -> {
                sqlItems.addAll(entity.key());
                yield new Compiled.EntityItem(entity.type(), entity.binders());
            }
            case Scalar scalar -> {
                sqlItems.add(scalar.sql());
                yield new Compiled.ValueItem(scalar.binder(), scalar.type());
            }
            case Constant constant -> {
                sqlItems.add(bound(constant, null).sql());
                yield new Compiled.ValueItem(null, constant.value() == null ? null : constant.value().getClass());
            }
        };
    }

    /** {@code NEW type(arguments)} (§4.8.2): the class is the application's, loaded by the class loader of the unit. */
    private Compiled.Item constructor(Ast.Constructor constructor, List<Expression> sqlItems) {
        Class<?> type;
        try {
            type = Class.forName(constructor.className(), false, unit.loader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("The constructor expression names " + constructor.className()
                + ", which cannot be loaded (§4.8.2)", e);
        }
        List<Compiled.Item> arguments = new ArrayList<>();
        for (Ast.Expr argument : constructor.arguments()) {
            arguments.add(selectItem(value(argument), sqlItems));
        }
        return new Compiled.ConstructorItem(type, List.copyOf(arguments));
    }

    /** The SQL of a subquery (§4.5.10): translated with the variables of this query in scope. */
    private Translator child() {
        return new Translator(unit, bindings, this);
    }

    private Query subquery(Ast.Select select, List<Compiled.Item> items) {
        return child().query(select, null, null, items);
    }

    /** The SQL expressions of a value: itself, or the key columns of an entity. */
    private List<Expression> sql(Value value) {
        return switch (value) {
            case Scalar scalar -> List.of(scalar.sql());
            case Entity entity -> entity.key();
            case Constant constant -> List.of(bound(constant, null).sql());
        };
    }

    // ---- FROM (§4.4) --------------------------------------------------------------------------------------

    private void declare(Ast.Range range) {
        Variable variable;
        if (range.entity() != null) {
            MappedEntity type = entityNamed(range.entity());
            variable = root(table(type), type, null);
        } else if (isOuter(range.collection().segments().getFirst())) {
            // a subquery ranging over a collection of an enclosing variable: its rows, correlated (§4.5.10)
            variable = rows(range.collection());
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

    /** A new FROM item of this query. */
    private Variable root(Table table, MappedEntity type, ElementCollectionMapping element) {
        int root = roots.size();
        String alias = alias();
        roots.add(table);
        rootAliases.add(alias);
        joins.add(new ArrayList<>());
        return new Variable(type, alias, root, this, element);
    }

    private boolean isOuter(String name) {
        return !variables.containsKey(name.toLowerCase(Locale.ROOT)) && parent != null && parent.lookup(name) != null;
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

    /** A declared join to the end of {@code path}: an entity of a relationship, or the elements of an element collection. */
    private Variable join(Ast.Path path, Ast.Join.Kind kind) {
        List<String> segments = path.segments();
        if (segments.size() < 2) {
            throw new IllegalArgumentException("A join names a relationship, not " + String.join(".", segments));
        }
        Variable from = variable(segments.getFirst());
        for (int s = 1; s < segments.size() - 1; s++) {
            from = navigate(from, attribute(from, segments.get(s)), Query.Join.Kind.INNER);
        }
        int attribute = attribute(from, segments.getLast());
        Query.Join.Kind sqlKind = kind == Ast.Join.Kind.LEFT ? Query.Join.Kind.LEFT : Query.Join.Kind.INNER;
        AttributeModel model = from.type().model().attributes().get(attribute);
        return switch (model) {
            case AssociationAttribute association when association.singleValued() -> relationshipJoin(from, attribute, association, sqlKind);
            case AssociationAttribute association -> collectionJoin(from, attribute, association, sqlKind);
            case ElementCollectionAttribute _ -> elementJoin(from, attribute, sqlKind);
            default -> throw new IllegalArgumentException("A join names a relationship or an element collection, not the attribute "
                + segments.getLast() + " of " + from.type().model().entityName() + " (§4.4.5)");
        };
    }

    /** The variable a single-valued path navigates to: one join per path (§4.4.4), shared by its uses. */
    private Variable navigate(Variable from, int attribute, Query.Join.Kind kind) {
        if (from.owner() != this) {
            throw NotYet.milestone("P7", "navigating a variable of an enclosing query in a subquery");
        }
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
            var reference = target.statements().reference(attribute(target, association.mappedBy())).orElseThrow(() ->
                NotYet.milestone("P7", "a join through an inverse side that its owner does not map by a foreign key"));
            on = equal(columns(alias, target.statements(), reference.columns()), key(from.alias(), from.type()));
        } else {
            throw NotYet.milestone("P7", "joins over a relationship through a join table");
        }
        joins.get(from.root()).add(new Query.Join(kind, table(target), alias, on));
        return new Variable(target, alias, from.root(), this);
    }

    private Variable collectionJoin(Variable from, int attribute, AssociationAttribute association, Query.Join.Kind kind) {
        MappedEntity target = entity(association.targetEntity());
        String alias = alias();
        List<Query.Join> list = joins.get(from.root());
        String positions = alias; // the table that holds the position of a member of an ordered list
        switch (collection(from, attribute, association)) {
            case Link.ByForeignKey link -> list.add(new Query.Join(kind, table(target), alias,
                equal(columns(alias, target.statements(), link.columns()), key(from.alias(), from.type()))));
            case Link.ByTable link -> {
                String row = alias();
                positions = row;
                list.add(new Query.Join(kind, link.table(), row, equal(names(row, link.ownerColumns()), key(from.alias(), from.type()))));
                list.add(new Query.Join(kind, table(target), alias, equal(key(alias, target), names(row, link.memberColumns()))));
            }
        }
        io.vidocq.mansart.jpa.core.mapping.IndexMapping index = switch (from.type().statements().collection(attribute).orElseThrow()) {
            case CollectionMapping.JoinTable table -> table.index();
            case CollectionMapping.MappedBy inverse -> inverse.index();
            case CollectionMapping.Unsupported _ -> null;
        };
        Expression position = index != null && index.positional() ? new Expression.Column(positions, index.columns().getFirst()) : null;
        return new Variable(target, alias, from.root(), this, null, position);
    }

    /** {@code JOIN e.aliases a}: the rows of the collection table, the variable an element (§4.4.5). */
    private Variable elementJoin(Variable from, int attribute, Query.Join.Kind kind) {
        ElementCollectionMapping mapping = elementCollection(from, attribute);
        String alias = alias();
        joins.get(from.root()).add(new Query.Join(kind, mapping.table(), alias,
            equal(names(alias, mapping.ownerColumns()), key(from.alias(), from.type()))));
        return new Variable(null, alias, from.root(), this, mapping);
    }

    private ElementCollectionMapping elementCollection(Variable from, int attribute) {
        return from.type().statements().elementCollection(attribute).orElseThrow(() -> NotYet.milestone("P5",
            "the element collection " + from.type().model().attributes().get(attribute).name() + " of " + from.type().model().entityName()));
    }

    /** How the members of a collection-valued relationship are reached from their owner. */
    private sealed interface Link {
        /** The members' foreign key {@code columns} (in the target's columns) hold the owner's key. */
        record ByForeignKey(int[] columns) implements Link {
        }

        /** A table of rows, owner key then member key. */
        record ByTable(Table table, List<Identifier> ownerColumns, List<Identifier> memberColumns) implements Link {
        }
    }

    private Link collection(Variable from, int attribute, AssociationAttribute association) {
        MappedEntity target = entity(association.targetEntity());
        return switch (from.type().statements().collection(attribute).orElseThrow()) {
            case CollectionMapping.JoinTable table -> new Link.ByTable(table.table(), table.ownerColumns(), table.targetColumns());
            case CollectionMapping.MappedBy _ -> {
                int mappedBy = attribute(target, association.mappedBy());
                var reference = target.statements().reference(mappedBy);
                var owner = target.statements().collection(mappedBy);
                if (reference.isPresent()) {
                    yield new Link.ByForeignKey(reference.get().columns());
                }
                if (owner.isPresent() && owner.get() instanceof CollectionMapping.JoinTable table) {
                    yield new Link.ByTable(table.table(), table.targetColumns(), table.ownerColumns());
                }
                throw NotYet.milestone("P7", "a collection whose owning side is mapped by a shape not mapped yet");
            }
            case CollectionMapping.Unsupported unsupported -> throw NotYet.milestone("P5", unsupported.feature());
        };
    }

    /**
     * The members of the collection at {@code path} as a FROM item of this (sub)query, correlated with the owner the
     * enclosing query holds: the variable of a member, the correlation added to this query's conditions.
     */
    private Variable rows(Ast.Path path) {
        List<String> segments = path.segments();
        Variable owner = variable(segments.getFirst());
        for (int s = 1; s < segments.size() - 1; s++) {
            owner = navigate(owner, attribute(owner, segments.get(s)), Query.Join.Kind.INNER);
        }
        int attribute = attribute(owner, segments.getLast());
        AttributeModel model = owner.type().model().attributes().get(attribute);
        List<Expression> ownerKey = key(owner.alias(), owner.type());
        return switch (model) {
            case AssociationAttribute association when !association.singleValued() -> {
                MappedEntity target = entity(association.targetEntity());
                yield switch (collection(owner, attribute, association)) {
                    case Link.ByForeignKey link -> {
                        Variable member = root(table(target), target, null);
                        correlations.add(equal(columns(member.alias(), target.statements(), link.columns()), ownerKey));
                        yield member;
                    }
                    case Link.ByTable link -> {
                        Variable row = root(link.table(), null, null);
                        correlations.add(equal(names(row.alias(), link.ownerColumns()), ownerKey));
                        String alias = alias();
                        joins.get(row.root()).add(new Query.Join(Query.Join.Kind.INNER, table(target), alias,
                            equal(key(alias, target), names(row.alias(), link.memberColumns()))));
                        yield new Variable(target, alias, row.root(), this);
                    }
                };
            }
            case ElementCollectionAttribute _ -> {
                ElementCollectionMapping mapping = elementCollection(owner, attribute);
                Variable member = root(mapping.table(), null, mapping);
                correlations.add(equal(names(member.alias(), mapping.ownerColumns()), ownerKey));
                yield member;
            }
            default -> throw new IllegalArgumentException("The path " + String.join(".", segments) + " is not a collection (§4.6.12)");
        };
    }

    // ---- paths (§4.4.4) -----------------------------------------------------------------------------------

    private Variable lookup(String name) {
        Variable variable = variables.get(name.toLowerCase(Locale.ROOT));
        return variable != null || parent == null ? variable : parent.lookup(name);
    }

    private Variable variable(String name) {
        Variable variable = lookup(name);
        if (variable == null) {
            throw new IllegalArgumentException("The identification variable " + name + " is not declared (§4.4.2)");
        }
        return variable;
    }

    /** What a path denotes: an entity, an attribute's column, the foreign key of a relationship, an element, an enum constant. */
    private Value path(Ast.Path path) {
        List<String> segments = path.segments();
        String head = segments.getFirst().toLowerCase(Locale.ROOT);
        Variable variable = lookup(segments.getFirst());
        if (variable == null) {
            if (segments.size() == 1 && resultVariables.containsKey(head)) {
                return resultVariables.get(head);
            }
            Object constant = segments.size() > 1 ? enumConstant(segments) : null;
            if (constant != null) {
                return new Constant(constant);
            }
            throw new IllegalArgumentException("The identification variable " + segments.getFirst() + " is not declared (§4.4.2)");
        }
        if (variable.element() != null) {
            return elementValue(variable, segments.subList(1, segments.size()));
        }
        if (segments.size() == 1) {
            return entityValue(variable.alias(), variable.type());
        }
        for (int s = 1; s < segments.size() - 1; s++) {
            int attribute = attribute(variable, segments.get(s));
            if (variable.type().model().attributes().get(attribute) instanceof EmbeddedAttribute) {
                return embeddedPath(variable, attribute, segments.subList(s + 1, segments.size()));
            }
            variable = navigate(variable, attribute, Query.Join.Kind.INNER);
        }
        int attribute = attribute(variable, segments.getLast());
        AttributeModel model = variable.type().model().attributes().get(attribute);
        return switch (model) {
            case BasicAttribute basic -> {
                Scalar derived = derivedIdColumn(variable, attribute, List.of());
                yield derived != null ? derived : column(variable, attribute, new int[0], basic.javaType());
            }
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

    /** The dotted name of an enum constant (§4.6.1), or {@code null}. */
    private Object enumConstant(List<String> segments) {
        String className = String.join(".", segments.subList(0, segments.size() - 1));
        try {
            Class<?> type = Class.forName(className, false, unit.loader());
            if (type.isEnum()) {
                for (Object constant : type.getEnumConstants()) {
                    if (((Enum<?>) constant).name().equals(segments.getLast())) {
                        return constant;
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            return null;
        }
        return null;
    }

    /** An element of an element collection, or an attribute of an embeddable one. */
    private Value elementValue(Variable variable, List<String> rest) {
        ElementCollectionMapping mapping = variable.element();
        AttributeModel element = mapping.model().element();
        int[] path = new int[rest.size()];
        if (element instanceof EmbeddedAttribute embedded) {
            List<AttributeModel> components = embedded.embeddable().attributes();
            for (int p = 0; p < rest.size(); p++) {
                int index = indexOf(components, rest.get(p));
                if (index < 0) {
                    throw new IllegalArgumentException(embedded.embeddable().javaType().getName() + " has no attribute " + rest.get(p));
                }
                path[p] = index;
                AttributeModel component = components.get(index);
                element = component;
                components = component instanceof EmbeddedAttribute nested ? nested.embeddable().attributes() : List.of();
            }
        } else if (!rest.isEmpty()) {
            throw new IllegalArgumentException("An element of " + mapping.model().name() + " is a value, it has no " + rest.getFirst());
        }
        if (!(element instanceof BasicAttribute basic)) {
            throw NotYet.milestone("P7", "embeddables compared as a whole");
        }
        for (EntityStatements.Column column : mapping.columns()) {
            if (Arrays.equals(column.path(), path)) {
                return new Scalar(new Expression.Column(variable.alias(), column.name()), column.binder(), basic.javaType());
            }
        }
        throw new IllegalArgumentException("The element of " + mapping.model().name() + " has no column for " + String.join(".", rest));
    }

    /** A path that ends in an embeddable: {@code e.address.city}. */
    private Value embeddedPath(Variable variable, int attribute, List<String> rest) {
        Scalar derived = derivedIdColumn(variable, attribute, rest);
        if (derived != null) {
            return derived;
        }
        EmbeddedAttribute embedded = (EmbeddedAttribute) variable.type().model().attributes().get(attribute);
        List<AttributeModel> components = embedded.embeddable().attributes();
        int[] path = new int[rest.size()];
        AttributeModel model = embedded;
        for (int p = 0; p < rest.size(); p++) {
            int index = indexOf(components, rest.get(p));
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

    /**
     * A part of an embedded identifier that an {@code @MapsId} relationship maps (§2.4.1): it has no column of its own,
     * the foreign key of the relationship holds it — {@code d.id.empPK.firstName} for {@code @MapsId("empPK")},
     * {@code d.id.firstName} for an {@code @MapsId} of the whole identifier. {@code null} for any other path.
     */
    private Scalar derivedIdColumn(Variable variable, int attribute, List<String> rest) {
        EntityModel model = variable.type().model();
        AttributeModel idAttribute = switch (model.id()) {
            case io.vidocq.mansart.jpa.core.model.IdModel.Embedded id -> id.attribute();
            case io.vidocq.mansart.jpa.core.model.IdModel.Single id -> id.attribute();
            default -> null;
        };
        if (idAttribute == null || model.attributes().indexOf(idAttribute) != attribute) {
            return null;
        }
        for (int a = 0; a < model.attributes().size(); a++) {
            if (!(model.attributes().get(a) instanceof AssociationAttribute association) || association.mapsId() == null) {
                continue;
            }
            List<String> within;
            if (association.mapsId().isEmpty()) {
                within = rest;
            } else if (rest.getFirst().equals(association.mapsId())) {
                within = rest.subList(1, rest.size());
            } else {
                continue;
            }
            MappedEntity parent = entity(association.targetEntity());
            List<String> parentKey = keyNames(parent.model());
            int part = within.isEmpty() ? 0 : parentKey.indexOf(within.getFirst());
            var reference = variable.type().statements().reference(a);
            if (part < 0 || within.size() > 1 || reference.isEmpty()) {
                return null;
            }
            EntityStatements.Column column = variable.type().statements().columns().get(reference.get().columns()[part]);
            return new Scalar(new Expression.Column(variable.alias(), column.name()), column.binder(), null);
        }
        return null;
    }

    /** The names of the key parts of an entity, in the order of its key. */
    private static List<String> keyNames(EntityModel model) {
        return switch (model.id()) {
            case io.vidocq.mansart.jpa.core.model.IdModel.Single single -> List.of(single.attribute().name());
            case io.vidocq.mansart.jpa.core.model.IdModel.Embedded embedded ->
                embedded.attribute().embeddable().attributes().stream().map(AttributeModel::name).toList();
            case io.vidocq.mansart.jpa.core.model.IdModel.ByIdClass byIdClass -> byIdClass.attributes().stream().map(AttributeModel::name).toList();
            case io.vidocq.mansart.jpa.core.model.IdModel.Derived _ -> List.of();
        };
    }

    private static int indexOf(List<AttributeModel> attributes, String name) {
        for (int i = 0; i < attributes.size(); i++) {
            if (attributes.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** The column of attribute {@code attribute} (at {@code path} in its embeddable) of {@code variable}. */
    private Scalar column(Variable variable, int attribute, int[] path, Class<?> type) {
        for (EntityStatements.Column column : variable.type().statements().columns()) {
            if (column.attribute() == attribute && column.foreignKey() == null && Arrays.equals(column.path(), path)) {
                if (column.table() != 0) {
                    throw NotYet.milestone("P7", "a column of a secondary table in a query");
                }
                return new Scalar(new Expression.Column(variable.alias(), column.name()), column.binder(), type);
            }
        }
        throw new IllegalArgumentException("The attribute " + variable.type().model().attributes().get(attribute).name() + " of "
            + variable.type().model().entityName() + " has no column");
    }

    private int attribute(Variable variable, String name) {
        if (variable.type() == null) {
            throw new IllegalArgumentException("An element of " + variable.element().model().name() + " has no relationship " + name);
        }
        return attribute(variable.type(), name);
    }

    private static int attribute(MappedEntity type, String name) {
        int index = indexOf(type.model().attributes(), name);
        if (index < 0) {
            throw new IllegalArgumentException(type.model().entityName() + " has no persistent attribute " + name);
        }
        return index;
    }

    // ---- expressions (§4.6) -------------------------------------------------------------------------------

    private Value value(Ast.Expr expr) {
        return switch (expr) {
            case Ast.Path path -> path(path);
            case Ast.Literal literal -> literal.value() == null || literal.value() instanceof Boolean || literal.value() instanceof Number
                || literal.value() instanceof String ? new Scalar(new Expression.Literal(literal.value()), null,
                    literal.value() == null ? null : literal.value().getClass()) : new Constant(literal.value());
            case Ast.Parameter parameter -> bound(parameter, null);
            case Ast.Binary binary when arithmetic(binary.op()) -> {
                Value left = value(binary.left());
                Scalar right = bound(binary.right(), left);
                Scalar leftScalar = bound(left, right);
                yield new Scalar(new Binary(leftScalar.sql(), operator(binary.op()), right.sql()), null,
                    binary.op() == Ast.Op.CONCAT ? String.class : promote(binary.op(), leftScalar.type(), right.type()));
            }
            case Ast.Negate negate -> {
                Scalar operand = scalar(value(negate.operand()));
                yield new Scalar(new Binary(new Expression.Literal(0), Operator.MINUS, operand.sql()), null, operand.type());
            }
            case Ast.Aggregate aggregate -> aggregate(aggregate);
            case Ast.Function function -> function(function);
            case Ast.Case kase -> caseValue(kase);
            case Ast.Subquery subquery -> {
                List<Compiled.Item> items = new ArrayList<>();
                Query query = subquery(subquery.select(), items);
                yield new Scalar(new Expression.Subquery(query), binder(items), type(items.getFirst()));
            }
            case Ast.Constructor _ -> throw new IllegalArgumentException("A constructor expression is a select item only (§4.8.2)");
            default -> new Scalar(condition(expr), null, Boolean.class);
        };
    }

    private static ValueBinder binder(List<Compiled.Item> items) {
        return items.getFirst() instanceof Compiled.ValueItem value ? value.binder() : null;
    }

    private static boolean arithmetic(Ast.Op op) {
        return op == Ast.Op.PLUS || op == Ast.Op.MINUS || op == Ast.Op.TIMES || op == Ast.Op.DIVIDE || op == Ast.Op.CONCAT;
    }

    /** §4.8.6: the numeric type of an arithmetic result, the widest of its operands'. */
    private static Class<?> promote(Ast.Op op, Class<?> left, Class<?> right) {
        if (left == null || right == null) {
            return left == null ? right : left;
        }
        List<Class<?>> order = List.of(Short.class, Integer.class, Long.class, BigInteger.class, Float.class, Double.class, BigDecimal.class);
        int l = order.indexOf(boxed(left));
        int r = order.indexOf(boxed(right));
        if (l < 0 || r < 0) {
            return null;
        }
        Class<?> widest = order.get(Math.max(Math.max(l, r), op == Ast.Op.DIVIDE ? 0 : 1));
        return widest == Short.class ? Integer.class : widest;
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
            case Entity entity -> {
                if (entity.key().size() > 1 && (aggregate.distinct() || !aggregate.function().equals("COUNT"))) {
                    throw NotYet.milestone("P7", aggregate.function() + " of an entity with a composite key");
                }
                yield entity.key().getFirst();
            }
            default -> bound(argument, null).sql();
        };
        Expression.Aggregate sqlAggregate = new Expression.Aggregate(aggregate.function(), aggregate.distinct(), sql);
        Scalar scalar = argument instanceof Scalar s ? s : null;
        return switch (aggregate.function()) {
            case "COUNT" -> new Scalar(sqlAggregate, null, Long.class);
            case "AVG" -> new Scalar(sqlAggregate, null, Double.class);
            case "SUM" -> new Scalar(sqlAggregate, null, sumType(scalar == null ? null : scalar.type()));
            default -> scalar != null ? new Scalar(sqlAggregate, scalar.binder(), scalar.type()) : new Scalar(sqlAggregate, null, null);
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

    /** The functions of the language (§4.6.17.2), typed as §4.8 says. */
    private Value function(Ast.Function function) {
        String name = function.name();
        List<Ast.Expr> arguments = function.arguments();
        return switch (name) {
            case "ALL", "ANY" -> {
                List<Compiled.Item> items = new ArrayList<>();
                Query query = subquery(((Ast.Subquery) arguments.getFirst()).select(), items);
                yield new Scalar(new Expression.Quantified(name, query), binder(items), type(items.getFirst()));
            }
            case "SIZE" -> {
                Translator child = child();
                Variable member = child.rows(path(arguments.getFirst(), "SIZE"));
                Query query = child.collectionQuery(new Expression.Aggregate("COUNT", false, null), member, null);
                yield new Scalar(new Expression.Subquery(query), null, Integer.class);
            }
            case "INDEX" -> {
                Variable variable = variable(path(arguments.getFirst(), "INDEX").segments().getFirst());
                if (variable.position() == null) {
                    throw new IllegalArgumentException("INDEX() takes the variable of a join over a list with an order column (§4.6.17.2.2)");
                }
                yield new Scalar(variable.position(), null, Integer.class);
            }
            case "ID" -> {
                Entity entity = entityOf(value(arguments.getFirst()), "ID");
                if (entity.key().size() > 1) {
                    throw NotYet.milestone("P7", "ID() of an entity with a composite key");
                }
                yield new Scalar(entity.key().getFirst(), entity.binders().getFirst(), entity.type().statements().keyColumns().size() == 1
                    && entity.type().model().id() instanceof io.vidocq.mansart.jpa.core.model.IdModel.Single single
                    ? single.attribute().javaType() : null);
            }
            case "VERSION" -> {
                Ast.Path path = path(arguments.getFirst(), "VERSION");
                Variable variable = variable(path.segments().getFirst());
                if (path.segments().size() > 1 || variable.type() == null || variable.type().model().version().isEmpty()) {
                    throw new IllegalArgumentException("VERSION() takes an identification variable of a versioned entity");
                }
                BasicAttribute version = variable.type().model().version().get();
                yield column(variable, variable.type().model().attributes().indexOf(version), new int[0], version.javaType());
            }
            case "CURRENT_DATE" -> new Scalar(new Expression.Function(name, List.of()), null, java.sql.Date.class);
            case "CURRENT_TIME" -> new Scalar(new Expression.Function(name, List.of()), null, java.sql.Time.class);
            case "CURRENT_TIMESTAMP" -> new Scalar(new Expression.Function(name, List.of()), null, java.sql.Timestamp.class);
            case "LOCAL_DATE" -> new Scalar(new Expression.Function(name, List.of()), null, java.time.LocalDate.class);
            case "LOCAL_TIME" -> new Scalar(new Expression.Function(name, List.of()), null, java.time.LocalTime.class);
            case "LOCAL_DATETIME" -> new Scalar(new Expression.Function(name, List.of()), null, java.time.LocalDateTime.class);
            case "TRIM" -> new Scalar(new Expression.Function(name, List.of(new Expression.Literal(((Ast.Literal) arguments.get(0)).value()),
                bound(arguments.get(1), null).sql(), bound(arguments.get(2), null).sql())), null, String.class);
            case "EXTRACT" -> {
                String field = (String) ((Ast.Literal) arguments.get(0)).value();
                yield new Scalar(new Expression.Function(name, List.of(new Expression.Literal(field), bound(arguments.get(1), null).sql())),
                    null, field.equals("SECOND") ? Double.class : Integer.class);
            }
            case "CONCAT", "SUBSTRING", "LOWER", "UPPER", "LEFT", "RIGHT", "REPLACE" -> sqlFunction(name, arguments, String.class);
            case "LENGTH", "LOCATE", "MOD", "SIGN" -> sqlFunction(name, arguments, Integer.class);
            case "SQRT", "EXP", "LN", "POWER" -> sqlFunction(name, arguments, Double.class);
            case "ABS", "CEILING", "FLOOR", "ROUND" -> {
                Scalar first = bound(arguments.getFirst(), null);
                yield sqlFunction(name, arguments, first.type());
            }
            case "COALESCE", "NULLIF" -> {
                Value first = value(arguments.getFirst());
                Scalar like = bound(first, null);
                List<Expression> sql = new ArrayList<>();
                sql.add(like.sql());
                for (Ast.Expr argument : arguments.subList(1, arguments.size())) {
                    sql.add(bound(argument, like).sql());
                }
                yield new Scalar(new Expression.Function(name, sql), like.binder(), like.type());
            }
            default -> throw NotYet.milestone("P7", "the function " + name);
        };
    }

    private Scalar sqlFunction(String name, List<Ast.Expr> arguments, Class<?> type) {
        List<Expression> sql = new ArrayList<>();
        for (Ast.Expr argument : arguments) {
            sql.add(bound(argument, null).sql());
        }
        return new Scalar(new Expression.Function(name, sql), null, type);
    }

    private static Ast.Path path(Ast.Expr expr, String function) {
        if (expr instanceof Ast.Path path) {
            return path;
        }
        throw new IllegalArgumentException(function + "() takes a path (§4.6.17.2)");
    }

    private static Entity entityOf(Value value, String function) {
        if (value instanceof Entity entity) {
            return entity;
        }
        throw new IllegalArgumentException(function + "() takes an entity (§4.6.17.2)");
    }

    /** {@code CASE} (§4.6.17.4): typed and read as its first result that tells its type. */
    private Scalar caseValue(Ast.Case kase) {
        Scalar operand = kase.operand() == null ? null : bound(kase.operand(), null);
        List<Expression.When> whens = new ArrayList<>();
        Scalar like = null;
        for (Ast.When when : kase.whens()) {
            Expression condition = operand == null ? condition(when.when()) : bound(when.when(), operand).sql();
            Scalar then = bound(when.then(), like);
            like = like == null || like.type() == null ? then : like;
            whens.add(new Expression.When(condition, then.sql()));
        }
        Expression otherwise = kase.otherwise() == null ? null : bound(kase.otherwise(), like).sql();
        return new Scalar(new Expression.Case(operand == null ? null : operand.sql(), whens, otherwise),
            like == null ? null : like.binder(), like == null ? null : like.type());
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
                yield new Expression.Between(operand.sql(), bound(between.low(), operand).sql(), bound(between.high(), operand).sql(),
                    between.negated());
            }
            case Ast.Like like -> {
                Scalar operand = scalar(value(like.operand()));
                yield new Expression.Like(operand.sql(), bound(like.pattern(), operand).sql(),
                    like.escape() == null ? null : bound(like.escape(), null).sql(), like.negated());
            }
            case Ast.In in -> in(in);
            case Ast.Exists exists -> new Expression.Exists(subquery(exists.select(), new ArrayList<>()), exists.negated());
            case Ast.IsEmpty isEmpty -> {
                Translator child = child();
                Variable member = child.rows(path(isEmpty.collection(), "IS EMPTY"));
                yield new Expression.Exists(child.collectionQuery(new Expression.Literal(1), member, null), !isEmpty.negated());
            }
            case Ast.MemberOf memberOf -> memberOf(memberOf);
            case Ast.Path _, Ast.Literal _, Ast.Parameter _, Ast.Function _, Ast.Subquery _, Ast.Case _ -> scalar(value(expr)).sql();
            default -> throw NotYet.milestone("P7", expr.getClass().getSimpleName());
        };
    }

    /** A query over the rows of a collection {@link #rows} declared in this (child) translator. */
    private Query collectionQuery(Expression item, Variable member, Expression condition) {
        Expression where = condition;
        for (Expression correlation : correlations) {
            where = where == null ? correlation : new Binary(correlation, Operator.AND, where);
        }
        List<Query.From> from = new ArrayList<>();
        for (int r = 0; r < roots.size(); r++) {
            from.add(new Query.From(roots.get(r), rootAliases.get(r), joins.get(r)));
        }
        return new Query(false, List.of(item), from, where, List.of(), null, List.of(), null, null);
    }

    /** {@code value [NOT] MEMBER OF collection} (§4.6.13): a row of the collection holds it. */
    private Expression memberOf(Ast.MemberOf memberOf) {
        Translator child = child();
        Variable member = child.rows(path(memberOf.collection(), "MEMBER OF"));
        Value element = member.element() != null ? child.elementValue(member, List.of()) : entityValue(member.alias(), member.type());
        Expression equality = child.equality(memberOf.value(), element);
        return new Expression.Exists(child.collectionQuery(new Expression.Literal(1), member, equality), memberOf.negated());
    }

    /** {@code left = right}, an entity compared key part by key part. */
    private Expression equality(Ast.Expr left, Value right) {
        Value value = left instanceof Ast.Parameter || left instanceof Ast.Literal ? null : value(left);
        Value bound = value != null ? value : boundValue(left, right);
        return compare(bound, Operator.EQ, right);
    }

    /** {@code left op right}: values compared, or entities compared key part by key part (§4.6.11). */
    private Expression comparison(Ast.Binary binary) {
        Operator op = operator(binary.op());
        Value left = binary.left() instanceof Ast.Parameter ? null : value(binary.left());
        Value right = binary.right() instanceof Ast.Parameter ? boundValue(binary.right(), left) : value(binary.right());
        if (left == null) {
            left = boundValue(binary.left(), right);
        }
        if (left instanceof Constant constant) {
            left = bound(constant, right instanceof Scalar s ? s : null);
        }
        if (right instanceof Constant constant) {
            right = bound(constant, left instanceof Scalar s ? s : null);
        }
        return compare(left, op, right);
    }

    private static Expression compare(Value left, Operator op, Value right) {
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

    /** {@code expr} where {@code like} is expected: a parameter or a literal takes its binder, or the key parts of its entity. */
    private Value boundValue(Ast.Expr expr, Value like) {
        if (expr instanceof Ast.Parameter parameter && like instanceof Entity entity) {
            List<Expression> key = new ArrayList<>();
            for (int part = 0; part < entity.key().size(); part++) {
                key.add(slot(new Compiled.Slot(parameter, -1, entity.type(), part, entity.binders().get(part),
                    entity.type().model().javaType(), null)));
            }
            return new Entity(entity.type(), key, entity.binders());
        }
        return bound(expr, like instanceof Scalar scalar ? scalar : null);
    }

    /** The SQL of {@code expr} where {@code like} is expected: a parameter or a constant takes its binder. */
    private Scalar bound(Ast.Expr expr, Value like) {
        Scalar with = like instanceof Scalar scalar ? scalar : null;
        if (expr instanceof Ast.Parameter parameter) {
            return new Scalar(slot(new Compiled.Slot(parameter, -1, null, -1, with == null ? null : with.binder(),
                with == null ? null : with.type(), null)), null, with == null ? null : with.type());
        }
        return bound(value(expr), with);
    }

    /** A value as a SQL scalar: a constant bound through the binder of {@code like}. */
    private Scalar bound(Value value, Scalar like) {
        return switch (value) {
            case Scalar scalar -> scalar;
            case Constant constant -> new Scalar(slot(Compiled.Slot.constant(constant.value(), like == null ? null : like.binder(),
                like == null ? null : like.type())), like == null ? null : like.binder(),
                constant.value() == null ? null : constant.value().getClass());
            case Entity entity -> {
                if (entity.key().size() > 1) {
                    throw new IllegalArgumentException("An entity with a composite key is used where a value is expected");
                }
                yield new Scalar(entity.key().getFirst(), entity.binders().getFirst(), null);
            }
        };
    }

    private Expression isNull(Value value, boolean negated) {
        Expression result = null;
        for (Expression part : sql(value)) {
            Expression test = new Expression.IsNull(part, negated);
            result = result == null ? test : new Binary(result, negated ? Operator.OR : Operator.AND, test);
        }
        return result;
    }

    /** {@code IN} values, a collection-valued parameter expanded to its elements (§4.6.9), or a subquery. */
    private Expression in(Ast.In in) {
        Value operand = value(in.operand());
        if (in.values().size() == 1 && in.values().getFirst() instanceof Ast.Subquery subquery) {
            Expression left = switch (operand) {
                case Entity entity when entity.key().size() == 1 -> entity.key().getFirst();
                case Entity _ -> throw NotYet.milestone("P7", "IN a subquery for an entity with a composite key");
                default -> bound(operand, null).sql();
            };
            return new Expression.InQuery(left, subquery(subquery.select(), new ArrayList<>()), in.negated());
        }
        if (operand instanceof Entity) {
            throw NotYet.milestone("P7", "IN with entities");
        }
        Scalar like = (Scalar) bound(operand, null);
        List<Expression> values = new ArrayList<>();
        for (Ast.Expr value : in.values()) {
            if (value instanceof Ast.Parameter parameter) {
                Object bound = bindings == null ? null : bindings.apply(parameter);
                int size = bound instanceof Collection<?> collection ? collection.size() : -1;
                if (size == 0) {
                    return new Binary(new Expression.Literal(1), in.negated() ? Operator.EQ : Operator.NE, new Expression.Literal(1));
                }
                for (int element = 0; element < Math.max(size, 1); element++) {
                    values.add(slot(new Compiled.Slot(parameter, size < 0 ? -1 : element, null, -1, like.binder(), like.type(), null)));
                }
            } else {
                values.add(bound(value, like).sql());
            }
        }
        return new Expression.In(like.sql(), values, in.negated());
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
        return "t" + aliases[0]++;
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
