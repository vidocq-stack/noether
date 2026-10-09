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
import io.vidocq.mansart.jpa.core.model.AssociationAttribute;
import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.BasicAttribute;
import io.vidocq.mansart.jpa.core.model.ColumnModel;
import io.vidocq.mansart.jpa.core.model.EmbeddableModel;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.model.EntityModel;
import io.vidocq.mansart.jpa.core.model.IdModel;
import io.vidocq.mansart.jpa.core.model.JoinColumnModel;
import io.vidocq.mansart.jpa.core.model.JoinTableModel;
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
import jakarta.persistence.PersistenceException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
     * @param foreignKey for a column of an owned single-valued relationship, the part of the target's key it holds;
     *        {@code null} otherwise
     */
    public record Column(Identifier name, ValueBinder binder, int attribute, int[] path, ManagedAccess[] accesses, boolean id,
            boolean version, boolean insertable, boolean updatable, int table, ForeignKey foreignKey) {
    }

    /**
     * What a foreign key column holds: part {@code part} of the key of the instance its relationship references, in
     * the order of {@link #keyValues(Object)} of the target.
     */
    public record ForeignKey(Class<?> target, int part) {
    }

    /**
     * An owned single-valued relationship (§2.10): the attribute and its foreign key columns, in the order of the
     * target's key parts.
     *
     * @param columns the indexes, in {@link #columns()}, of its foreign key columns
     */
    public record Reference(int attribute, Class<?> target, int[] columns) {
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
    private final Function<Class<?>, MappedEntity> entities;
    private final List<Reference> references;
    private final List<CollectionMapping> collections;
    private final List<Column> columns;
    private final List<TableStatements> tables;
    private final Integer version;
    private final Select versionSelect;

    private EntityStatements(String entity, String unsupported, Function<Class<?>, MappedEntity> entities, List<Reference> references,
            List<CollectionMapping> collections, List<Column> columns, List<TableStatements> tables, Integer version,
            Select versionSelect) {
        this.entity = entity;
        this.unsupported = unsupported;
        this.entities = entities;
        this.references = references;
        this.collections = collections;
        this.columns = columns;
        this.tables = tables;
        this.version = version;
        this.versionSelect = versionSelect;
    }

    /**
     * @param models the entity models of the unit, by class: the targets of relationships
     * @param entities the mapped entities of the unit, by class, looked up when values are computed (not before: the
     *        unit is still being mapped when this is called)
     */
    static EntityStatements of(EntityModel model, int[] idAttributes, Function<BasicAttribute, ValueBinder> binders,
            Function<EmbeddableModel, ManagedAccess> embeddables, Function<Class<?>, EntityModel> models,
            Function<Class<?>, MappedEntity> entities) {
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
        List<Reference> references = new ArrayList<>();
        List<CollectionMapping> collections = new ArrayList<>();
        List<AttributeModel> attributes = model.attributes();
        for (int i = 0; i < attributes.size(); i++) {
            boolean id = ids.contains(i);
            switch (attributes.get(i)) {
                case BasicAttribute basic -> columns.add(new Column(Identifier.of(basic.column().name()), binders.apply(basic), i,
                    new int[0], new ManagedAccess[0], id, basic.version(), basic.column().insertable(), basic.column().updatable(),
                    tableIndex(basic.column().table(), tableNames), null));
                case EmbeddedAttribute embedded -> flatten(embedded, embedded.embeddable(), "", i, new int[0], new ManagedAccess[0], id,
                    binders, embeddables, tableNames, columns);
                case AssociationAttribute association when association.singleValued() && association.owning() -> {
                    String problem = foreignKey(association, i, models, binders, tableNames, columns, references);
                    if (problem != null) {
                        return unsupported(model, "P5", problem);
                    }
                }
                case AssociationAttribute association when !association.singleValued() ->
                    collections.add(collection(model, association, i, models, binders));
                default -> {
                    // inverse to-one sides have no column; element collections have their own tables
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
        // the row of the primary table, by identifier: its version, or its key when the entity has no version (§3.5)
        TableStatements primary = tables.getFirst();
        Select versionSelect = new Select(primary.select().table(), List.of(columns.get(version != null ? version : keys.getFirst()).name()),
            primary.select().conditions());
        return new EntityStatements(model.entityName(), null, entities, List.copyOf(references), List.copyOf(collections),
            List.copyOf(columns), List.copyOf(tables), version, versionSelect);
    }

    // ---- foreign keys (§2.10, §11.1.25) -------------------------------------------------------------------

    /** A key column of an entity: its name and binder, in the order of {@link #keyValues(Object)}. */
    private record KeyColumn(String name, ValueBinder binder) {
    }

    /**
     * Adds the foreign key columns of the owned single-valued relationship {@code association}: one per key column of
     * its target, named by its {@code @JoinColumn}s or by default {@code <attribute>_<referenced column>} (§2.10.3.1,
     * §11.1.25). Returns what P5 does not map yet, or {@code null}.
     */
    private static String foreignKey(AssociationAttribute association, int attribute, Function<Class<?>, EntityModel> models,
            Function<BasicAttribute, ValueBinder> binders, List<String> tableNames, List<Column> columns, List<Reference> references) {
        if (association.joinTable() != null) {
            return "a single-valued relationship through a join table";
        }
        EntityModel target = models.apply(association.targetEntity());
        if (target == null) {
            throw new PersistenceException("The relationship " + association.name() + " of " + association.declaringClass().getName()
                + " references " + association.targetEntity().getName() + ", which is not an entity of the persistence unit");
        }
        List<KeyColumn> keys = keyColumns(target, binders);
        if (keys == null) {
            return "a relationship to an entity with a derived identity or a nested embedded identifier";
        }
        JoinColumnModel[] joins = byKeyPart(association, association.joinColumns(), keys, target);
        if (joins == null) {
            return "a join column referencing a column that is not a key column of " + target.entityName();
        }
        int[] indexes = new int[keys.size()];
        for (int part = 0; part < keys.size(); part++) {
            JoinColumnModel join = joins[part];
            String name = join.name() != null ? join.name() : association.name() + "_" + keys.get(part).name();
            indexes[part] = columns.size();
            columns.add(new Column(Identifier.of(name), keys.get(part).binder(), attribute, new int[0], new ManagedAccess[0], false,
                false, join.insertable(), join.updatable(), tableIndex(join.table(), tableNames),
                new ForeignKey(association.targetEntity(), part)));
        }
        references.add(new Reference(attribute, association.targetEntity(), indexes));
        return null;
    }

    /**
     * The join columns {@code written} for a relationship to {@code target}, one per key column of the target in the
     * order of its key parts (§11.1.25: matched by {@code referencedColumnName}, in order when there is one key
     * column); the defaults when none is written. {@code null} when one references a column that is not a key column.
     */
    private static JoinColumnModel[] byKeyPart(AssociationAttribute association, List<JoinColumnModel> written, List<KeyColumn> keys,
            EntityModel target) {
        String relationship = association.name() + " of " + association.declaringClass().getName();
        if (!written.isEmpty() && written.size() != keys.size()) {
            throw new PersistenceException("The relationship " + relationship + " has " + written.size() + " join columns, "
                + target.entityName() + " a key of " + keys.size());
        }
        JoinColumnModel[] joins = new JoinColumnModel[keys.size()];
        for (int k = 0; k < keys.size(); k++) {
            JoinColumnModel join = written.isEmpty() ? JoinColumnModel.defaults() : written.get(k);
            int part = k;
            if (join.referencedColumnName() != null) {
                part = -1;
                for (int candidate = 0; candidate < keys.size(); candidate++) {
                    if (keys.get(candidate).name().equalsIgnoreCase(join.referencedColumnName())) {
                        part = candidate;
                    }
                }
                if (part < 0) {
                    return null;
                }
            } else if (written.size() > 1) {
                throw new PersistenceException("The join columns of " + relationship + " must name their referencedColumnName "
                    + "(§11.1.25)");
            }
            if (joins[part] != null) {
                throw new PersistenceException("Two join columns of " + relationship + " reference " + keys.get(part).name());
            }
            joins[part] = join;
        }
        return joins;
    }

    // ---- collections (§2.10, §11.1.27) --------------------------------------------------------------------

    /**
     * How the collection-valued relationship {@code association} of {@code owner} reaches the database: the inverse side
     * through its target; the owning side of a many-to-many or of a unidirectional one-to-many through a join table, by
     * default {@code <owner table>_<target table>} (§11.1.27), its columns {@code <inverse attribute or owner entity
     * name>_<owner key column>} and {@code <attribute>_<target key column>} (§11.1.25).
     */
    private static CollectionMapping collection(EntityModel owner, AssociationAttribute association, int attribute,
            Function<Class<?>, EntityModel> models, Function<BasicAttribute, ValueBinder> binders) {
        if (Map.class.isAssignableFrom(association.javaType())) {
            return new CollectionMapping.Unsupported(attribute, association, "map collections");
        }
        EntityModel target = models.apply(association.targetEntity());
        if (target == null) {
            throw new PersistenceException("The relationship " + association.name() + " of " + association.declaringClass().getName()
                + " references " + association.targetEntity().getName() + ", which is not an entity of the persistence unit");
        }
        if (!association.owning()) {
            return new CollectionMapping.MappedBy(attribute, association);
        }
        if (association.kind() == AssociationAttribute.Kind.ONE_TO_MANY && association.joinTable() == null
                && !association.joinColumns().isEmpty()) {
            return new CollectionMapping.Unsupported(attribute, association, "a unidirectional one-to-many through a foreign key");
        }
        List<KeyColumn> ownerKeys = keyColumns(owner, binders);
        List<KeyColumn> targetKeys = keyColumns(target, binders);
        if (ownerKeys == null || targetKeys == null) {
            return new CollectionMapping.Unsupported(attribute, association, "a join table of an entity with a derived identity");
        }
        JoinTableModel written = association.joinTable() != null ? association.joinTable() : JoinTableModel.defaults();
        JoinColumnModel[] ownerJoins = byKeyPart(association, written.joinColumns(), ownerKeys, owner);
        JoinColumnModel[] targetJoins = byKeyPart(association, written.inverseJoinColumns(), targetKeys, target);
        if (ownerJoins == null || targetJoins == null) {
            return new CollectionMapping.Unsupported(attribute, association, "a join column referencing a column that is not a key");
        }
        String ownerPrefix = inverseOf(owner, association, target).orElse(owner.entityName());
        List<Identifier> ownerColumns = new ArrayList<>();
        List<ValueBinder> ownerBinders = new ArrayList<>();
        for (int k = 0; k < ownerKeys.size(); k++) {
            String name = ownerJoins[k].name();
            ownerColumns.add(Identifier.of(name != null ? name : ownerPrefix + "_" + ownerKeys.get(k).name()));
            ownerBinders.add(ownerKeys.get(k).binder());
        }
        List<Identifier> targetColumns = new ArrayList<>();
        List<ValueBinder> targetBinders = new ArrayList<>();
        for (int k = 0; k < targetKeys.size(); k++) {
            String name = targetJoins[k].name();
            targetColumns.add(Identifier.of(name != null ? name : association.name() + "_" + targetKeys.get(k).name()));
            targetBinders.add(targetKeys.get(k).binder());
        }
        String tableName = written.name() != null ? written.name() : owner.table().name() + "_" + target.table().name();
        Table table = new Table(Identifier.of(tableName), written.schema() == null ? null : Identifier.of(written.schema()),
            written.catalog() == null ? null : Identifier.of(written.catalog()));
        return new CollectionMapping.JoinTable(attribute, association, table, ownerColumns, ownerBinders, targetColumns,
            targetBinders);
    }

    /** The attribute of {@code target} that is the inverse side of {@code association} of {@code owner}, if any. */
    private static Optional<String> inverseOf(EntityModel owner, AssociationAttribute association, EntityModel target) {
        return target.attributes().stream()
            .filter(a -> a instanceof AssociationAttribute inverse && association.name().equals(inverse.mappedBy())
                && inverse.targetEntity().isAssignableFrom(owner.javaType()))
            .map(AttributeModel::name).findFirst();
    }

    /**
     * The key columns of {@code entity}, in the order of its key parts: its {@code @Id}, the attributes of its
     * {@code @EmbeddedId}, or its {@code @IdClass} parts; {@code null} for keys P5 does not reference yet.
     */
    private static List<KeyColumn> keyColumns(EntityModel entity, Function<BasicAttribute, ValueBinder> binders) {
        return switch (entity.id()) {
            case IdModel.Single single -> List.of(new KeyColumn(single.attribute().column().name(), binders.apply(single.attribute())));
            case IdModel.Embedded embedded -> {
                List<KeyColumn> keys = new ArrayList<>();
                for (AttributeModel part : embedded.attribute().embeddable().attributes()) {
                    if (!(part instanceof BasicAttribute basic)) {
                        yield null;
                    }
                    ColumnModel column = embedded.attribute().column(basic.name()).orElse(basic.column());
                    keys.add(new KeyColumn(column.name(), binders.apply(basic)));
                }
                yield keys;
            }
            case IdModel.ByIdClass byIdClass -> {
                List<KeyColumn> keys = new ArrayList<>();
                for (AttributeModel part : byIdClass.attributes()) {
                    if (!(part instanceof BasicAttribute basic)) {
                        yield null;
                    }
                    keys.add(new KeyColumn(basic.column().name(), binders.apply(basic)));
                }
                yield keys;
            }
            case IdModel.Derived _ -> null;
        };
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
                        column.insertable(), column.updatable(), tableIndex(column.table(), tableNames), null));
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
        return new EntityStatements(model.entityName(), milestone + ":" + feature, null, List.of(), List.of(), List.of(), List.of(), null,
            null);
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
            if (column.foreignKey() != null) {
                values[c] = value == null ? null : keyPart(value, column.foreignKey());
                continue;
            }
            for (int step = 0; step < column.path().length && value != null; step++) {
                value = column.accesses()[step].get(value, column.path()[step]);
            }
            values[c] = value;
        }
        return values;
    }

    /** The part of the key of {@code target} a foreign key column holds; {@code null} while the key is not assigned. */
    private Object keyPart(Object target, ForeignKey foreignKey) {
        MappedEntity type = entities.apply(target.getClass());
        if (type == null) {
            type = entities.apply(foreignKey.target());
        }
        Object id = type.id(target);
        return id == null ? null : type.statements().keyValues(id)[foreignKey.part()];
    }

    /** The collection-valued relationships. */
    public List<CollectionMapping> collections() {
        check();
        return collections;
    }

    /** The mapping of the collection-valued relationship {@code attribute}, if it is one. */
    public Optional<CollectionMapping> collection(int attribute) {
        check();
        return collections.stream().filter(c -> c.attribute() == attribute).findFirst();
    }

    /**
     * The values of the key columns of {@code element}, an instance of entity {@code target} or of a subclass;
     * {@code null} while its identifier is not assigned.
     */
    public Object[] targetKey(Object element, Class<?> target) {
        check();
        MappedEntity type = entities.apply(element.getClass());
        if (type == null) {
            type = entities.apply(target);
        }
        Object id = type.id(element);
        return id == null ? null : type.statements().keyValues(id);
    }

    /** The owned single-valued relationships, with their foreign key columns. */
    public List<Reference> references() {
        check();
        return references;
    }

    /**
     * The identity of the instance a reference holds, from the values of its foreign key columns in a row; {@code null}
     * when they are all SQL {@code NULL}.
     */
    public Object referencedKey(Reference reference, Object[] values) {
        check();
        Object[] parts = new Object[reference.columns().length];
        boolean empty = true;
        for (int k = 0; k < parts.length; k++) {
            parts[k] = values[reference.columns()[k]];
            empty &= parts[k] == null;
        }
        if (empty) {
            return null;
        }
        return parts.length == 1 ? parts[0] : CompositeId.of(parts);
    }

    /** The reference whose foreign key columns hold the relationship attribute {@code attribute}, if this side owns it. */
    public Optional<Reference> reference(int attribute) {
        check();
        return references.stream().filter(r -> r.attribute() == attribute).findFirst();
    }

    /**
     * The select of the identifiers of the rows whose foreign key columns of {@code reference} hold given values, in
     * the order of {@link #keyValues(Object)}: how the inverse side of a relationship finds its owners (§2.10). Its
     * parameters are the foreign key columns.
     */
    public Select ownersSelect(Reference reference) {
        check();
        List<Identifier> conditions = new ArrayList<>();
        for (int c : reference.columns()) {
            if (columns.get(c).table() != 0) {
                throw NotYet.milestone("P5", "a foreign key in a secondary table on the inverse side of a relationship (entity "
                    + entity + ")");
            }
            conditions.add(columns.get(c).name());
        }
        TableStatements primary = tables.getFirst();
        return new Select(primary.select().table(), primary.select().conditions(), conditions);
    }

    /** The identity whose key columns hold {@code values}, in the order of {@link #keyValues(Object)}. */
    public Object key(Object[] values) {
        check();
        return values.length == 1 ? values[0] : CompositeId.of(values);
    }

    /** The key columns, in the order of {@link #keyValues(Object)}: the parameters of a select by identifier. */
    public List<Column> keyColumns() {
        check();
        return tables.getFirst().selectParameters().stream().map(p -> columns.get(p.column())).toList();
    }

    /**
     * The update writing the foreign key of {@code reference} alone: the keys of instances inserted later in a flush
     * (a cycle), or the {@code NULL} written before a delete. Its parameters are {@link #referenceUpdateParameters}.
     */
    public Update referenceUpdate(Reference reference) {
        check();
        TableStatements statements = tables.get(columns.get(reference.columns()[0]).table());
        List<Identifier> set = new ArrayList<>();
        for (int c : reference.columns()) {
            set.add(columns.get(c).name());
        }
        return new Update(statements.delete().table(), set, statements.select().conditions());
    }

    /** The parameters of {@link #referenceUpdate(Reference)}: the foreign key columns, then the key. */
    public List<Parameter> referenceUpdateParameters(Reference reference) {
        check();
        List<Parameter> parameters = new ArrayList<>();
        for (int c : reference.columns()) {
            parameters.add(new Parameter(c, false));
        }
        parameters.addAll(tables.get(columns.get(reference.columns()[0]).table()).selectParameters());
        return List.copyOf(parameters);
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
            if (column.foreignKey() != null) {
                c++; // the loader resolves the instance it references
                continue;
            }
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

    /** The select of the version of a row (of its key without version), by identifier: what a lock reads. */
    public Select versionSelect() {
        check();
        return versionSelect;
    }

    /** The index of the version column in {@link #columns()} (§3.4.2), if the entity is versioned. */
    public Optional<Integer> version() {
        check();
        return Optional.ofNullable(version);
    }
}
