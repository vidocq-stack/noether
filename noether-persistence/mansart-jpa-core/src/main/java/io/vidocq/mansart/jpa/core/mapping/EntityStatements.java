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

import io.vidocq.mansart.jpa.core.jdbc.type.ValueBinder;
import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.BasicAttribute;
import io.vidocq.mansart.jpa.core.model.ColumnModel;
import io.vidocq.mansart.jpa.core.model.EmbeddableModel;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.model.EntityModel;
import io.vidocq.mansart.jpa.core.model.IdModel;
import io.vidocq.mansart.jpa.core.model.TableModel;
import io.vidocq.mansart.jpa.core.session.NotYet;
import io.vidocq.mansart.jpa.core.spi.ManagedAccess;
import io.vidocq.mansart.jpa.dialect.sql.Delete;
import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import io.vidocq.mansart.jpa.dialect.sql.Insert;
import io.vidocq.mansart.jpa.dialect.sql.Select;
import io.vidocq.mansart.jpa.dialect.sql.Table;
import io.vidocq.mansart.jpa.dialect.sql.Update;
import jakarta.persistence.GenerationType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The statements of an entity, built once from its mapping: its columns (basic attributes, embedded ones flattened with
 * their overrides, §2.6), and the insert, update, delete and select by identifier that the persistence context needs.
 * Each statement comes with its parameters: which column each binds, from the current state or from the snapshot (the
 * version a row must still have, §3.4.2). Immutable; rendered by the dialect of the factory.
 *
 * <p>Entities that need a later milestone (inheritance, P6; derived identities and relationship columns, P5) get
 * statements that say so when used, so that the bootstrap never fails for them.
 */
public final class EntityStatements {

    /**
     * A column of the entity table.
     *
     * @param attribute the index of the attribute it belongs to, in {@link EntityModel#attributes()}
     * @param path the indexes, in the nested embeddables, that lead from the attribute to the column; empty for a basic
     *        attribute
     * @param accesses the access of each embeddable along {@code path}
     */
    public record Column(Identifier name, ValueBinder binder, int attribute, int[] path, ManagedAccess[] accesses, boolean id,
            boolean version, boolean insertable, boolean updatable, int table) {
    }

    /**
     * The statements of one table of the entity: the primary table first, then its secondary tables (§11.1.46), whose
     * rows are keyed by the identifier.
     *
     * @param update {@code null} when nothing in the table can change
     * @param selected the indexes, in {@link #columns()}, of the columns the select reads, in its order
     */
    public record TableStatements(Insert insert, List<Parameter> insertParameters, Update update, List<Parameter> updateParameters,
            Delete delete, List<Parameter> deleteParameters, Select select, List<Parameter> selectParameters, int[] selected) {
    }

    /**
     * A parameter of a statement.
     *
     * @param column the index of its column in {@link #columns()}
     * @param previous whether it binds the value of the snapshot rather than the current one
     */
    public record Parameter(int column, boolean previous) {
    }

    private final String entity;
    private final String unsupported;
    private final List<Column> columns;
    private final List<TableStatements> tables;
    private final Integer version;

    private EntityStatements(String entity, String unsupported, List<Column> columns, List<TableStatements> tables, Integer version) {
        this.entity = entity;
        this.unsupported = unsupported;
        this.columns = columns;
        this.tables = tables;
        this.version = version;
    }

    static EntityStatements of(EntityModel model, int[] idAttributes, Function<BasicAttribute, ValueBinder> binders,
            Function<EmbeddableModel, ManagedAccess> embeddables) {
        if (model.superEntity().isPresent()) {
            return unsupported(model, "P6", "entity inheritance");
        }
        List<Integer> ids = Arrays.stream(idAttributes).boxed().toList();
        // a derived identity, single or through an @IdClass: part of the identifier is a relationship column (P5)
        if (ids.stream().anyMatch(i -> !(model.attributes().get(i) instanceof BasicAttribute
                || model.attributes().get(i) instanceof EmbeddedAttribute))) {
            return unsupported(model, "P5", "derived identities");
        }
        Identifier generated = model.id() instanceof IdModel.Single single
            && single.generation().map(g -> g.strategy() == GenerationType.IDENTITY).orElse(false)
            ? Identifier.of(single.attribute().column().name()) : null;
        List<String> tableNames = new ArrayList<>();
        tableNames.add(model.table().name());
        model.secondaryTables().forEach(t -> tableNames.add(t.table().name()));
        List<Column> columns = new ArrayList<>();
        List<AttributeModel> attributes = model.attributes();
        for (int i = 0; i < attributes.size(); i++) {
            boolean id = ids.contains(i);
            switch (attributes.get(i)) {
                case BasicAttribute basic -> columns.add(new Column(Identifier.of(basic.column().name()), binders.apply(basic), i,
                    new int[0], new ManagedAccess[0], id, basic.version(), basic.column().insertable(), basic.column().updatable(),
                    tableIndex(basic.column().table(), tableNames)));
                case EmbeddedAttribute embedded -> flatten(embedded, embedded.embeddable(), "", i, new int[0], new ManagedAccess[0], id,
                    binders, embeddables, tableNames, columns);
                default -> {
                    // relationship columns come with P5, element collections with their own tables (P5)
                }
            }
        }
        for (Column column : columns) {
            if (column.table() < 0) {
                return unsupported(model, "P10", "a column of a table that no @SecondaryTable declares (a mapping file may)");
            }
        }
        List<Integer> keys = new ArrayList<>();
        Integer version = null;
        for (int c = 0; c < columns.size(); c++) {
            if (columns.get(c).id()) {
                keys.add(c);
            }
            if (columns.get(c).version()) {
                version = c;
            }
        }
        if (keys.isEmpty()) {
            return unsupported(model, "P5", "identifiers without a column of their own");
        }
        List<TableStatements> tables = new ArrayList<>();
        for (int t = 0; t < tableNames.size(); t++) {
            TableModel tableModel = t == 0 ? model.table() : model.secondaryTables().get(t - 1).table();
            List<String> joinNames = t == 0 ? List.of() : model.secondaryTables().get(t - 1).joinColumns();
            List<Identifier> keyNames = new ArrayList<>();
            for (int k = 0; k < keys.size(); k++) {
                keyNames.add(joinNames.size() == keys.size() ? Identifier.of(joinNames.get(k)) : columns.get(keys.get(k)).name());
            }
            TableStatements statements = table(table(tableModel), t, columns, keys, keyNames, t == 0 ? version : null, generated);
            if (statements != null) {
                tables.add(statements);
            }
        }
        return new EntityStatements(model.entityName(), null, List.copyOf(columns), List.copyOf(tables), version);
    }

    /** The statements of table {@code t}; {@code null} for a secondary table no column is mapped to. */
    private static TableStatements table(Table table, int t, List<Column> columns, List<Integer> keys, List<Identifier> keyNames,
            Integer version, Identifier generated) {
        List<Parameter> keyParameters = keys.stream().map(c -> new Parameter(c, false)).toList();
        List<Identifier> insertColumns = new ArrayList<>(t == 0 ? List.of() : keyNames);
        List<Parameter> insertParameters = new ArrayList<>(t == 0 ? List.of() : keyParameters);
        List<Identifier> setColumns = new ArrayList<>();
        List<Parameter> setParameters = new ArrayList<>();
        List<Identifier> selectColumns = new ArrayList<>();
        List<Integer> selected = new ArrayList<>();
        for (int c = 0; c < columns.size(); c++) {
            Column column = columns.get(c);
            boolean mine = t == 0 ? column.id() || column.table() == 0 : !column.id() && column.table() == t;
            if (!mine) {
                continue;
            }
            selectColumns.add(column.name());
            selected.add(c);
            if (column.insertable() && !column.name().equals(generated)) {
                insertColumns.add(column.name());
                insertParameters.add(new Parameter(c, false));
            }
            if (!column.id() && column.updatable()) {
                setColumns.add(column.name());
                setParameters.add(new Parameter(c, false));
            }
        }
        if (t > 0 && selected.isEmpty()) {
            return null;
        }
        List<Identifier> conditions = new ArrayList<>(keyNames);
        List<Parameter> conditionParameters = new ArrayList<>(keyParameters);
        if (version != null) {
            conditions.add(columns.get(version).name());
            conditionParameters.add(new Parameter(version, true));
        }
        Update update = null;
        List<Parameter> updateParameters = List.of();
        if (!setColumns.isEmpty()) {
            update = new Update(table, setColumns, conditions);
            List<Parameter> parameters = new ArrayList<>(setParameters);
            parameters.addAll(conditionParameters);
            updateParameters = List.copyOf(parameters);
        }
        return new TableStatements(new Insert(table, insertColumns, t == 0 ? generated : null), List.copyOf(insertParameters), update,
            updateParameters, new Delete(table, conditions), List.copyOf(conditionParameters), new Select(table, selectColumns, keyNames),
            keyParameters, selected.stream().mapToInt(Integer::intValue).toArray());
    }

    /** The table a column belongs to: 0 for the primary one, else its secondary table; -1 if no table has that name. */
    private static int tableIndex(String table, List<String> tableNames) {
        if (table == null || table.isBlank()) {
            return 0;
        }
        for (int t = 0; t < tableNames.size(); t++) {
            if (tableNames.get(t).equalsIgnoreCase(table)) {
                return t;
            }
        }
        return -1;
    }

    private static void flatten(EmbeddedAttribute owner, EmbeddableModel embeddable, String prefix, int attribute, int[] path,
            ManagedAccess[] accesses, boolean id, Function<BasicAttribute, ValueBinder> binders,
            Function<EmbeddableModel, ManagedAccess> embeddableAccess, List<String> tableNames, List<Column> columns) {
        ManagedAccess access = embeddableAccess.apply(embeddable);
        List<AttributeModel> attributes = embeddable.attributes();
        for (int i = 0; i < attributes.size(); i++) {
            int[] nested = Arrays.copyOf(path, path.length + 1);
            nested[path.length] = i;
            ManagedAccess[] along = Arrays.copyOf(accesses, accesses.length + 1);
            along[accesses.length] = access;
            switch (attributes.get(i)) {
                case BasicAttribute basic -> {
                    ColumnModel column = owner.column(prefix + basic.name()).orElse(basic.column());
                    columns.add(new Column(Identifier.of(column.name()), binders.apply(basic), attribute, nested, along, id, false,
                        column.insertable(), column.updatable(), tableIndex(column.table(), tableNames)));
                }
                case EmbeddedAttribute inner -> flatten(owner, inner.embeddable(), prefix + inner.name() + ".", attribute, nested,
                    along, id, binders, embeddableAccess, tableNames, columns);
                default -> {
                    // relationships of embeddables come with P5
                }
            }
        }
    }

    private static Table table(TableModel table) {
        return new Table(Identifier.of(table.name()), table.schema() == null ? null : Identifier.of(table.schema()),
            table.catalog() == null ? null : Identifier.of(table.catalog()));
    }

    private static EntityStatements unsupported(EntityModel model, String milestone, String feature) {
        return new EntityStatements(model.entityName(), milestone + ":" + feature, List.of(), List.of(), null);
    }

    private void check() {
        if (unsupported != null) {
            int colon = unsupported.indexOf(':');
            throw NotYet.milestone(unsupported.substring(0, colon), unsupported.substring(colon + 1) + " (entity " + entity + ")");
        }
    }

    /** The columns of the table, in attribute order. */
    public List<Column> columns() {
        check();
        return columns;
    }

    /** The value of every column, from the state of an instance, in {@link #columns()} order. */
    public Object[] values(Object[] state) {
        check();
        Object[] values = new Object[columns.size()];
        for (int c = 0; c < values.length; c++) {
            Column column = columns.get(c);
            Object value = state[column.attribute()];
            for (int step = 0; step < column.path().length && value != null; step++) {
                value = column.accesses()[step].get(value, column.path()[step]);
            }
            values[c] = value;
        }
        return values;
    }

    /**
     * Writes into {@code state} the attributes a row holds, from its column values in {@link #columns()} order: basic
     * values as read, embeddables built from their columns (a record through its canonical constructor), {@code null}
     * when all their columns are SQL {@code NULL} ({@code nulls}: a primitive component reads 0 from a {@code NULL}).
     * Attributes without columns (relationships, P5) keep what {@code state} holds.
     */
    public void hydrate(Object[] values, boolean[] nulls, Object[] state) {
        check();
        int c = 0;
        while (c < columns.size()) {
            Column column = columns.get(c);
            if (column.path().length == 0) {
                state[column.attribute()] = values[c++];
                continue;
            }
            int end = c;
            while (end < columns.size() && columns.get(end).attribute() == column.attribute()) {
                end++;
            }
            state[column.attribute()] = embeddable(values, nulls, c, end, 0);
            c = end;
        }
    }

    /** The embeddable at {@code depth} whose columns are {@code values[from, to)}; {@code null} if they are all NULL. */
    private Object embeddable(Object[] values, boolean[] nulls, int from, int to, int depth) {
        boolean empty = true;
        for (int c = from; c < to && empty; c++) {
            empty = nulls[c];
        }
        if (empty) {
            return null;
        }
        ManagedAccess access = columns.get(from).accesses()[depth];
        Object[] components = new Object[access.attributes().size()];
        int c = from;
        while (c < to) {
            Column column = columns.get(c);
            int component = column.path()[depth];
            if (column.path().length == depth + 1) {
                components[component] = values[c++];
                continue;
            }
            int end = c;
            while (end < to && columns.get(end).path()[depth] == component) {
                end++;
            }
            components[component] = embeddable(values, nulls, c, end, depth + 1);
            c = end;
        }
        if (access.type().isRecord()) {
            return access.construct(components);
        }
        Object instance = access.instantiate();
        access.write(instance, components);
        return instance;
    }

    /** The values of the key columns of {@code id}: the identifier, or the parts of a {@link CompositeId}. */
    public Object[] keyValues(Object id) {
        check();
        return id instanceof CompositeId composite ? composite.values().toArray() : new Object[] {id};
    }

    public Insert insert() {
        check();
        return tables.getFirst().insert();
    }

    public List<Parameter> insertParameters() {
        check();
        return tables.getFirst().insertParameters();
    }

    /** The update of the attributes that can change; {@code null} if the identifier is the only column. */
    public Update update() {
        check();
        return tables.getFirst().update();
    }

    public List<Parameter> updateParameters() {
        check();
        return tables.getFirst().updateParameters();
    }

    public Delete delete() {
        check();
        return tables.getFirst().delete();
    }

    public List<Parameter> deleteParameters() {
        check();
        return tables.getFirst().deleteParameters();
    }

    public Select select() {
        check();
        return tables.getFirst().select();
    }

    public List<Parameter> selectParameters() {
        check();
        return tables.getFirst().selectParameters();
    }

    /** The statements of each table: the primary table first, then the secondary tables that columns are mapped to. */
    public List<TableStatements> tables() {
        check();
        return tables;
    }

    /** The index of the version column in {@link #columns()} (§3.4.2), if the entity is versioned. */
    public Optional<Integer> version() {
        check();
        return Optional.ofNullable(version);
    }
}
