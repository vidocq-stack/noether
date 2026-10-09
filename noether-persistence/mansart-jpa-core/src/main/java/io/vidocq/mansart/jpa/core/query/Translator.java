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
import io.vidocq.mansart.jpa.core.mapping.IndexMapping;
import io.vidocq.mansart.jpa.core.model.CollectionIndex;
import io.vidocq.mansart.jpa.core.model.AssociationAttribute;
import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.BasicAttribute;
import io.vidocq.mansart.jpa.core.model.ElementCollectionAttribute;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.model.EntityModel;
import io.vidocq.mansart.jpa.core.model.EmbeddableModel;
import io.vidocq.mansart.jpa.core.query.jpql.Ast;
import io.vidocq.mansart.jpa.core.spi.ManagedAccess;
import io.vidocq.mansart.jpa.core.session.NotYet;
import io.vidocq.mansart.jpa.dialect.sql.Expression;
import io.vidocq.mansart.jpa.dialect.sql.Expression.Binary;
import io.vidocq.mansart.jpa.dialect.sql.Expression.Operator;
import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import io.vidocq.mansart.jpa.dialect.sql.DeleteQuery;
import io.vidocq.mansart.jpa.dialect.sql.Query;
import io.vidocq.mansart.jpa.dialect.sql.SelectStatement;
import io.vidocq.mansart.jpa.dialect.sql.SetQuery;
import io.vidocq.mansart.jpa.dialect.sql.UpdateQuery;
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
 * Translates a select query (§4) to the SQL AST against the entity model: identification variables become table aliases,
 * paths columns, single-valued path navigation inner joins (§4.4.4), declared joins joins (§4.4.5), and entities their
 * key columns — compared key part by key part, read back as managed instances. A subquery is translated by a translator
 * of its own that sees the variables of the enclosing one (§4.5.10). One translator per translation: it holds the aliases
 * and joins it made.
 */
final class Translator {

    /**
     * An identification variable: the alias of its table, the FROM item it joins to, the translator that declared it;
     * the entity it ranges over, or the element collection whose elements it ranges over (§4.4.5).
     */
    private record Variable(MappedEntity type, String alias, int root, Translator owner, ElementCollectionMapping element,
            Expression position, IndexMapping index, String indexAlias) {
        Variable(MappedEntity type, String alias, int root, Translator owner) {
            this(type, alias, root, owner, null, null, null, alias);
        }

        Variable(MappedEntity type, String alias, int root, Translator owner, ElementCollectionMapping element) {
            this(type, alias, root, owner, element, null, element == null ? null : element.index(), alias);
        }

        Variable(MappedEntity type, String alias, int root, Translator owner, ElementCollectionMapping element,
                Expression position) {
            this(type, alias, root, owner, element, position, null, alias);
        }
    }

    /** What an expression denotes. */
    private sealed interface Value permits Scalar, Entity, Constant, Embedded, MapEntry {
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

    /** The basic columns of one embeddable value and their paths through its generated access. */
    private record Embedded(EmbeddableModel model, ManagedAccess access, List<Scalar> fields, List<int[]> paths) implements Value {
    }

    private record MapEntry(Value key, Value value) implements Value {
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
    private final Map<String, String> tableAliases = new HashMap<>();
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
    static Compiled translate(Ast.Statement statement, MappedUnit unit, Function<Ast.Parameter, Object> bindings, Integer offset,
            Integer limit) {
        Translator translator = new Translator(unit, bindings, null);
        String bulkEntity = statement instanceof Ast.Update update ? update.entity()
            : statement instanceof Ast.Delete delete ? delete.entity() : null;
        if (bulkEntity != null && unit.inheritance(translator.entityNamed(bulkEntity).model().javaType()).joined()) {
            return translator.joinedBulk(statement);
        }
        return switch (statement) {
            case Ast.Query query -> {
                List<Compiled.Item> items = new ArrayList<>();
                SelectStatement sql = translator.selectStatement(query, offset, limit, items);
                Class<?> resultType = items.size() > 1 ? Object[].class : type(items.getFirst());
                yield new Compiled(sql, List.copyOf(items), resultType == null ? null : boxed(resultType), slots(sql.parameters()));
            }
            case Ast.Update update -> {
                UpdateQuery sql = translator.update(update);
                yield new Compiled(sql, List.of(), null, slots(sql.parameters()));
            }
            case Ast.Delete delete -> {
                DeleteQuery sql = translator.delete(delete);
                yield new Compiled(sql, List.of(), null, slots(sql.parameters()));
            }
        };
    }

    private SelectStatement selectStatement(Ast.Query query, Integer offset, Integer limit, List<Compiled.Item> items) {
        return switch (query) {
            case Ast.Select select -> query(select, offset, limit, items);
            case Ast.SetQuery setQuery -> setQuery(setQuery, offset, limit, items);
        };
    }

    private SetQuery setQuery(Ast.SetQuery setQuery, Integer offset, Integer limit, List<Compiled.Item> items) {
        List<Query> operands = new ArrayList<>();
        List<Compiled.Item> firstItems = null;
        for (Ast.Select operand : setQuery.operands()) {
            List<Compiled.Item> operandItems = new ArrayList<>();
            Query sql = new Translator(unit, bindings, this).query(operand, null, null, operandItems);
            if (firstItems == null) {
                firstItems = operandItems;
                items.addAll(operandItems);
            } else {
                List<Compiled.Item> expectedItems = firstItems;
                if (operandItems.size() != expectedItems.size()
                        || java.util.stream.IntStream.range(0, expectedItems.size())
                            .anyMatch(i -> operandItems.get(i).width() != expectedItems.get(i).width())) {
                    throw new IllegalArgumentException("Every query in a set operation must select the same number of values");
                }
            }
            operands.add(sql);
        }
        Ast.Select first = setQuery.operands().getFirst();
        List<SetQuery.Order> orderBy = new ArrayList<>();
        for (Ast.OrderItem order : setQuery.orderBy()) {
            int position = orderPosition(first, order.expression());
            orderBy.add(new SetQuery.Order(position, order.descending(), order.nullsFirst()));
        }
        List<SetQuery.Operation> operations = setQuery.operations().stream()
            .map(operation -> new SetQuery.Operation(SetQuery.Operator.valueOf(operation.operator().name()), operation.all()))
            .toList();
        return new SetQuery(operands, operations, orderBy, offset, limit);
    }

    private static int orderPosition(Ast.Select select, Ast.Expr expression) {
        for (int i = 0; i < select.items().size(); i++) {
            Ast.Item item = select.items().get(i);
            if (item.expression().equals(expression)
                    || expression instanceof Ast.Path path && path.segments().size() == 1
                        && item.alias() != null && item.alias().equalsIgnoreCase(path.segments().getFirst())) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException("The ORDER BY expression of a set operation must be selected");
    }

    private static List<Compiled.Slot> slots(List<Expression.Parameter> parameters) {
        return parameters.stream().map(p -> (Compiled.Slot) p.slot()).toList();
    }

    // ---- bulk statements (§4.10) --------------------------------------------------------------------------

    /** Captures qualifying identities and assignment values before changing any table (§4.10). */
    private Compiled joinedBulk(Ast.Statement statement) {
        Ast.Update update = statement instanceof Ast.Update u ? u : null;
        Ast.Delete delete = statement instanceof Ast.Delete d ? d : null;
        MappedEntity type = entityNamed(update != null ? update.entity() : delete.entity());
        Variable variable = root(table(type), type, null);
        define(update != null ? update.variable() : delete.variable(), variable);
        List<Expression> projected = new ArrayList<>(key(variable.alias(), type));
        List<Compiled.Item> items = new ArrayList<>();
        List<ValueBinder> readers = new ArrayList<>(type.statements().keyColumns().stream()
            .map(EntityStatements.Column::binder).toList());
        readers.forEach(b -> items.add(new Compiled.ValueItem(b, null)));
        Map<Integer, List<Integer>> assigned = new java.util.LinkedHashMap<>();
        Map<Integer, List<Identifier>> setColumns = new java.util.LinkedHashMap<>();
        if (update != null) {
            for (Ast.Assignment assignment : update.assignments()) {
                List<String> segments = assignment.path().segments();
                List<String> path = lookup(segments.getFirst()) == variable ? segments.subList(1, segments.size()) : segments;
                int index = attribute(variable, path.getFirst());
                AttributeModel attribute = type.model().attributes().get(index);
                List<EntityStatements.Column> columns;
                List<Expression> values;
                if (attribute instanceof AssociationAttribute association && association.singleValued()) {
                    var reference = type.statements().reference(index).orElseThrow();
                    columns = Arrays.stream(reference.columns()).mapToObj(c -> type.statements().columns().get(c)).toList();
                    Entity like = new Entity(entity(association.targetEntity()), columns(variable, reference.columns()),
                        binders(type.statements(), reference.columns()));
                    values = assignment.value() instanceof Ast.Literal(Object v) && v == null
                        ? columns.stream().<Expression>map(_ -> new Expression.Literal(null)).toList()
                        : sql(assignment.value() instanceof Ast.Parameter ? boundValue(assignment.value(), like) : value(assignment.value()));
                } else {
                    Scalar lhs = scalar(path.size() > 1 ? embeddedPath(variable, index, path.subList(1, path.size()))
                        : attribute instanceof BasicAttribute basic ? basicValue(variable, index, basic) : null);
                    Expression.Column name = (Expression.Column) lhs.sql();
                    columns = List.of(type.statements().columns().stream().filter(c -> c.attribute() == index
                        && c.name().equals(name.name()) && tableAlias(variable, c.table()).equals(name.alias())).findFirst().orElseThrow());
                    values = List.of(bound(assignment.value(), lhs).sql());
                }
                for (int c = 0; c < columns.size(); c++) {
                    EntityStatements.Column column = columns.get(c);
                    assigned.computeIfAbsent(column.table(), _ -> new ArrayList<>()).add(projected.size());
                    setColumns.computeIfAbsent(column.table(), _ -> new ArrayList<>()).add(column.name());
                    projected.add(values.get(c));
                    readers.add(column.binder());
                    items.add(new Compiled.ValueItem(column.binder(), null));
                }
            }
        }
        Ast.Expr predicate = update != null ? update.where() : delete.where();
        Expression where = restricted(predicate == null ? null : condition(predicate));
        List<Query.From> from = new ArrayList<>();
        for (int r = 0; r < roots.size(); r++) {
            from.add(new Query.From(roots.get(r), rootAliases.get(r), joins.get(r)));
        }
        Query selection = new Query(false, projected, from, where, List.of(), null, List.of(), null, null);
        List<Integer> keyIndexes = java.util.stream.IntStream.range(0, type.statements().keyColumns().size()).boxed().toList();
        List<Compiled.Mutation> mutations = new ArrayList<>();
        if (update != null) {
            for (int t : assigned.keySet()) {
                var table = type.statements().tables().get(t).select();
                List<Integer> values = new ArrayList<>(assigned.get(t));
                values.addAll(keyIndexes);
                mutations.add(new Compiled.Mutation(new io.vidocq.mansart.jpa.dialect.sql.Update(table.table(),
                    setColumns.get(t), table.conditions()), List.copyOf(values), values.stream().map(readers::get).toList()));
            }
        } else {
            Map<Table, List<Identifier>> tables = new java.util.LinkedHashMap<>();
            unit.inheritance(type.model().javaType()).hierarchy().stream()
                .filter(e -> type.model().javaType().isAssignableFrom(e.javaType()))
                .sorted(java.util.Comparator.comparingInt(e -> unit.inheritance(e.javaType()).chain().size()))
                .forEach(e -> entity(e.javaType()).statements().tables().forEach(t ->
                    tables.putIfAbsent(t.select().table(), t.select().conditions())));
            List<Table> order = new ArrayList<>(tables.keySet());
            java.util.Collections.reverse(order);
            for (Table table : order) {
                mutations.add(new Compiled.Mutation(new io.vidocq.mansart.jpa.dialect.sql.Delete(table, tables.get(table)),
                    keyIndexes, keyIndexes.stream().map(readers::get).toList()));
            }
        }
        return new Compiled(selection, List.copyOf(items), Object[].class, slots(selection.parameters()), List.copyOf(mutations));
    }

    private UpdateQuery update(Ast.Update update) {
        Variable variable = bulkVariable(update.entity(), update.variable());
        List<UpdateQuery.Assignment> assignments = new ArrayList<>();
        for (Ast.Assignment assignment : update.assignments()) {
            List<String> segments = assignment.path().segments();
            List<String> attribute = lookup(segments.getFirst()) == variable ? segments.subList(1, segments.size()) : segments;
            if (attribute.isEmpty()) {
                throw new IllegalArgumentException("SET assigns an attribute, not the variable " + segments.getFirst() + " (§4.10)");
            }
            int index = attribute(variable, attribute.getFirst());
            AttributeModel model = variable.type().model().attributes().get(index);
            if (model instanceof AssociationAttribute association && association.singleValued()) {
                var reference = variable.type().statements().reference(index).orElseThrow(() -> new IllegalArgumentException(
                    "SET assigns " + association.name() + ", which this side of the relationship does not own (§4.10)"));
                EntityStatements statements = variable.type().statements();
                Entity like = new Entity(entity(association.targetEntity()), columns(variable.alias(), statements,
                    reference.columns()), binders(statements, reference.columns()));
                List<Expression> values = assignment.value() instanceof Ast.Literal(Object v) && v == null ? null
                    : sql(assignment.value() instanceof Ast.Parameter ? boundValue(assignment.value(), like) : value(assignment.value()));
                for (int k = 0; k < reference.columns().length; k++) {
                    assignments.add(new UpdateQuery.Assignment(statements.columns().get(reference.columns()[k]).name(),
                        values == null ? new Expression.Literal(null) : values.get(k)));
                }
                continue;
            }
            Scalar target = scalar(attribute.size() > 1 ? embeddedPath(variable, index, attribute.subList(1, attribute.size()))
                : model instanceof BasicAttribute basic ? basicValue(variable, index, basic) : null);
            Identifier column = ((Expression.Column) target.sql()).name();
            assignments.add(new UpdateQuery.Assignment(column, bound(assignment.value(), target).sql()));
        }
        Expression where = restricted(update.where() == null ? null : condition(update.where()));
        requireSingleTable();
        return new UpdateQuery(roots.getFirst(), variable.alias(), assignments, where);
    }

    private DeleteQuery delete(Ast.Delete delete) {
        Variable variable = bulkVariable(delete.entity(), delete.variable());
        Expression where = restricted(delete.where() == null ? null : condition(delete.where()));
        requireSingleTable();
        return new DeleteQuery(roots.getFirst(), variable.alias(), where);
    }

    private Variable bulkVariable(String entity, String name) {
        MappedEntity type = entityNamed(entity);
        if (type.statements().tables().size() > 1) {
            throw NotYet.milestone("P7", "bulk statements on an entity with secondary tables");
        }
        Variable variable = root(table(type), type, null);
        define(name, variable);
        return variable;
    }

    /** A bulk statement works on one table: a path that would need a join is not translated (§4.10). */
    private void requireSingleTable() {
        if (!joins.getFirst().isEmpty()) {
            throw NotYet.milestone("P7", "bulk statements whose paths navigate relationships");
        }
    }

    /** {@code segments} after the name of the implicit identification variable. */
    private static List<String> implicit(List<String> segments) {
        List<String> path = new ArrayList<>();
        path.add(Ast.IMPLICIT_VARIABLE);
        path.addAll(segments);
        return path;
    }

    private static Class<?> type(Compiled.Item item) {
        return switch (item) {
            case Compiled.EntityItem entity -> entity.type().model().javaType();
            case Compiled.ValueItem value -> value.type();
            case Compiled.ConstructorItem constructor -> constructor.type();
            case Compiled.MapEntryItem _ -> java.util.Map.Entry.class;
            case Compiled.EmbeddedItem embedded -> embedded.model().javaType();
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
            case Embedded embedded -> {
                sqlItems.addAll(embedded.fields().stream().map(Scalar::sql).toList());
                yield embeddedItem(embedded);
            }
            case MapEntry entry -> new Compiled.MapEntryItem(selectItem(entry.key(), sqlItems), selectItem(entry.value(), sqlItems));
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

    private SelectStatement subquery(Ast.Query query, List<Compiled.Item> items) {
        return child().selectStatement(query, null, null, items);
    }

    /** The SQL expressions of a value: itself, or the key columns of an entity. */
    private List<Expression> sql(Value value) {
        return switch (value) {
            case Scalar scalar -> List.of(scalar.sql());
            case Entity entity -> entity.key();
            case Embedded embedded -> embedded.fields().stream().map(Scalar::sql).toList();
            case MapEntry _ -> throw new IllegalArgumentException("ENTRY() is a select item (§4.6.17.2.3)");
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
        Variable variable = new Variable(type, alias, root, this, element);
        if (type != null) {
            Expression restriction = inheritanceRestriction(variable);
            if (restriction != null) {
                correlations.add(restriction);
            }
            var inheritance = unit.inheritance(type.model().javaType());
            if (inheritance.joined()) {
                for (int t = 1; t < inheritance.chain().size(); t++) {
                    tableAlias(variable, t);
                }
            }
        }
        return variable;
    }

    private Expression restricted(Expression where) {
        for (Expression restriction : correlations) {
            where = where == null ? restriction : new Binary(where, Operator.AND, restriction);
        }
        return where;
    }

    private Expression inheritanceRestriction(Variable variable) {
        var inheritance = unit.inheritance(variable.type().model().javaType());
        if (variable.type().model().javaType() == inheritance.root().javaType()) {
            return null;
        }
        if (inheritance.discriminator() == null) {
            if (!inheritance.joined()) {
                return null;
            }
            var table = variable.type().statements().tables().get(inheritance.chain().size() - 1).select();
            String alias = alias();
            Query query = new Query(false, List.of(new Expression.Literal(1)),
                List.of(new Query.From(table.table(), alias, List.of())),
                equal(names(alias, table.conditions()), key(variable.alias(), variable.type())),
                List.of(), null, List.of(), null, null);
            return new Expression.Exists(query, false);
        }
        List<Expression> values = inheritance.hierarchy().stream()
            .filter(e -> variable.type().model().javaType().isAssignableFrom(e.javaType()))
            .filter(e -> !inheritance.abstractEntity(e))
            .<Expression>map(e -> new Expression.Literal(inheritance.discriminatorValue(e))).toList();
        return new Expression.In(new Expression.Column(variable.alias(), Identifier.of(inheritance.discriminator())), values, false);
    }

    private String tableAlias(Variable variable, int table) {
        if (variable.owner() != this) {
            return variable.owner().tableAlias(variable, table);
        }
        if (table == 0) {
            return variable.alias();
        }
        String key = variable.alias() + "#" + table;
        String known = tableAliases.get(key);
        if (known != null) {
            return known;
        }
        String alias = alias();
        var statements = variable.type().statements().tables().get(table);
        var inheritance = unit.inheritance(variable.type().model().javaType());
        Query.Join.Kind kind = inheritance.joined() && table < inheritance.chain().size()
            ? Query.Join.Kind.INNER : Query.Join.Kind.LEFT;
        joins.get(variable.root()).add(new Query.Join(kind, statements.select().table(), alias,
            equal(names(alias, statements.select().conditions()), key(variable.alias(), variable.type()))));
        tableAliases.put(key, alias);
        return alias;
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
        if (path.treatEntity() != null) {
            if (path.treatAt() != segments.size() || segments.size() < 2) {
                throw new IllegalArgumentException("A treated join must end at its treated relationship (§4.4.5)");
            }
            return treatedJoin(segments, path.treatAt(), path.treatEntity(),
                kind == Ast.Join.Kind.LEFT ? Query.Join.Kind.LEFT : Query.Join.Kind.INNER);
        }
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

    private Variable treatedJoin(List<String> segments, int treatAt, String typeName, Query.Join.Kind kind) {
        Variable from = variable(segments.getFirst());
        for (int s = 1; s < treatAt - 1; s++) {
            from = navigate(from, attribute(from, segments.get(s)), Query.Join.Kind.INNER);
        }
        int attribute = attribute(from, segments.get(treatAt - 1));
        if (!(from.type().model().attributes().get(attribute) instanceof AssociationAttribute association)) {
            throw new IllegalArgumentException("TREAT in a join must name an entity relationship (§4.4.5)");
        }
        MappedEntity target = treatedType(association.targetEntity(), typeName);
        return association.singleValued()
            ? relationshipJoin(from, attribute, association, kind, target)
            : collectionJoin(from, attribute, association, kind, target);
    }

    /** The variable a single-valued path navigates to: one join per path (§4.4.4), shared by its uses. */
    private Variable navigate(Variable from, int attribute, Query.Join.Kind kind) {
        if (from.owner() != this) {
            return from.owner().navigate(from, attribute, kind);
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
        return relationshipJoin(from, attribute, association, kind, entity(association.targetEntity()));
    }

    private Variable relationshipJoin(Variable from, int attribute, AssociationAttribute association, Query.Join.Kind kind,
            MappedEntity target) {
        String alias = alias();
        Expression on;
        var owned = from.type().statements().reference(attribute);
        if (owned.isPresent()) { // from.fk = target.key
            on = equal(columns(from, owned.get().columns()), key(alias, target));
        } else if (!association.owning()) { // the owner's foreign key holds this key: target.fk = from.key
            var reference = target.statements().reference(attribute(target, association.mappedBy())).orElseThrow(() ->
                NotYet.milestone("P7", "a join through an inverse side that its owner does not map by a foreign key"));
            on = foreignKeyPredicate(alias, target, reference.columns(), key(from.alias(), from.type()));
        } else {
            throw NotYet.milestone("P7", "joins over a relationship through a join table");
        }
        Variable variable = new Variable(target, alias, from.root(), this);
        Expression restriction = inheritanceRestriction(variable);
        joins.get(from.root()).add(new Query.Join(kind, table(target), alias,
            restriction == null ? on : new Binary(on, Operator.AND, restriction)));
        return variable;
    }

    private Variable collectionJoin(Variable from, int attribute, AssociationAttribute association, Query.Join.Kind kind) {
        return collectionJoin(from, attribute, association, kind, entity(association.targetEntity()));
    }

    private Variable collectionJoin(Variable from, int attribute, AssociationAttribute association, Query.Join.Kind kind,
            MappedEntity target) {
        String alias = alias();
        List<Query.Join> list = joins.get(from.root());
        String positions = alias; // the table that holds the position of a member of an ordered list
        switch (collection(from, attribute, association)) {
            case Link.ByForeignKey link -> list.add(new Query.Join(kind, table(target), alias,
                foreignKeyPredicate(alias, target, link.columns(), key(from.alias(), from.type()))));
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
        Variable variable = new Variable(target, alias, from.root(), this, null, position, index, positions);
        Expression restriction = inheritanceRestriction(variable);
        if (restriction != null) {
            Query.Join last = list.getLast();
            list.set(list.size() - 1, new Query.Join(last.kind(), last.table(), last.alias(),
                new Binary(last.on(), Operator.AND, restriction)));
        }
        return variable;
    }

    /** {@code JOIN e.aliases a}: the rows of the collection table, the variable an element (§4.4.5). */
    private Variable elementJoin(Variable from, int attribute, Query.Join.Kind kind) {
        ElementCollectionMapping mapping = elementCollection(from, attribute);
        String alias = alias();
        joins.get(from.root()).add(new Query.Join(kind, mapping.table(), alias,
            equal(names(alias, mapping.ownerColumns()), key(from.alias(), from.type()))));
        return new Variable(null, alias, from.root(), this, mapping, null, mapping.index(), alias);
    }

    private Expression foreignKeyPredicate(String targetAlias, MappedEntity target, int[] indexes, List<Expression> ownerKey) {
        EntityStatements statements = target.statements();
        int tableIndex = statements.columns().get(indexes[0]).table();
        if (tableIndex == 0) {
            return equal(columns(targetAlias, statements, indexes), ownerKey);
        }
        var table = statements.tables().get(tableIndex).select();
        String alias = alias();
        Expression where = new Binary(equal(names(alias, table.conditions()), key(targetAlias, target)), Operator.AND,
            equal(columns(alias, statements, indexes), ownerKey));
        return new Expression.Exists(new Query(false, List.of(new Expression.Literal(1)),
            List.of(new Query.From(table.table(), alias, List.of())), where, List.of(), null, List.of(), null, null), false);
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
            case AssociationAttribute association when association.singleValued() -> {
                MappedEntity target = entity(association.targetEntity());
                Variable member = root(table(target), target, null);
                var reference = owner.type().statements().reference(attribute);
                if (reference.isPresent()) {
                    correlations.add(equal(columns(owner, reference.get().columns()), key(member.alias(), target)));
                } else if (!association.owning()) {
                    var inverse = target.statements().reference(attribute(target, association.mappedBy()));
                    if (inverse.isEmpty()) {
                        throw NotYet.milestone("P7", "a correlated path through an inverse relationship without a foreign key");
                    }
                    correlations.add(foreignKeyPredicate(member.alias(), target, inverse.get().columns(), ownerKey));
                } else {
                    throw NotYet.milestone("P7", "a correlated path through a single-valued relationship using a join table");
                }
                yield member;
            }
            case AssociationAttribute association when !association.singleValued() -> {
                MappedEntity target = entity(association.targetEntity());
                yield switch (collection(owner, attribute, association)) {
                    case Link.ByForeignKey link -> {
                        Variable member = root(table(target), target, null);
                        correlations.add(foreignKeyPredicate(member.alias(), target, link.columns(), ownerKey));
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
        if (path.treatEntity() != null) {
            return treatedPath(path);
        }
        String head = segments.getFirst().toLowerCase(Locale.ROOT);
        Variable variable = lookup(segments.getFirst());
        if (variable == null && !resultVariables.containsKey(head) && lookup(Ast.IMPLICIT_VARIABLE) != null
                && (segments.size() > 1 || enumConstant(segments) == null)) {
            // 3.2: the attributes of the implicit identification variable, named without it
            Variable implicit = lookup(Ast.IMPLICIT_VARIABLE);
            if (implicit.type() != null && indexOf(implicit.type().model().attributes(), segments.getFirst()) >= 0) {
                variable = implicit;
                segments = implicit(segments);
            }

        }
        if (variable == null) {
            if (segments.size() == 1 && resultVariables.containsKey(head)) {
                return resultVariables.get(head);
            }
            var entity = unit.model().entity(String.join(".", segments));
            if (entity.isPresent()) {
                return new Constant(entity.get().javaType());
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
            case BasicAttribute basic -> basicValue(variable, attribute, basic);
            case AssociationAttribute association when association.singleValued() -> {
                var owned = variable.type().statements().reference(attribute);
                if (owned.isPresent() && !selecting) { // compared and tested by its foreign key, without a join
                    EntityStatements statements = variable.type().statements();
                    yield new Entity(entity(association.targetEntity()), columns(variable, owned.get().columns()),
                        binders(statements, owned.get().columns()));
                }
                Variable target = navigate(variable, attribute, selecting ? Query.Join.Kind.INNER : Query.Join.Kind.LEFT);
                yield entityValue(target.alias(), target.type());
            }
            case EmbeddedAttribute embedded -> embeddedEntity(variable, attribute, embedded.embeddable(), new int[0]);
            default -> throw new IllegalArgumentException("The path " + String.join(".", segments) + " ends at a collection; it is "
                + "joined, or used with IS EMPTY, MEMBER OF or SIZE (§4.4.4)");
        };
    }

    private Value treatedPath(Ast.Path path) {
        List<String> segments = path.segments();
        int at = path.treatAt();
        if (at < 1 || at > segments.size()) {
            throw new IllegalArgumentException("TREAT has an invalid path (§4.4.9)");
        }
        Variable variable = variable(segments.getFirst());
        for (int s = 1; s < at; s++) {
            int attribute = attribute(variable, segments.get(s));
            if (s == at - 1) {
                if (!(variable.type().model().attributes().get(attribute) instanceof AssociationAttribute association)) {
                    throw new IllegalArgumentException("TREAT must apply to an entity-valued path (§4.4.9)");
                }
                MappedEntity target = treatedType(association.targetEntity(), path.treatEntity());
                variable = association.singleValued()
                    ? relationshipJoin(variable, attribute, association,
                        selecting ? Query.Join.Kind.INNER : Query.Join.Kind.LEFT, target)
                    : collectionJoin(variable, attribute, association,
                        selecting ? Query.Join.Kind.INNER : Query.Join.Kind.LEFT, target);
            } else {
                variable = navigate(variable, attribute, Query.Join.Kind.INNER);
            }
        }
        if (at == 1) {
            MappedEntity target = treatedType(variable.type().model().javaType(), path.treatEntity());
            variable = new Variable(target, variable.alias(), variable.root(), variable.owner(), variable.element(),
                variable.position(), variable.index(), variable.indexAlias());
            Expression restriction = inheritanceRestriction(variable);
            if (restriction != null) {
                correlations.add(restriction);
            }
        }
        if (segments.size() == at) {
            return entityValue(variable.alias(), variable.type());
        }
        if (variable.element() != null) {
            throw new IllegalArgumentException("TREAT cannot narrow an element collection value (§4.4.9)");
        }
        for (int s = at; s < segments.size() - 1; s++) {
            int attribute = attribute(variable, segments.get(s));
            if (variable.type().model().attributes().get(attribute) instanceof EmbeddedAttribute) {
                return embeddedPath(variable, attribute, segments.subList(s + 1, segments.size()));
            }
            variable = navigate(variable, attribute, Query.Join.Kind.INNER);
        }
        int attribute = attribute(variable, segments.getLast());
        AttributeModel model = variable.type().model().attributes().get(attribute);
        return switch (model) {
            case BasicAttribute basic -> basicValue(variable, attribute, basic);
            case AssociationAttribute association when association.singleValued() -> {
                Variable target = navigate(variable, attribute, selecting ? Query.Join.Kind.INNER : Query.Join.Kind.LEFT);
                yield entityValue(target.alias(), target.type());
            }
            case EmbeddedAttribute embedded -> embeddedEntity(variable, attribute, embedded.embeddable(), new int[0]);
            default -> throw new IllegalArgumentException("TREAT path ends at a collection (§4.4.9)");
        };
    }

    private MappedEntity treatedType(Class<?> declaredType, String typeName) {
        MappedEntity declared = entity(declaredType);
        MappedEntity treated = entityNamed(typeName);
        if (!declaredType.isAssignableFrom(treated.model().javaType())) {
            throw new IllegalArgumentException("TREAT target " + typeName + " is not a subtype of " + declaredType.getName() + " (§4.4.9)");
        }
        return treated;
    }

    /** The column of a basic attribute, or of the foreign key that holds it when an {@code @MapsId} maps it. */
    private Scalar basicValue(Variable variable, int attribute, BasicAttribute basic) {
        Scalar derived = derivedIdColumn(variable, attribute, List.of());
        return derived != null ? derived : column(variable, attribute, new int[0], basic.javaType());
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
        if (element instanceof EmbeddedAttribute embedded) {
            EmbeddableModel model = embedded.embeddable();
            int[] prefix = new int[0];
            AttributeModel current = embedded;
            for (int p = 0; p < rest.size(); p++) {
                int index = indexOf(model.attributes(), rest.get(p));
                if (index < 0) {
                    throw new IllegalArgumentException(model.javaType().getName() + " has no attribute " + rest.get(p));
                }
                prefix = Arrays.copyOf(prefix, prefix.length + 1);
                prefix[prefix.length - 1] = index;
                current = model.attributes().get(index);
                if (current instanceof EmbeddedAttribute nested) {
                    if (p == rest.size() - 1) {
                        return embeddedElement(variable, mapping, nested.embeddable(), prefix);
                    }
                    model = nested.embeddable();
                } else if (p < rest.size() - 1) {
                    throw new IllegalArgumentException("The path goes through " + rest.get(p) + ", which is not an embeddable");
                }
            }
            if (rest.isEmpty()) {
                return embeddedElement(variable, mapping, embedded.embeddable(), prefix);
            }
            if (!(current instanceof BasicAttribute basic)) {
                throw new IllegalArgumentException("The path does not end at a basic embeddable attribute");
            }
            for (EntityStatements.Column column : mapping.columns()) {
                if (Arrays.equals(column.path(), prefix)) {
                    return new Scalar(new Expression.Column(variable.alias(), column.name()), column.binder(), basic.javaType());
                }
            }
            throw new IllegalArgumentException("The element of " + mapping.model().name() + " has no column for " + rest);
        }
        if (!rest.isEmpty()) {
            throw new IllegalArgumentException("An element of " + mapping.model().name() + " is a value, it has no " + rest.getFirst());
        }
        BasicAttribute basic = (BasicAttribute) element;
        for (EntityStatements.Column column : mapping.columns()) {
            if (column.path().length == 0) {
                return new Scalar(new Expression.Column(variable.alias(), column.name()), column.binder(), basic.javaType());
            }
        }
        throw new IllegalArgumentException("The element of " + mapping.model().name() + " has no value column");
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
            if (model instanceof EmbeddedAttribute nested) {
                return embeddedEntity(variable, attribute, nested.embeddable(), path);
            }
            throw new IllegalArgumentException("The path does not end at a basic or embedded attribute");
        }
        return column(variable, attribute, path, basic.javaType());
    }

    private Embedded embeddedEntity(Variable variable, int attribute, EmbeddableModel model, int[] prefix) {
        List<Scalar> fields = new ArrayList<>();
        List<int[]> paths = new ArrayList<>();
        flatten(model, new int[0], (basic, path) -> {
            int[] complete = Arrays.copyOf(prefix, prefix.length + path.length);
            System.arraycopy(path, 0, complete, prefix.length, path.length);
            fields.add(column(variable, attribute, complete, basic.javaType()));
            paths.add(path);
        });
        return new Embedded(model, unit.access(model), List.copyOf(fields), List.copyOf(paths));
    }

    private Compiled.EmbeddedItem embeddedItem(Embedded embedded) {
        int[] field = new int[1];
        return embeddedItem(embedded.model(), embedded.fields(), field);
    }

    private Compiled.EmbeddedItem embeddedItem(EmbeddableModel model, List<Scalar> fields, int[] field) {
        List<Compiled.EmbeddedPart> parts = new ArrayList<>();
        for (AttributeModel attribute : model.attributes()) {
            if (attribute instanceof BasicAttribute) {
                Scalar scalar = fields.get(field[0]++);
                parts.add(new Compiled.ScalarEmbedded(scalar.binder(), scalar.type()));
            } else if (attribute instanceof EmbeddedAttribute embedded) {
                parts.add(new Compiled.NestedEmbedded(embeddedItem(embedded.embeddable(), fields, field)));
            } else {
                throw NotYet.milestone("P7", "relationships in an embeddable result");
            }
        }
        return new Compiled.EmbeddedItem(model, unit.access(model), List.copyOf(parts));
    }

    private Embedded embeddedElement(Variable variable, ElementCollectionMapping mapping, EmbeddableModel model, int[] prefix) {
        List<Scalar> fields = new ArrayList<>();
        List<int[]> paths = new ArrayList<>();
        flatten(model, new int[0], (basic, path) -> {
            int[] complete = Arrays.copyOf(prefix, prefix.length + path.length);
            System.arraycopy(path, 0, complete, prefix.length, path.length);
            for (EntityStatements.Column column : mapping.columns()) {
                if (Arrays.equals(column.path(), complete)) {
                    fields.add(new Scalar(new Expression.Column(variable.alias(), column.name()), column.binder(), basic.javaType()));
                    paths.add(path);
                    return;
                }
            }
            throw new IllegalArgumentException("The element of " + mapping.model().name() + " has no column for "
                + Arrays.toString(complete));
        });
        return new Embedded(model, unit.access(model), List.copyOf(fields), List.copyOf(paths));
    }

    @FunctionalInterface
    private interface BasicField {
        void accept(BasicAttribute attribute, int[] path);
    }

    private static void flatten(EmbeddableModel model, int[] prefix, BasicField consumer) {
        for (int i = 0; i < model.attributes().size(); i++) {
            AttributeModel attribute = model.attributes().get(i);
            int[] path = Arrays.copyOf(prefix, prefix.length + 1);
            path[path.length - 1] = i;
            if (attribute instanceof BasicAttribute basic) {
                consumer.accept(basic, path);
            } else if (attribute instanceof EmbeddedAttribute embedded) {
                flatten(embedded.embeddable(), path, consumer);
            } else {
                throw NotYet.milestone("P7", "relationships in an embeddable comparison");
            }
        }
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
            return new Scalar(new Expression.Column(tableAlias(variable, column.table()), column.name()), column.binder(), null);
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
                return new Scalar(new Expression.Column(tableAlias(variable, column.table()), column.name()), column.binder(), type);
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
            case Ast.Cast cast -> cast(cast);
            case Ast.Case kase -> caseValue(kase);
            case Ast.Subquery subquery -> {
                List<Compiled.Item> items = new ArrayList<>();
                SelectStatement query = subquery(subquery.select(), items);
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

    private Scalar cast(Ast.Cast cast) {
        Class<?> javaType = switch (cast.type()) {
            case STRING -> String.class;
            case INTEGER -> Integer.class;
            case LONG -> Long.class;
            case FLOAT -> Float.class;
            case DOUBLE -> Double.class;
            case FIXED, BIGDECIMAL -> BigDecimal.class;
            case BIGINTEGER -> BigInteger.class;
        };
        Scalar operand = bound(cast.expression(), new Scalar(null, null, javaType));
        Expression.Cast.Type sqlType = switch (cast.type()) {
            case STRING -> Expression.Cast.Type.VARCHAR;
            case INTEGER -> Expression.Cast.Type.INTEGER;
            case LONG -> Expression.Cast.Type.BIGINT;
            case FLOAT -> Expression.Cast.Type.REAL;
            case DOUBLE -> Expression.Cast.Type.DOUBLE_PRECISION;
            case FIXED, BIGDECIMAL -> Expression.Cast.Type.DECIMAL;
            case BIGINTEGER -> Expression.Cast.Type.DECIMAL;
        };
        return new Scalar(new Expression.Cast(operand.sql(), sqlType), null, javaType);
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
            case "TYPE" -> {
                Ast.Path path = path(arguments.getFirst(), "TYPE");
                Variable variable = variable(path.segments().getFirst());
                for (String segment : path.segments().subList(1, path.segments().size())) {
                    variable = navigate(variable, attribute(variable, segment), Query.Join.Kind.INNER);
                }
                var inheritance = unit.inheritance(variable.type().model().javaType());
                Expression sql;
                if (inheritance.discriminator() != null) {
                    sql = new Expression.Column(variable.alias(), Identifier.of(inheritance.discriminator()));
                } else if (!inheritance.joined()) {
                    sql = new Expression.Literal(variable.type().model().entityName());
                } else {
                    List<Expression.When> whens = new ArrayList<>();
                    var candidates = inheritance.hierarchy().stream()
                        .filter(e -> !inheritance.abstractEntity(e))
                        .filter(e -> e.javaType() != inheritance.root().javaType())
                        .sorted(java.util.Comparator.comparingInt((EntityModel e) ->
                            unit.inheritance(e.javaType()).chain().size()).reversed()).toList();
                    for (EntityModel candidate : candidates) {
                        var table = entity(candidate.javaType()).statements().tables()
                            .get(unit.inheritance(candidate.javaType()).chain().size() - 1).select();
                        String alias = alias();
                        Query query = new Query(false, List.of(new Expression.Literal(1)),
                            List.of(new Query.From(table.table(), alias, List.of())),
                            equal(names(alias, table.conditions()), key(variable.alias(), variable.type())),
                            List.of(), null, List.of(), null, null);
                        whens.add(new Expression.When(new Expression.Exists(query, false),
                            new Expression.Literal(candidate.entityName())));
                    }
                    sql = whens.isEmpty() ? new Expression.Literal(inheritance.root().entityName())
                        : new Expression.Case(null, whens, new Expression.Literal(inheritance.root().entityName()));
                }
                yield new Scalar(sql, inheritance.binder(true), Class.class);
            }
            case "ALL", "ANY" -> {
                List<Compiled.Item> items = new ArrayList<>();
                SelectStatement query = subquery(((Ast.Subquery) arguments.getFirst()).select(), items);
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
            case "KEY" -> mapKey(arguments.getFirst());
            case "VALUE" -> value(arguments.getFirst());
            case "ENTRY" -> {
                Value key = mapKey(arguments.getFirst());
                yield new MapEntry(key, value(arguments.getFirst()));
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

    private Value mapKey(Ast.Expr expression) {
        Ast.Path path = path(expression, "KEY");
        if (path.segments().size() != 1) {
            throw new IllegalArgumentException("KEY() takes the variable of a map-valued join (§4.6.17.2.3)");
        }
        Variable variable = variable(path.segments().getFirst());
        IndexMapping index = variable.index();
        if (index == null || index.index() instanceof CollectionIndex.ByPosition) {
            throw new IllegalArgumentException("KEY() takes the variable of a map-valued join (§4.6.17.2.3)");
        }
        return switch (index.index()) {
            case CollectionIndex.ByAttribute _ when index.keyAttribute() < 0 -> entityValue(variable.alias(), variable.type());
            case CollectionIndex.ByAttribute _ -> {
                AttributeModel key = variable.type().model().attributes().get(index.keyAttribute());
                if (key instanceof BasicAttribute basic) {
                    yield basicValue(variable, index.keyAttribute(), basic);
                }
                throw NotYet.milestone("P7", "map keys that are relationship attributes of their values");
            }
            case CollectionIndex.ByColumn key ->
                new Scalar(new Expression.Column(variable.indexAlias(), index.columns().getFirst()),
                    index.binders().isEmpty() ? null : index.binders().getFirst(), key.key().javaType());
            case CollectionIndex.ByEntity key -> new Entity(entity(key.entity()),
                index.columns().stream().<Expression>map(column -> new Expression.Column(variable.indexAlias(), column)).toList(),
                index.binders());
            case CollectionIndex.ByPosition _ -> throw new IllegalArgumentException("KEY() does not take a list index");
            case CollectionIndex.Unsupported unsupported -> throw NotYet.milestone("P5", unsupported.feature());
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
            case Ast.Path _, Ast.Literal _, Ast.Parameter _, Ast.Function _, Ast.Cast _, Ast.Subquery _, Ast.Case _ ->
                scalar(value(expr)).sql();
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
        if (left instanceof Embedded || right instanceof Embedded) {
            if (!(left instanceof Embedded l) || !(right instanceof Embedded r) || l.fields().size() != r.fields().size()) {
                throw new IllegalArgumentException("An embeddable is compared with a value of a different type (§4.6.11)");
            }
            if (op != Operator.EQ && op != Operator.NE) {
                throw new IllegalArgumentException("Embeddables are compared with = and <> only (§4.6.11)");
            }
            Expression result = null;
            for (int i = 0; i < l.fields().size(); i++) {
                Expression part = new Binary(l.fields().get(i).sql(), op, r.fields().get(i).sql());
                result = result == null ? part : new Binary(result, op == Operator.EQ ? Operator.AND : Operator.OR, part);
            }
            return result;
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

    /** {@code expr} where {@code like} is expected: a parameter or a literal takes its binder, or the key parts of its entity. */
    private Value boundValue(Ast.Expr expr, Value like) {
        if (like instanceof Embedded embedded) {
            if (expr instanceof Ast.Parameter parameter) {
                Object value = bindings == null ? null : bindings.apply(parameter);
                if (value != null && !embedded.model().javaType().isInstance(value)) {
                    throw new IllegalArgumentException("The parameter " + parameter + " is not a "
                        + embedded.model().javaType().getName());
                }
                List<Scalar> fields = new ArrayList<>();
                for (int i = 0; i < embedded.fields().size(); i++) {
                    Scalar field = embedded.fields().get(i);
                    Expression expression;
                    if (bindings == null) {
                        expression = slot(new Compiled.Slot(parameter, -1, null, -1, field.binder(), field.type(), null,
                            embedded.model().javaType()));
                    } else {
                        Object component = value == null ? null : embeddedValue(embedded.model(), value, embedded.paths().get(i));
                        expression = slot(Compiled.Slot.constant(component, field.binder(), field.type()));
                    }
                    fields.add(new Scalar(expression, field.binder(), field.type()));
                }
                return new Embedded(embedded.model(), embedded.access(), List.copyOf(fields), embedded.paths());
            }
            if (expr instanceof Ast.Literal literal && literal.value() == null) {
                List<Scalar> fields = embedded.fields().stream().map(field ->
                    new Scalar(slot(Compiled.Slot.constant(null, field.binder(), field.type())), field.binder(), field.type())).toList();
                return new Embedded(embedded.model(), embedded.access(), fields, embedded.paths());
            }
            throw new IllegalArgumentException("An embeddable is compared with an embeddable parameter or NULL (§4.6.11)");
        }
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

    private Object embeddedValue(EmbeddableModel model, Object instance, int[] path) {
        Object value = instance;
        EmbeddableModel current = model;
        for (int index : path) {
            if (value == null) {
                return null;
            }
            AttributeModel attribute = current.attributes().get(index);
            value = unit.access(current).get(value, index);
            if (attribute instanceof EmbeddedAttribute nested) {
                current = nested.embeddable();
            }
        }
        return value;
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
            case Embedded _ -> throw new IllegalArgumentException("An embeddable must be compared as a whole (§4.6.11)");
            case MapEntry _ -> throw new IllegalArgumentException("ENTRY() is a select item (§4.6.17.2.3)");
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

    private List<Expression> columns(Variable variable, int[] indexes) {
        List<Expression> columns = new ArrayList<>();
        for (int index : indexes) {
            EntityStatements.Column column = variable.type().statements().columns().get(index);
            columns.add(new Expression.Column(tableAlias(variable, column.table()), column.name()));
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
