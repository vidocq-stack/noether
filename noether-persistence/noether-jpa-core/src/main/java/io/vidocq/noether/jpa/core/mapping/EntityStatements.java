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
package io.vidocq.noether.jpa.core.mapping;

import io.vidocq.noether.jpa.core.jdbc.type.ValueBinder;
import io.vidocq.noether.jpa.core.model.AssociationAttribute;
import io.vidocq.noether.jpa.core.model.AttributeModel;
import io.vidocq.noether.jpa.core.model.BasicAttribute;
import io.vidocq.noether.jpa.core.model.CollectionIndex;
import io.vidocq.noether.jpa.core.model.CollectionTableModel;
import io.vidocq.noether.jpa.core.model.ColumnModel;
import io.vidocq.noether.jpa.core.model.ElementCollectionAttribute;
import io.vidocq.noether.jpa.core.model.EmbeddableModel;
import io.vidocq.noether.jpa.core.model.EmbeddedAttribute;
import io.vidocq.noether.jpa.core.model.EntityModel;
import io.vidocq.noether.jpa.core.model.IdModel;
import io.vidocq.noether.jpa.core.model.JoinColumnModel;
import io.vidocq.noether.jpa.core.model.JoinTableModel;
import io.vidocq.noether.jpa.core.model.SecondaryTableModel;
import io.vidocq.noether.jpa.core.model.TableModel;
import io.vidocq.noether.jpa.core.session.NotYet;
import io.vidocq.noether.jpa.core.spi.ManagedAccess;
import io.vidocq.noether.jpa.dialect.sql.Delete;
import io.vidocq.noether.jpa.dialect.sql.Identifier;
import io.vidocq.noether.jpa.dialect.sql.Insert;
import io.vidocq.noether.jpa.dialect.sql.Select;
import io.vidocq.noether.jpa.dialect.sql.Table;
import io.vidocq.noether.jpa.dialect.sql.Update;
import jakarta.persistence.GenerationType;
import jakarta.persistence.PersistenceException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The statements of an entity, built once from its mapping: its columns (basic attributes, embedded ones flattened with
 * their overrides, §2.6), and the insert, update, delete and select by identifier that the persistence context needs.
 * Each statement comes with its parameters: which column each binds, from the current state or from the snapshot (the
 * version a row must still have, §3.4.2). Immutable; rendered by the dialect of the factory.
 *
 * <p>Mapping shapes that need a later milestone get
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
     * @param foreignKey for a column of an owned single-valued relationship, the referenced target value it holds;
     *        {@code null} otherwise
     */
    public record Column(Identifier name, ValueBinder binder, int attribute, int[] path, ManagedAccess[] accesses, boolean id,
            boolean version, boolean insertable, boolean updatable, int table, ForeignKey foreignKey) {
    }

    /**
     * What a foreign key column holds: part {@code part} of the target identity, or the BASIC target access slot
     * {@code targetAttribute} for a unique business column. A negative slot retains the original ID mapping.
     */
    public record ForeignKey(Class<?> target, int part, int targetAttribute) {
        public ForeignKey(Class<?> target, int part) {
            this(target, part, -1);
        }
    }

    /**
     * An owned single-valued relationship (§2.10): the attribute and its foreign key columns, in the order of the
     * target's identity parts, or in explicit business-join order.
     *
     * @param columns the indexes, in {@link #columns()}, of its foreign key columns
     * @param referencedColumns empty for identity references, otherwise the unique target business tuple
     */
    public record Reference(int attribute, Class<?> target, int[] columns, List<Identifier> referencedColumns) {
        public Reference(int attribute, Class<?> target, int[] columns) {
            this(attribute, target, columns, List.of());
        }

        public boolean businessKey() {
            return !referencedColumns.isEmpty();
        }
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
    private final List<ElementCollectionMapping> elementCollections;
    private final List<Column> columns;
    private final List<TableStatements> tables;
    private final Integer version;
    private final Select versionSelect;
    private final Object discriminatorValue;
    private final Function<Class<?>, InheritanceMapping> inheritances;
    private final RecordIdentifier recordIdentifier;

    private record RecordIdentifier(int attribute, ManagedAccess access, Map<Integer, Integer> scalarColumns) {}

    private EntityStatements(String entity, String unsupported, Function<Class<?>, MappedEntity> entities, List<Reference> references,
            List<CollectionMapping> collections, List<ElementCollectionMapping> elementCollections, List<Column> columns,
            List<TableStatements> tables, Integer version, Select versionSelect, Object discriminatorValue,
            Function<Class<?>, InheritanceMapping> inheritances, RecordIdentifier recordIdentifier) {
        this.entity = entity;
        this.unsupported = unsupported;
        this.entities = entities;
        this.references = references;
        this.collections = collections;
        this.elementCollections = elementCollections;
        this.columns = columns;
        this.tables = tables;
        this.version = version;
        this.versionSelect = versionSelect;
        this.discriminatorValue = discriminatorValue;
        this.inheritances = inheritances;
        this.recordIdentifier = recordIdentifier;
    }

    /**
     * @param models the entity models of the unit, by class: the targets of relationships
     * @param entities the mapped entities of the unit, by class, looked up when values are computed (not before: the
     *        unit is still being mapped when this is called)
     */
    static EntityStatements of(EntityModel model, int[] idAttributes, Function<BasicAttribute, ValueBinder> binders,
            Function<EmbeddableModel, ManagedAccess> embeddables, Function<Class<?>, EntityModel> models,
            Function<Class<?>, MappedEntity> entities) {
        return of(model, idAttributes, binders, embeddables, models, entities, null, _ -> null, false);
    }

    static EntityStatements of(EntityModel model, int[] idAttributes, Function<BasicAttribute, ValueBinder> binders,
            Function<EmbeddableModel, ManagedAccess> embeddables, Function<Class<?>, EntityModel> models,
            Function<Class<?>, MappedEntity> entities, InheritanceMapping inheritance,
            Function<Class<?>, InheritanceMapping> inheritances, boolean delimited) {
        List<Integer> ids = Arrays.stream(idAttributes).boxed().toList();
        // §2.4.1 derived identities: the relationships of the identifier, and those an @MapsId names, hold key columns
        Map<String, Integer> mapsIds = new HashMap<>();
        for (int i = 0; i < model.attributes().size(); i++) {
            if (model.attributes().get(i) instanceof AssociationAttribute association && association.mapsId() != null) {
                mapsIds.put(association.mapsId(), i);
            }
        }
        Identifier generated = model.id() instanceof IdModel.Single single
            && single.generation().map(g -> g.strategy() == GenerationType.IDENTITY).orElse(false)
            ? Identifier.of(single.attribute().column().name(), delimited) : null;
        List<SecondaryTableModel> tableModels = inheritance == null
            ? new ArrayList<>() : new ArrayList<>(inheritance.tables());
        if (inheritance == null) {
            tableModels.add(new SecondaryTableModel(model.table(), List.of()));
            tableModels.addAll(model.secondaryTables());
        }
        List<String> tableNames = tableModels.stream().map(t -> t.table().name()).toList();
        List<Column> columns = new ArrayList<>();
        List<Reference> references = new ArrayList<>();
        List<CollectionMapping> collections = new ArrayList<>();
        List<ElementCollectionMapping> elementCollections = new ArrayList<>();
        List<AttributeModel> attributes = model.attributes();
        for (int i = 0; i < attributes.size(); i++) {
            boolean id = ids.contains(i);
            switch (attributes.get(i)) {
                case BasicAttribute basic when id && mapsIds.containsKey("") -> {
                    // its value is the parent's key, which the columns of the relationship hold
                }
                case BasicAttribute basic -> columns.add(new Column(Identifier.of(basic.column().name(), delimited), binders.apply(basic), i,
                    new int[0], new ManagedAccess[0], id, basic.version(), basic.column().insertable(), basic.column().updatable(),
                    tableIndex(basic.column().table(), tableNames), null));
                case EmbeddedAttribute embedded when id && mapsIds.containsKey("") -> {
                    // the whole identifier is the parent's key
                }
                case EmbeddedAttribute embedded -> flatten(embedded, embedded.embeddable(), "", i, new int[0], new ManagedAccess[0], id,
                    binders, embeddables, tableNames, columns, id ? mapsIds.keySet() : Set.of(), delimited);
                case AssociationAttribute association when association.singleValued() && association.owning() -> {
                    boolean identifier = id || association.mapsId() != null;
                    if (association.joinTable() != null && !identifier) {
                        collections.add(collection(inheritance == null ? model : inheritance.declaringEntity(association.declaringClass()),
                            association, i, models, binders, embeddables, inheritances, delimited));
                        continue;
                    }
                    String problem = foreignKey(association, i, identifier, models, binders, tableNames, columns, references, inheritances, delimited);
                    if (problem != null) {
                        return unsupported(model, "P5", problem);
                    }
                }
                case AssociationAttribute association when !association.singleValued() ->
                    collections.add(collection(inheritance == null ? model : inheritance.declaringEntity(association.declaringClass()),
                        association, i, models, binders, embeddables, inheritances, delimited));
                case ElementCollectionAttribute elements when elements.element() != null ->
                    elementCollection(inheritance == null ? model : inheritance.declaringEntity(elements.declaringClass()), elements, i,
                        models, binders, embeddables, inheritances, delimited).ifPresent(elementCollections::add);
                default -> {
                    // inverse to-one sides have no column; element collections have their own tables
                }
            }
        }
        if (inheritance != null && inheritance.joined()) {
            for (int c = 0; c < columns.size(); c++) {
                Column column = columns.get(c);
                if (column.table() == 0 && !column.id()) {
                    int table = inheritance.tableOf(attributes.get(column.attribute()).declaringClass());
                    columns.set(c, new Column(column.name(), column.binder(), column.attribute(), column.path(), column.accesses(),
                        column.id(), column.version(), column.insertable(), column.updatable(), table, column.foreignKey()));
                }
            }
        }
        if (inheritance != null && inheritance.discriminator() != null) {
            columns.add(new Column(Identifier.of(inheritance.discriminator(), delimited), inheritance.binder(false), -1,
                new int[0], new ManagedAccess[0], false, false, true, false, 0, null));
        }
        for (Column column : columns) {
            if (column.table() < 0) {
                return unsupported(model, "P10", "a column of a table that no @SecondaryTable declares (a mapping file may)");
            }
        }
        Integer version = null;
        for (int c = 0; c < columns.size(); c++) {
            if (columns.get(c).version()) {
                version = c;
            }
        }
        List<Integer> keys = keys(model, columns, references, mapsIds);
        if (keys == null || keys.isEmpty()) {
            return unsupported(model, "P5", "an identifier whose columns are not mapped yet");
        }
        List<TableStatements> tables = new ArrayList<>();
        for (int t = 0; t < tableNames.size(); t++) {
            TableModel tableModel = tableModels.get(t).table();
            List<String> joinNames = tableModels.get(t).joinColumns();
            List<Identifier> keyNames = new ArrayList<>();
            for (int k = 0; k < keys.size(); k++) {
                keyNames.add(joinNames.size() == keys.size() && !joinNames.get(k).isBlank()
                    ? Identifier.of(joinNames.get(k), delimited) : columns.get(keys.get(k)).name());
            }
            if (inheritance != null && inheritance.joined() && t < inheritance.chain().size()) {
                keyNames = inheritance.keyNames(t, keys.stream().map(k -> columns.get(k).name()).toList());
            } else if (inheritance != null && inheritance.joined() && joinNames.isEmpty()) {
                keyNames = inheritance.secondaryKeys(tableModels.get(t), keys.stream().map(k -> columns.get(k).name()).toList());
            }
            TableStatements statements = table(table(tableModel, delimited), t, columns, keys, keyNames, t == 0 ? version : null, generated);
            if (statements != null) {
                tables.add(statements);
            }
        }
        // the row of the primary table, by identifier: its version, or its key when the entity has no version (§3.5)
        TableStatements primary = tables.getFirst();
        Select versionSelect = new Select(primary.select().table(), List.of(columns.get(version != null ? version : keys.getFirst()).name()),
            primary.select().conditions());
        return new EntityStatements(model.entityName(), null, entities, List.copyOf(references), List.copyOf(collections),
            List.copyOf(elementCollections), List.copyOf(columns), List.copyOf(tables), version, versionSelect,
            inheritance == null ? null : inheritance.value(), inheritances,
            recordIdentifier(model, mapsIds, references, columns, embeddables));
    }

    private static RecordIdentifier recordIdentifier(EntityModel model, Map<String, Integer> mapsIds,
            List<Reference> references, List<Column> columns, Function<EmbeddableModel, ManagedAccess> embeddables) {
        if (!(model.id() instanceof IdModel.Embedded embedded) || !embedded.attribute().embeddable().isRecord()
                || mapsIds.isEmpty() || mapsIds.containsKey("")) return null;
        int attribute = model.attributes().indexOf(embedded.attribute());
        Map<Integer, Integer> scalars = new HashMap<>();
        List<AttributeModel> parts = embedded.attribute().embeddable().attributes();
        for (int part = 0; part < parts.size(); part++) {
            if (!(parts.get(part) instanceof BasicAttribute)) continue;
            Integer relationship = mapsIds.get(parts.get(part).name());
            if (relationship == null) {
                scalars.put(part, columnOf(attribute, part, columns));
            } else {
                List<Integer> foreignKey = referenceColumns(relationship, references);
                if (foreignKey == null || foreignKey.size() != 1) {
                    throw new PersistenceException("Record MapsId scalar component " + parts.get(part).name()
                        + " requires one mapped parent key column");
                }
                scalars.put(part, foreignKey.getFirst());
            }
        }
        return new RecordIdentifier(attribute, embeddables.apply(embedded.attribute().embeddable()), Map.copyOf(scalars));
    }

    /**
     * The key columns, as indexes in {@code columns}, in the order of the identity (§2.4): the column of each identifier
     * attribute, of each component of the embedded identifier, and for a part derived from a parent — an {@code @Id}
     * relationship, or a relationship an {@code @MapsId} names — the foreign key columns of the relationship, in the
     * order of the parent's key. {@code null} when one has no columns.
     */
    private static List<Integer> keys(EntityModel model, List<Column> columns, List<Reference> references, Map<String, Integer> mapsIds) {
        List<Integer> keys = new ArrayList<>();
        Integer whole = mapsIds.get("");
        if (whole != null) {
            return referenceColumns(whole, references);
        }
        switch (model.id()) {
            case IdModel.Single single -> keys.add(columnOf(model.attributes().indexOf(single.attribute()), -1, columns));
            case IdModel.Derived derived -> {
                return referenceColumns(model.attributes().indexOf(derived.relationship()), references);
            }
            case IdModel.Embedded embedded -> {
                int attribute = model.attributes().indexOf(embedded.attribute());
                List<AttributeModel> components = embedded.attribute().embeddable().attributes();
                for (int c = 0; c < components.size(); c++) {
                    Integer mapped = mapsIds.get(components.get(c).name());
                    List<Integer> part = mapped != null ? referenceColumns(mapped, references) : List.of(columnOf(attribute, c, columns));
                    if (part == null) {
                        return null;
                    }
                    keys.addAll(part);
                }
            }
            case IdModel.ByIdClass byIdClass -> {
                for (AttributeModel attribute : byIdClass.attributes()) {
                    int index = model.attributes().indexOf(attribute);
                    List<Integer> part = attribute instanceof AssociationAttribute ? referenceColumns(index, references)
                        : List.of(columnOf(index, -1, columns));
                    if (part == null) {
                        return null;
                    }
                    keys.addAll(part);
                }
            }
        }
        return keys.contains(-1) ? null : keys;
    }

    /** The column of attribute {@code attribute} — of its component {@code component} when ≥ 0 — or -1. */
    private static int columnOf(int attribute, int component, List<Column> columns) {
        for (int c = 0; c < columns.size(); c++) {
            Column column = columns.get(c);
            if (column.attribute() == attribute && column.foreignKey() == null
                    && (component < 0 ? column.path().length == 0 : column.path().length == 1 && column.path()[0] == component)) {
                return c;
            }
        }
        return -1;
    }

    /** The foreign key columns of the relationship {@code attribute}, in the order of its target's key; {@code null} if none. */
    private static List<Integer> referenceColumns(int attribute, List<Reference> references) {
        return references.stream().filter(r -> r.attribute() == attribute).findFirst()
            .map(r -> Arrays.stream(r.columns()).boxed().toList()).orElse(null);
    }

    // ---- foreign keys (§2.10, §11.1.25) -------------------------------------------------------------------

    /** A key column of an entity: its name and binder, in the order of {@link #keyValues(Object)}. */
    private record KeyColumn(String name, ValueBinder binder, boolean quoted) {
        KeyColumn(String mappedName, ValueBinder binder) {
            this(Identifier.of(mappedName).name(), binder, Identifier.of(mappedName).quoted());
        }

        Identifier identifier(boolean delimited) {
            return new Identifier(name, quoted || delimited);
        }
    }

    /**
     * Adds the foreign key columns of the owned single-valued relationship {@code association}: one per key column of
     * its target, named by its {@code @JoinColumn}s or by default {@code <attribute>_<referenced column>} (§2.10.3.1,
     * §11.1.25). Returns what P5 does not map yet, or {@code null}.
     */
    private static String foreignKey(AssociationAttribute association, int attribute, boolean id, Function<Class<?>, EntityModel> models,
            Function<BasicAttribute, ValueBinder> binders, List<String> tableNames, List<Column> columns, List<Reference> references,
            Function<Class<?>, InheritanceMapping> inheritances, boolean delimited) {
        if (association.joinTable() != null) {
            return "a single-valued relationship through a join table";
        }
        EntityModel target = models.apply(association.targetEntity());
        if (target == null) {
            throw new PersistenceException("The relationship " + association.name() + " of " + association.declaringClass().getName()
                + " references " + association.targetEntity().getName() + ", which is not an entity of the persistence unit");
        }
        List<KeyColumn> keys = relationalKeys(target, models, binders, inheritances, delimited);
        if (keys == null) {
            if (association.joinColumns().stream().anyMatch(j -> j.referencedColumnName() != null
                    && target.attributes().stream().filter(a -> a instanceof BasicAttribute).map(a -> (BasicAttribute) a)
                        .anyMatch(a -> sameColumn(Identifier.of(a.column().name(), delimited), Identifier.of(j.referencedColumnName(), delimited))))) {
                throw new PersistenceException("Business-key joins do not support derived or nested target identifiers");
            }
            return "a relationship to an entity with a derived identity or a nested embedded identifier";
        }
        boolean business = association.joinColumns().stream().anyMatch(j -> j.referencedColumnName() != null
            && keys.stream().noneMatch(k -> sameColumn(k.identifier(delimited), Identifier.of(j.referencedColumnName(), delimited))));
        if (business) {
            if (id || association.mapsId() != null || association.name().contains(".")
                    || inheritances.apply(target.javaType()) != null
                        && inheritances.apply(target.javaType()).hierarchy().size() != 1) {
                throw new PersistenceException("Business-key join " + describe(association)
                    + " requires a non-derived target without inheritance or embedded relationships");
            }
            List<BasicAttribute> referenced = new ArrayList<>();
            for (JoinColumnModel join : association.joinColumns()) {
                JoinColumnModel first = association.joinColumns().getFirst();
                if (join.nullable() != first.nullable() || join.insertable() != first.insertable()
                        || join.updatable() != first.updatable()) {
                    throw new PersistenceException("Composite business-key join columns require uniform nullability and writeability");
                }
                if (!join.insertable() || !join.updatable()) {
                    throw new PersistenceException("Business-key joins require writable join columns (insertable=true, updatable=true)");
                }
                BasicAttribute basic = target.attributes().stream().filter(a -> a instanceof BasicAttribute)
                    .map(a -> (BasicAttribute) a)
                    .filter(a -> join.referencedColumnName() != null
                        && sameColumn(Identifier.of(a.column().name(), delimited), Identifier.of(join.referencedColumnName(), delimited)))
                    .findFirst().orElseThrow(() -> new PersistenceException("Business-key join " + describe(association)
                        + " must explicitly reference existing BASIC columns"));
                if (basic.column().nullable() || basic.column().updatable() || !basic.column().insertable() || basic.version()
                        || referenced.contains(basic)
                        || keys.stream().anyMatch(k -> sameColumn(k.identifier(delimited), Identifier.of(basic.column().name(), delimited)))) {
                    throw new PersistenceException("Business-key join " + describe(association)
                        + " requires distinct, non-null, immutable non-ID BASIC columns (nullable=false, updatable=false)");
                }
                if (!target.secondaryTables().isEmpty()) {
                    var secondary = target.secondaryTables().stream()
                        .filter(t -> basic.column().table() != null && sameColumn(Identifier.of(t.table().name(), delimited),
                            Identifier.of(basic.column().table(), delimited))).findFirst().orElse(null);
                    if (keys.size() != 1 || secondary == null || secondary.joinColumns().size() > 1
                            || referenced.stream().anyMatch(previous -> !sameColumn(
                                Identifier.of(previous.column().table(), delimited),
                                Identifier.of(basic.column().table(), delimited)))) {
                        throw new PersistenceException("Secondary-table business-key joins require BASIC columns in one declared "
                            + "secondary table and a single-column target primary key");
                    }
                } else if (basic.column().table() != null) {
                    throw new PersistenceException("Business-key column must belong to a declared secondary table");
                }
                if (target.attributes().stream().filter(a -> a instanceof BasicAttribute b
                        && sameColumn(Identifier.of(b.column().name(), delimited), Identifier.of(basic.column().name(), delimited))).count() != 1) {
                    throw new PersistenceException("Business-key referenced columns must resolve to one unambiguous BASIC attribute");
                }
                referenced.add(basic);
            }
            int[] indexes = new int[referenced.size()];
            for (int part = 0; part < referenced.size(); part++) {
                BasicAttribute basic = referenced.get(part);
                JoinColumnModel join = association.joinColumns().get(part);
                if (join.table() != null) throw new PersistenceException(
                    "Business-key joins do not support owner secondary-table join columns; JoinColumn.table is not the target table");
                indexes[part] = columns.size();
                columns.add(new Column(Identifier.of(join.name() == null ? leaf(association.name()) + "_" + basic.column().name()
                    : join.name(), delimited), binders.apply(basic), attribute, new int[0], new ManagedAccess[0], false, false,
                    join.insertable(), join.updatable(), 0,
                    new ForeignKey(association.targetEntity(), part, target.attributes().indexOf(basic))));
            }
            references.add(new Reference(attribute, association.targetEntity(), indexes,
                referenced.stream().map(b -> Identifier.of(b.column().name(), delimited)).toList()));
            return null;
        }
        JoinColumnModel[] joins = byKeyPart(describe(association), association.joinColumns(), keys, target, delimited);
        if (joins == null) {
            return "a join column referencing a column that is not a key column of " + target.entityName();
        }
        int[] indexes = new int[keys.size()];
        for (int part = 0; part < keys.size(); part++) {
            JoinColumnModel join = joins[part];
            String name = join.name() != null ? join.name() : leaf(association.name()) + "_" + keys.get(part).name();
            indexes[part] = columns.size();
            columns.add(new Column(Identifier.of(name, delimited), keys.get(part).binder(), attribute, new int[0], new ManagedAccess[0], id,
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
    private static JoinColumnModel[] byKeyPart(String relationship, List<JoinColumnModel> written, List<KeyColumn> keys,
            EntityModel target, boolean delimited) {
        if (!written.isEmpty() && written.size() != keys.size()) {
            throw new PersistenceException("The join columns of " + relationship + " are " + written.size() + ", the key of "
                + target.entityName() + " has " + keys.size() + " columns");
        }
        JoinColumnModel[] joins = new JoinColumnModel[keys.size()];
        for (int k = 0; k < keys.size(); k++) {
            JoinColumnModel join = written.isEmpty() ? JoinColumnModel.defaults() : written.get(k);
            int part = k;
            if (join.referencedColumnName() != null) {
                part = -1;
                for (int candidate = 0; candidate < keys.size(); candidate++) {
                    Identifier referenced = Identifier.of(join.referencedColumnName(), delimited);
                    KeyColumn key = keys.get(candidate);
                    if (key.quoted() || referenced.quoted() || delimited
                            ? key.name().equals(referenced.name()) : key.name().equalsIgnoreCase(referenced.name())) {
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

    private static String describe(AssociationAttribute association) {
        return "the relationship " + association.name() + " of " + association.declaringClass().getName();
    }

    public static boolean sameColumn(Identifier first, Identifier second) {
        return first.quoted() || second.quoted() ? first.name().equals(second.name())
            : first.name().equalsIgnoreCase(second.name());
    }

    private static String leaf(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    // ---- collections (§2.10, §11.1.27) --------------------------------------------------------------------

    /**
     * How the collection-valued relationship {@code association} of {@code owner} reaches the database: the inverse side
     * through its target; the owning side of a many-to-many or of a unidirectional one-to-many through a join table, by
     * default {@code <owner table>_<target table>} (§11.1.27), its columns {@code <inverse attribute or owner entity
     * name>_<owner key column>} and {@code <attribute>_<target key column>} (§11.1.25).
     */
    private static CollectionMapping collection(EntityModel owner, AssociationAttribute association, int attribute,
            Function<Class<?>, EntityModel> models, Function<BasicAttribute, ValueBinder> binders,
            Function<EmbeddableModel, ManagedAccess> embeddables, Function<Class<?>, InheritanceMapping> inheritances, boolean delimited) {
        EntityModel target = models.apply(association.targetEntity());
        if (target == null) {
            throw new PersistenceException("The relationship " + association.name() + " of " + association.declaringClass().getName()
                + " references " + association.targetEntity().getName() + ", which is not an entity of the persistence unit");
        }
        Indexing indexing = index(leaf(association.name()), association.index(), target, models, binders, embeddables,
            describe(association), inheritances, delimited);
        if (indexing.problem() != null) {
            return new CollectionMapping.Unsupported(attribute, association, indexing.problem());
        }
        IndexMapping index = indexing.index();
        if (!association.owning()) {
            return inverse(association, attribute, target, index, models, binders, inheritances, delimited);
        }
        List<KeyColumn> ownerKeys = relationalKeys(owner, models, binders, inheritances, delimited);
        List<KeyColumn> targetKeys = relationalKeys(target, models, binders, inheritances, delimited);
        if (ownerKeys == null || targetKeys == null) {
            return new CollectionMapping.Unsupported(attribute, association, "a join table of an entity with a derived identity");
        }
        if (association.singleValued() && association.joinTable() != null
                && association.joinTable().inverseJoinColumns().stream().anyMatch(j -> j.referencedColumnName() != null
                    && targetKeys.stream().noneMatch(k -> sameColumn(k.identifier(delimited), Identifier.of(j.referencedColumnName(), delimited))))) {
            throw new PersistenceException("Business-key joins do not support join tables");
        }
        if (association.kind() == AssociationAttribute.Kind.ONE_TO_MANY && association.joinTable() == null
                && !association.joinColumns().isEmpty()) {
            JoinColumnModel[] joins = byKeyPart(describe(association), association.joinColumns(), ownerKeys, owner, delimited);
            if (joins == null) {
                return new CollectionMapping.Unsupported(attribute, association, "a join column referencing a column that is not a key");
            }
            String tableName = joins[0].table();
            if (Arrays.stream(joins).anyMatch(j -> !java.util.Objects.equals(tableName, j.table()))) {
                throw new PersistenceException("The join columns of " + describe(association) + " must belong to one target table");
            }
            TableModel targetTable = primaryTable(target, inheritances);
            List<Identifier> targetNames = targetKeys.stream().map(k -> k.identifier(delimited)).toList();
            if (tableName != null && !tableName.equals(targetTable.name())) {
                InheritanceMapping targetInheritance = inheritances.apply(target.javaType());
                List<SecondaryTableModel> targetTables = targetInheritance == null
                    ? target.secondaryTables() : targetInheritance.tables();
                SecondaryTableModel selected = targetTables.stream()
                    .filter(t -> t.table().name().equals(tableName)).findFirst()
                    .orElseThrow(() -> new PersistenceException("Unknown target table " + tableName + " of " + describe(association)));
                targetTable = selected.table();
                List<Identifier> keyNames = keyColumns(target, binders).stream().map(k -> k.identifier(delimited)).toList();
                if (selected.joinColumns().size() == keyNames.size()) {
                    targetNames = selected.joinColumns().stream().map(n -> Identifier.of(n, delimited)).toList();
                } else if (targetInheritance != null && targetInheritance.joined()) {
                    int t = targetTables.indexOf(selected);
                    targetNames = t < targetInheritance.chain().size()
                        ? targetInheritance.keyNames(t, keyNames)
                        : targetInheritance.secondaryKeys(selected, keyNames);
                }
            }
            List<Identifier> names = new ArrayList<>();
            for (int k = 0; k < ownerKeys.size(); k++) {
                names.add(Identifier.of(joins[k].name() == null
                    ? owner.entityName() + "_" + ownerKeys.get(k).name() : joins[k].name(), delimited));
            }
            return new CollectionMapping.ForeignKey(attribute, association, table(targetTable, delimited), names,
                ownerKeys.stream().map(KeyColumn::binder).toList(),
                targetNames,
                targetKeys.stream().map(KeyColumn::binder).toList(), List.of(joins), index);
        }
        JoinTableModel written = association.joinTable() != null ? association.joinTable() : JoinTableModel.defaults();
        JoinColumnModel[] ownerJoins = byKeyPart(describe(association), written.joinColumns(), ownerKeys, owner, delimited);
        JoinColumnModel[] targetJoins = byKeyPart(describe(association), written.inverseJoinColumns(), targetKeys, target, delimited);
        if (ownerJoins == null || targetJoins == null) {
            return new CollectionMapping.Unsupported(attribute, association, "a join column referencing a column that is not a key");
        }
        String ownerPrefix = inverseOf(owner, association, target).map(EntityStatements::leaf).orElse(owner.entityName());
        List<Identifier> ownerColumns = new ArrayList<>();
        List<ValueBinder> ownerBinders = new ArrayList<>();
        for (int k = 0; k < ownerKeys.size(); k++) {
            String name = ownerJoins[k].name();
            ownerColumns.add(Identifier.of(name != null ? name : ownerPrefix + "_" + ownerKeys.get(k).name(), delimited));
            ownerBinders.add(ownerKeys.get(k).binder());
        }
        List<Identifier> targetColumns = new ArrayList<>();
        List<ValueBinder> targetBinders = new ArrayList<>();
        for (int k = 0; k < targetKeys.size(); k++) {
            String name = targetJoins[k].name();
            targetColumns.add(Identifier.of(name != null ? name : leaf(association.name()) + "_" + targetKeys.get(k).name(), delimited));
            targetBinders.add(targetKeys.get(k).binder());
        }
        String tableName = written.name() != null ? written.name()
            : Identifier.of(primaryTable(owner, inheritances).name()).name() + "_"
                + Identifier.of(primaryTable(target, inheritances).name()).name();
        Table table = new Table(Identifier.of(tableName, delimited), written.schema() == null ? null : Identifier.of(written.schema(), delimited),
            written.catalog() == null ? null : Identifier.of(written.catalog(), delimited));
        return new CollectionMapping.JoinTable(attribute, association, table, ownerColumns, ownerBinders, targetColumns,
            targetBinders, index, List.of(ownerJoins), List.of(targetJoins));
    }

    /**
     * The inverse side {@code association} of a collection-valued relationship. Its index, when it has columns, lives
     * in the table of the target, beside the foreign key of the owning side: this side writes it.
     */
    private static CollectionMapping inverse(AssociationAttribute association, int attribute, EntityModel target, IndexMapping index,
            Function<Class<?>, EntityModel> models, Function<BasicAttribute, ValueBinder> binders,
            Function<Class<?>, InheritanceMapping> inheritances, boolean delimited) {
        if (index == null || !index.stored()) {
            return new CollectionMapping.MappedBy(attribute, association, index, null, null);
        }
        AttributeModel owning = target.attribute(association.mappedBy()).orElse(null);
        if (!(owning instanceof AssociationAttribute side) || !side.singleValued() || side.joinTable() != null) {
            return new CollectionMapping.Unsupported(attribute, association, "an index of an inverse side without a foreign key");
        }
        InheritanceMapping inheritance = inheritances.apply(target.javaType());
        EntityModel declaring = inheritance == null ? target : inheritance.declaringEntity(side.declaringClass());
        List<KeyColumn> targetKeys = relationalKeys(declaring, models, binders, inheritances, delimited);
        if (targetKeys == null) {
            return new CollectionMapping.Unsupported(attribute, association, "an index of an inverse side without a mapped key");
        }
        Update update = new Update(table(primaryTable(declaring, inheritances), delimited), index.columns(),
            targetKeys.stream().map(k -> k.identifier(delimited)).toList());
        return new CollectionMapping.MappedBy(attribute, association, index, update,
            targetKeys.stream().map(KeyColumn::binder).toList());
    }

    /** An index as mapped, or what P5 does not map yet. */
    private record Indexing(IndexMapping index, String problem) {
    }

    /**
     * The index of the collection {@code name}: for a {@code @MapKey}, the attribute of {@code target} it names; for a
     * key or a position, its column; for a key entity, its foreign key columns, by default {@code <attribute>_KEY}
     * (§11.1.35). {@code target} is {@code null} for an element collection.
     */
    private static Indexing index(String name, CollectionIndex index, EntityModel target, Function<Class<?>, EntityModel> models,
            Function<BasicAttribute, ValueBinder> binders, Function<EmbeddableModel, ManagedAccess> embeddables,
            String relationship, Function<Class<?>, InheritanceMapping> inheritances,
            boolean delimited) {
        return switch (index) {
            case null -> new Indexing(null, null);
            case CollectionIndex.Unsupported unsupported -> new Indexing(null, unsupported.feature());
            case CollectionIndex.ByAttribute byAttribute -> {
                if (target == null) {
                    yield new Indexing(null, "a @MapKey on an element collection");
                }
                int key = -1;
                if (!byAttribute.name().isEmpty()) {
                    key = target.attributes().indexOf(target.attribute(byAttribute.name()).orElseThrow(() -> new PersistenceException(
                        "The @MapKey of " + relationship + " names " + byAttribute.name() + ", which " + target.entityName()
                            + " does not have")));
                }
                yield new Indexing(new IndexMapping(index, key, null, List.of(), List.of(), false, false), null);
            }
            case CollectionIndex.ByColumn(BasicAttribute key) -> new Indexing(new IndexMapping(index, -1, null,
                List.of(Identifier.of(key.column().name(), delimited)), List.of(binders.apply(key)), key.column().insertable(),
                key.column().updatable()), null);
            case CollectionIndex.ByEmbedded(EmbeddedAttribute key) -> {
                if (hasRelationships(key.embeddable())) {
                    throw new PersistenceException("The map key of " + relationship
                        + " contains a relationship whose collection-table mapping is not supported");
                }
                List<Column> columns = new ArrayList<>();
                flatten(key, key.embeddable(), "", 0, new int[0], new ManagedAccess[0], false,
                    binders, embeddables, List.of(""), columns, Set.of(), delimited);
                yield new Indexing(new IndexMapping(index, -1, null, columns.stream().map(Column::name).toList(),
                    columns.stream().map(Column::binder).toList(), columns.stream().allMatch(Column::insertable),
                    columns.stream().allMatch(Column::updatable), columns), null);
            }
            case CollectionIndex.ByPosition(BasicAttribute position) -> new Indexing(new IndexMapping(index, -1, null,
                List.of(Identifier.of(position.column().name(), delimited)), List.of(binders.apply(position)), position.column().insertable(),
                position.column().updatable()), null);
            case CollectionIndex.ByEntity(Class<?> entity, List<JoinColumnModel> written) -> {
                EntityModel keyModel = models.apply(entity);
                if (keyModel == null) {
                    throw new PersistenceException("The map key of " + relationship + " is " + entity.getName()
                        + ", which is not an entity of the persistence unit");
                }
                List<KeyColumn> keys = relationalKeys(keyModel, models, binders, inheritances, delimited);
                JoinColumnModel[] joins = keys == null ? null : byKeyPart("the map key of " + relationship, written, keys, keyModel, delimited);
                if (joins == null) {
                    yield new Indexing(null, "a map key entity with a derived identity, or referenced by a column that is not its key");
                }
                List<Identifier> columns = new ArrayList<>();
                for (int k = 0; k < keys.size(); k++) {
                    String column = joins[k].name() != null ? joins[k].name()
                        : keys.size() == 1 ? name + "_KEY" : name + "_KEY_" + keys.get(k).name();
                    columns.add(Identifier.of(column, delimited));
                }
                yield new Indexing(new IndexMapping(index, -1, entity, columns, keys.stream().map(KeyColumn::binder).toList(),
                    joins[0].insertable(), joins[0].updatable()), null);
            }
        };
    }

    /**
     * The collection table of the element collection {@code elements} of {@code owner} (§11.1.8): by default
     * {@code <entity>_<attribute>}, joined by {@code <entity>_<key column>} (§11.1.25); the element columns are those
     * of its basic value or of its embeddable, overrides applied. Empty for an owner whose key P5 does not reference yet.
     */
    private static Optional<ElementCollectionMapping> elementCollection(EntityModel owner, ElementCollectionAttribute elements,
            int attribute, Function<Class<?>, EntityModel> models, Function<BasicAttribute, ValueBinder> binders,
            Function<EmbeddableModel, ManagedAccess> embeddables, Function<Class<?>, InheritanceMapping> inheritances, boolean delimited) {
        boolean relationships = elements.element() instanceof EmbeddedAttribute embedded && hasRelationships(embedded.embeddable());
        String problem = "The element collection " + elements.name() + " of " + owner.javaType().getName()
            + " contains relationships and requires ";
        List<KeyColumn> ownerKeys = relationalKeys(owner, models, binders, inheritances, delimited);
        if (ownerKeys == null) {
            if (relationships) throw new PersistenceException(problem + "a mapped owner key");
            return Optional.empty();
        }
        CollectionTableModel written = elements.table();
        JoinColumnModel[] joins = byKeyPart("the collection table of " + elements.name() + " of " + elements.declaringClass().getName(),
            written.joinColumns(), ownerKeys, owner, delimited);
        if (joins == null) {
            if (relationships) throw new PersistenceException(problem + "collection joins to the owner's key");
            return Optional.empty();
        }
        List<Identifier> ownerColumns = new ArrayList<>();
        List<ValueBinder> ownerBinders = new ArrayList<>();
        for (int k = 0; k < ownerKeys.size(); k++) {
            String name = joins[k].name();
            ownerColumns.add(Identifier.of(name != null ? name : owner.entityName() + "_" + ownerKeys.get(k).name(), delimited));
            ownerBinders.add(ownerKeys.get(k).binder());
        }
        String tableName = written.name() != null ? written.name() : owner.entityName() + "_" + leaf(elements.name());
        Indexing indexing = index(leaf(elements.name()), elements.index(), null, models, binders, embeddables,
            "the element collection " + elements.name() + " of " + elements.declaringClass().getName(), inheritances, delimited);
        if (indexing.problem() != null) {
            if (relationships) throw new PersistenceException(problem + "a supported collection index: " + indexing.problem());
            return Optional.empty();
        }
        List<Column> columns = new ArrayList<>();
        switch (elements.element()) {
            case BasicAttribute basic -> columns.add(new Column(Identifier.of(basic.column().name(), delimited), binders.apply(basic), 0, new int[0],
                new ManagedAccess[0], false, false, basic.column().insertable(), basic.column().updatable(), 0, null));
            case EmbeddedAttribute embedded -> {
                flatten(embedded, embedded.embeddable(), "", 0, new int[0], new ManagedAccess[0], false,
                    binders, embeddables, List.of(tableName), columns, Set.of(), delimited);
                elementReferences(embedded.embeddable(), new int[0], new ManagedAccess[0], columns,
                    models, binders, embeddables, inheritances, delimited, elements.name());
                columns.sort((left, right) -> java.util.Arrays.compare(left.path(), right.path()));
            }
            default -> throw new IllegalStateException("An element is basic or embeddable: " + elements.element());
        }
        Table table = new Table(Identifier.of(tableName, delimited), written.schema() == null ? null : Identifier.of(written.schema(), delimited),
            written.catalog() == null ? null : Identifier.of(written.catalog(), delimited));
        java.util.Set<Identifier> names = new java.util.HashSet<>(ownerColumns);
        for (Identifier name : indexing.index() == null ? List.<Identifier>of() : indexing.index().columns()) {
            if (!names.add(name)) throw new PersistenceException("Duplicate collection column " + name);
        }
        for (Column column : columns) {
            if (!names.add(column.name())) throw new PersistenceException("Duplicate collection column " + column.name());
        }
        return Optional.of(new ElementCollectionMapping(attribute, elements, table, ownerColumns, ownerBinders, indexing.index(), columns));
    }

    private static void elementReferences(EmbeddableModel model, int[] path, ManagedAccess[] accesses, List<Column> columns,
            Function<Class<?>, EntityModel> models, Function<BasicAttribute, ValueBinder> binders,
            Function<EmbeddableModel, ManagedAccess> embeddables, Function<Class<?>, InheritanceMapping> inheritances,
            boolean delimited, String collection) {
        for (int i = 0; i < model.attributes().size(); i++) {
            int[] nested = Arrays.copyOf(path, path.length + 1);
            nested[path.length] = i;
            ManagedAccess[] along = Arrays.copyOf(accesses, accesses.length + 1);
            along[accesses.length] = embeddables.apply(model);
            AttributeModel attribute = model.attributes().get(i);
            if (attribute instanceof EmbeddedAttribute embedded) {
                elementReferences(embedded.embeddable(), nested, along, columns, models, binders,
                    embeddables, inheritances, delimited, collection);
            } else if (attribute instanceof ElementCollectionAttribute) {
                throw new PersistenceException("The element collection " + collection + " cannot contain nested collections");
            } else if (attribute instanceof AssociationAttribute association) {
                String problem = "The element collection " + collection + " relationship " + association.name() + ": ";
                if (!association.singleValued() || !association.owning() || association.joinTable() != null
                        || association.mapsId() != null || association.orphanRemoval()) {
                    throw new PersistenceException(problem
                        + "requires an owning to-one FK without join table, MapsId or orphan removal");
                }
                EntityModel target = models.apply(association.targetEntity());
                InheritanceMapping inheritance = target == null ? null : inheritances.apply(target.javaType());
                if (target == null || !(target.id() instanceof IdModel.Single || target.id() instanceof IdModel.Embedded
                        || target.id() instanceof IdModel.ByIdClass)
                        || inheritance != null && (inheritance.joined() || inheritance.tablePerClass()
                            || inheritance.hierarchy().size() != 1)
                        || !eligibleElementTarget(target, models, inheritances)) {
                    throw new PersistenceException(problem + "requires an eligible primary-key target");
                }
                List<KeyColumn> keys = relationalKeys(target, models, binders, inheritances, delimited);
                JoinColumnModel[] joins = keys == null || keys.isEmpty() ? null
                    : byKeyPart(problem, association.joinColumns(), keys, target, delimited);
                if (joins == null) {
                    throw new PersistenceException(problem + "requires a complete join to its primary key");
                }
                JoinColumnModel first = joins[0];
                for (JoinColumnModel join : joins) {
                    if (join.nullable() != first.nullable() || join.insertable() != first.insertable()
                            || join.updatable() != first.updatable()) {
                        throw new PersistenceException(problem + "requires uniform composite join-column nullability and writeability");
                    }
                    if (!join.insertable() || !join.updatable() || join.table() != null && !join.table().isBlank()) {
                        throw new PersistenceException(problem + "requires writable collection-table join columns");
                    }
                }
                for (int part = 0; part < keys.size(); part++) {
                    JoinColumnModel join = joins[part];
                    String name = join.name() == null ? association.name() + "_" + keys.get(part).name() : join.name();
                    columns.add(new Column(Identifier.of(name, delimited), keys.get(part).binder(), 0, nested, along,
                        false, false, true, true, 0, new ForeignKey(association.targetEntity(), part)));
                }
            }
        }
    }

    private static boolean eligibleElementTarget(EntityModel target, Function<Class<?>, EntityModel> models,
            Function<Class<?>, InheritanceMapping> inheritances) {
        List<AssociationAttribute> mapsIds = target.attributes().stream()
            .filter(AssociationAttribute.class::isInstance).map(AssociationAttribute.class::cast)
            .filter(association -> association.mapsId() != null).toList();
        if (mapsIds.isEmpty()) {
            if (!(target.id() instanceof IdModel.ByIdClass idClass)) return true;
            List<AssociationAttribute> relationships = idClass.attributes().stream()
                .filter(AssociationAttribute.class::isInstance).map(AssociationAttribute.class::cast).toList();
            if (relationships.isEmpty()) return true;
            if (idClass.attributes().stream().anyMatch(attribute ->
                    !(attribute instanceof BasicAttribute || attribute instanceof AssociationAttribute))) return false;
            return relationships.stream().allMatch(derived -> derived.mapsId() == null
                && derived.singleValued() && derived.owning() && derived.joinTable() == null
                && eligibleBasicElementParent(derived, relationships.size() == 1, models, inheritances));
        }
        for (AssociationAttribute derived : mapsIds) {
            if (!derived.singleValued() || !derived.owning() || derived.joinTable() != null) return false;
            if (target.id() instanceof IdModel.Single) {
                if (mapsIds.size() != 1 || !derived.mapsId().isEmpty()
                        || derived.kind() != AssociationAttribute.Kind.ONE_TO_ONE) return false;
            } else if (target.id() instanceof IdModel.Embedded embedded) {
                List<AttributeModel> parts = embedded.attribute().embeddable().attributes();
                EntityModel parent = models.apply(derived.targetEntity());
                Class<?> parentKey = parent == null ? null : switch (parent.id()) {
                    case IdModel.Embedded parentId -> parentId.attribute().javaType();
                    case IdModel.ByIdClass parentId -> parentId.idClass();
                    default -> null;
                };
                if (parts.stream().anyMatch(attribute -> !(attribute instanceof BasicAttribute))) {
                    boolean mappedComponents = parts.stream().allMatch(part -> part instanceof BasicAttribute
                        || part instanceof EmbeddedAttribute nested
                            && nested.embeddable().attributes().stream().allMatch(BasicAttribute.class::isInstance)
                            && mapsIds.stream().anyMatch(mapping -> part.name().equals(mapping.mapsId())));
                    if (derived.mapsId().isEmpty() || !mappedComponents) return false;
                    AttributeModel component = parts.stream().filter(part -> part.name().equals(derived.mapsId()))
                        .findFirst().orElse(null);
                    if (component instanceof EmbeddedAttribute nested) {
                        if (parentKey != nested.javaType()
                                || !eligibleBasicElementParent(derived, false, models, inheritances)) return false;
                        continue;
                    }
                }
                if (derived.mapsId().isEmpty()) {
                    return mapsIds.size() == 1 && derived.kind() == AssociationAttribute.Kind.ONE_TO_ONE
                        && parentKey == embedded.attribute().javaType()
                        && eligibleBasicElementParent(derived, false, models, inheritances);
                }
                if (parts.stream().map(AttributeModel::name).noneMatch(derived.mapsId()::equals)) return false;
            } else {
                return false;
            }
            if (!simpleNonPolymorphicParent(derived, models, inheritances)) return false;
        }
        return true;
    }

    private static boolean simpleNonPolymorphicParent(AssociationAttribute association,
            Function<Class<?>, EntityModel> models, Function<Class<?>, InheritanceMapping> inheritances) {
        EntityModel parent = models.apply(association.targetEntity());
        if (parent == null || !(parent.id() instanceof IdModel.Single)) return false;
        InheritanceMapping inheritance = inheritances.apply(parent.javaType());
        return inheritance == null || !inheritance.joined() && !inheritance.tablePerClass()
            && inheritance.hierarchy().size() == 1;
    }

    private static boolean eligibleBasicElementParent(AssociationAttribute association, boolean singleRelationship,
            Function<Class<?>, EntityModel> models, Function<Class<?>, InheritanceMapping> inheritances) {
        EntityModel parent = models.apply(association.targetEntity());
        if (parent == null) return false;
        boolean basicKey = switch (parent.id()) {
            case IdModel.Single _ -> true;
            case IdModel.Embedded embedded ->
                embedded.attribute().embeddable().attributes().stream().allMatch(BasicAttribute.class::isInstance);
            case IdModel.ByIdClass idClass -> idClass.attributes().stream().allMatch(BasicAttribute.class::isInstance);
            case IdModel.Derived _ -> false;
        };
        if (!basicKey) return false;
        if (!(singleRelationship && parent.id() instanceof IdModel.Single)
                && parent.attributes().stream().anyMatch(attribute ->
                    attribute instanceof AssociationAttribute derived && derived.mapsId() != null)) return false;
        InheritanceMapping inheritance = inheritances.apply(parent.javaType());
        return inheritance == null || !inheritance.joined() && !inheritance.tablePerClass()
            && inheritance.hierarchy().size() == 1;
    }

    private static boolean hasRelationships(EmbeddableModel model) {
        return model.attributes().stream().anyMatch(a -> a instanceof AssociationAttribute
            || a instanceof ElementCollectionAttribute || a instanceof EmbeddedAttribute e && hasRelationships(e.embeddable()));
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
    private static TableModel primaryTable(EntityModel entity, Function<Class<?>, InheritanceMapping> inheritances) {
        InheritanceMapping mapping = inheritances.apply(entity.javaType());
        return mapping == null || mapping.joined() || mapping.tablePerClass() ? entity.table() : mapping.root().table();
    }

    private static List<KeyColumn> relationalKeys(EntityModel entity, Function<Class<?>, EntityModel> models,
            Function<BasicAttribute, ValueBinder> binders,
            Function<Class<?>, InheritanceMapping> inheritances, boolean delimited) {
        List<KeyColumn> keys = switch (entity.id()) {
            case IdModel.ByIdClass idClass -> idClassKeyColumns(idClass, models, binders, delimited);
            case IdModel.Embedded embedded -> embeddedRelationalKeys(entity, embedded.attribute(), models, binders, delimited);
            default -> keyColumns(entity, binders);
        };
        if (keys == null) return null;
        for (AttributeModel attribute : entity.attributes()) {
            if (!(attribute instanceof AssociationAttribute association) || association.mapsId() == null) continue;
            if (!association.mapsId().isEmpty()) continue;
            keys = associationKeyColumns(association, models, binders, delimited);
            if (keys == null) return null;
        }
        InheritanceMapping mapping = inheritances.apply(entity.javaType());
        if (keys == null || mapping == null || !mapping.joined()) {
            return keys;
        }
        List<Identifier> names = mapping.keyNames(mapping.chain().size() - 1,
            keys.stream().map(k -> k.identifier(delimited)).toList());
        List<KeyColumn> relational = new ArrayList<>();
        for (int k = 0; k < keys.size(); k++) {
            relational.add(new KeyColumn(names.get(k).name(), keys.get(k).binder(), names.get(k).quoted()));
        }
        return relational;
    }

    private static List<KeyColumn> embeddedRelationalKeys(EntityModel entity, EmbeddedAttribute embedded,
            Function<Class<?>, EntityModel> models, Function<BasicAttribute, ValueBinder> binders, boolean delimited) {
        List<KeyColumn> keys = new ArrayList<>();
        for (AttributeModel component : embedded.embeddable().attributes()) {
            AssociationAttribute derived = entity.attributes().stream()
                .filter(AssociationAttribute.class::isInstance).map(AssociationAttribute.class::cast)
                .filter(association -> component.name().equals(association.mapsId())).findFirst().orElse(null);
            if (derived != null) {
                List<KeyColumn> parentKeys = associationKeyColumns(derived, models, binders, delimited);
                if (parentKeys == null) return null;
                keys.addAll(parentKeys);
            } else if (component instanceof BasicAttribute basic) {
                ColumnModel column = embedded.column(basic.name()).orElse(basic.column());
                keys.add(new KeyColumn(column.name(), binders.apply(basic)));
            } else {
                return null;
            }
        }
        return keys;
    }

    private static List<KeyColumn> idClassKeyColumns(IdModel.ByIdClass idClass,
            Function<Class<?>, EntityModel> models, Function<BasicAttribute, ValueBinder> binders, boolean delimited) {
        List<KeyColumn> keys = new ArrayList<>();
        for (AttributeModel attribute : idClass.attributes()) {
            if (attribute instanceof BasicAttribute basic) {
                keys.add(new KeyColumn(basic.column().name(), binders.apply(basic)));
            } else if (attribute instanceof AssociationAttribute association) {
                List<KeyColumn> parentKeys = associationKeyColumns(association, models, binders, delimited);
                if (parentKeys == null) return null;
                keys.addAll(parentKeys);
            } else {
                return null;
            }
        }
        return keys;
    }

    private static List<KeyColumn> associationKeyColumns(AssociationAttribute association,
            Function<Class<?>, EntityModel> models, Function<BasicAttribute, ValueBinder> binders, boolean delimited) {
        EntityModel parent = models.apply(association.targetEntity());
        List<KeyColumn> parentKeys = parent == null ? null : keyColumns(parent, binders);
        if (parentKeys == null) return null;
        JoinColumnModel[] joins = byKeyPart(describe(association), association.joinColumns(), parentKeys, parent, delimited);
        if (joins == null) return null;
        List<KeyColumn> keys = new ArrayList<>();
        for (int part = 0; part < parentKeys.size(); part++) {
            String name = joins[part].name() == null
                ? leaf(association.name()) + "_" + parentKeys.get(part).name() : joins[part].name();
            keys.add(new KeyColumn(name, parentKeys.get(part).binder()));
        }
        return keys;
    }

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
        if (selectColumns.isEmpty()) {
            selectColumns.addAll(keyNames);
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

    static void flatten(EmbeddedAttribute owner, EmbeddableModel embeddable, String prefix, int attribute, int[] path,
            ManagedAccess[] accesses, boolean id, Function<BasicAttribute, ValueBinder> binders,
            Function<EmbeddableModel, ManagedAccess> embeddableAccess, List<String> tableNames, List<Column> columns) {
        flatten(owner, embeddable, prefix, attribute, path, accesses, id, binders, embeddableAccess, tableNames, columns, Set.of(), false);
    }

    /** @param skipped the components, of the top embeddable, that have no columns: an {@code @MapsId} maps them */
    private static void flatten(EmbeddedAttribute owner, EmbeddableModel embeddable, String prefix, int attribute, int[] path,
            ManagedAccess[] accesses, boolean id, Function<BasicAttribute, ValueBinder> binders,
            Function<EmbeddableModel, ManagedAccess> embeddableAccess, List<String> tableNames, List<Column> columns,
            Set<String> skipped, boolean delimited) {
        ManagedAccess access = embeddableAccess.apply(embeddable);
        List<AttributeModel> attributes = embeddable.attributes();
        for (int i = 0; i < attributes.size(); i++) {
            if (skipped.contains(attributes.get(i).name())) {
                continue;
            }
            int[] nested = Arrays.copyOf(path, path.length + 1);
            nested[path.length] = i;
            ManagedAccess[] along = Arrays.copyOf(accesses, accesses.length + 1);
            along[accesses.length] = access;
            switch (attributes.get(i)) {
                case BasicAttribute basic -> {
                    ColumnModel column = owner.column(prefix + basic.name()).orElse(basic.column());
                    columns.add(new Column(Identifier.of(column.name(), delimited), binders.apply(basic), attribute, nested, along, id, false,
                        column.insertable(), column.updatable(), tableIndex(column.table(), tableNames), null));
                }
                case EmbeddedAttribute inner -> flatten(owner, inner.embeddable(), prefix + inner.name() + ".", attribute, nested,
                    along, id, binders, embeddableAccess, tableNames, columns, Set.of(), delimited);
                default -> {
                    // Embedded relationships and collections have their own lowered executable state slots.
                }
            }
        }
    }

    private static Table table(TableModel table, boolean delimited) {
        return new Table(Identifier.of(table.name(), delimited), table.schema() == null ? null : Identifier.of(table.schema(), delimited),
            table.catalog() == null ? null : Identifier.of(table.catalog(), delimited));
    }

    private static EntityStatements unsupported(EntityModel model, String milestone, String feature) {
        return new EntityStatements(model.entityName(), milestone + ":" + feature, null, List.of(), List.of(), List.of(), List.of(),
            List.of(), null, null, null, null, null);
    }

    /** Whether the entity's mapping can be read and written: otherwise its statements throw. */
    public boolean supported() {
        return unsupported == null;
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
            Object value = column.attribute() < 0 ? discriminatorValue : state[column.attribute()];
            if (column.foreignKey() != null) {
                values[c] = value == null ? null : referencedPart(value, column.foreignKey());
                continue;
            }
            for (int step = 0; step < column.path().length && value != null; step++) {
                value = column.accesses()[step].get(value, column.path()[step]);
            }
            values[c] = value;
        }
        return values;
    }

    /** The referenced value a foreign key column holds; ID parts may be null before identifier generation. */
    private Object referencedPart(Object target, ForeignKey foreignKey) {
        MappedEntity type = entities.apply(target.getClass());
        if (type == null) {
            type = entities.apply(foreignKey.target());
        }
        if (foreignKey.targetAttribute() >= 0) {
            io.vidocq.noether.jpa.core.spi.LazyReference.initialize(target);
            Object value = type.access().get(target, foreignKey.targetAttribute());
            if (value == null) throw new PersistenceException("A referenced business-key value must not be null");
            return value;
        }
        Object id = type.id(target);
        return id == null ? null : type.statements().keyValues(id)[foreignKey.part()];
    }

    /** The element collections stored in a collection table. */
    public List<ElementCollectionMapping> elementCollections() {
        check();
        return elementCollections;
    }

    /** The mapping of the element collection {@code attribute}, if it is stored. */
    public Optional<ElementCollectionMapping> elementCollection(int attribute) {
        check();
        return elementCollections.stream().filter(c -> c.attribute() == attribute).findFirst();
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

    /** Resolves a target-side mutation table without changing joined or secondary-table mappings. */
    public Table targetTable(Object element, Class<?> target, Table table) {
        var inheritance = inheritances == null ? null : inheritances.apply(target);
        if (inheritance == null || !inheritance.tablePerClass()
                || inheritance.chain().stream().noneMatch(e -> entities.apply(e.javaType()).statements().select().table().equals(table))) {
            return table;
        }
        MappedEntity concrete = entities.apply(element.getClass());
        return concrete == null ? entities.apply(target).statements().select().table() : concrete.statements().select().table();
    }

    /** The owned single-valued relationships, with their foreign key columns. */
    public List<Reference> references() {
        check();
        return references;
    }

    /**
     * The referenced tuple from the foreign-key columns in a row; null when all columns are SQL NULL.
     * A business tuple is deliberately not an entity identity.
     */
    public Object[] referencedValues(Reference reference, Object[] values) {
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
        if (reference.businessKey() && Arrays.stream(parts).anyMatch(Objects::isNull)) {
            throw new PersistenceException("Partially null composite business-key join of " + entity);
        }
        return parts;
    }

    /** The reference whose foreign key columns hold the relationship attribute {@code attribute}, if this side owns it. */
    public Optional<Reference> reference(int attribute) {
        check();
        return references.stream().filter(r -> r.attribute() == attribute).findFirst();
    }

    /** Actual target columns, never an assumption that a foreign key holds JPA identity. */
    public List<Column> referencedColumns(Reference reference) {
        MappedEntity target = entities.apply(reference.target());
        return reference.businessKey() ? reference.referencedColumns().stream()
            .map(n -> target.statements().columns().stream().filter(c -> sameColumn(c.name(), n)).findFirst().orElseThrow()).toList()
            : target.statements().keyColumns();
    }

    /** The physical target table and its PK join, not necessarily the target's primary table. */
    public TableStatements referencedTable(Reference reference) {
        var target = entities.apply(reference.target()).statements();
        return target.tables().get(referencedColumns(reference).getFirst().table());
    }

    public Column referencedColumn(ForeignKey foreignKey) {
        MappedEntity target = entities.apply(foreignKey.target());
        return foreignKey.targetAttribute() < 0 ? target.statements().keyColumns().get(foreignKey.part())
            : target.statements().columns().stream().filter(c -> c.attribute() == foreignKey.targetAttribute()
                && c.foreignKey() == null).findFirst().orElseThrow();
    }

    /**
     * The select of the identifiers of the rows whose foreign key columns of {@code reference} hold given values, in
     * the order of {@link #keyValues(Object)}: how the inverse side of a relationship finds its owners (§2.10). Its
     * parameters are the foreign key columns.
     */
    public Select ownersSelect(Reference reference) {
        check();
        List<Identifier> conditions = new ArrayList<>();
        int table = columns.get(reference.columns()[0]).table();
        for (int c : reference.columns()) {
            if (columns.get(c).table() != table) {
                throw new PersistenceException("Foreign key columns of " + entity + " must belong to one table");
            }
            conditions.add(columns.get(c).name());
        }
        TableStatements owner = tables.get(table);
        return new Select(owner.select().table(), owner.select().conditions(), conditions);
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
        hydrate(columns, values, nulls, state, false, recordIdentifier == null ? -1 : recordIdentifier.attribute());
        if (recordIdentifier != null) {
            // A derived primitive must be present before invoking the record constructor.
            Object[] components = new Object[recordIdentifier.access().attributes().size()];
            recordIdentifier.scalarColumns().forEach((component, column) -> components[component] = values[column]);
            state[recordIdentifier.attribute()] = recordIdentifier.access().construct(components);
        }
    }

    /** {@link #hydrate(Object[], boolean[], Object[])} for any list of columns: an entity's, or a collection table's. */
    static void hydrate(List<Column> columns, Object[] values, boolean[] nulls, Object[] state) {
        hydrate(columns, values, nulls, state, false);
    }

    /** Collection FK values may already be resolved to managed instances before constructing their embeddable. */
    static void hydrate(List<Column> columns, Object[] values, boolean[] nulls, Object[] state, boolean referencesResolved) {
        hydrate(columns, values, nulls, state, referencesResolved, -1);
    }

    private static void hydrate(List<Column> columns, Object[] values, boolean[] nulls, Object[] state,
            boolean referencesResolved, int deferredIdentifier) {
        int c = 0;
        while (c < columns.size()) {
            Column column = columns.get(c);
            if (column.foreignKey() != null && !referencesResolved || column.attribute() < 0
                    || column.attribute() == deferredIdentifier) {
                c++; // relationships and deferred record identifiers are populated separately
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
            state[column.attribute()] = embeddable(columns, values, nulls, c, end, 0);
            c = end;
        }
    }

    /** The embeddable at {@code depth} whose columns are {@code values[from, to)}; {@code null} if they are all NULL. */
    private static Object embeddable(List<Column> columns, Object[] values, boolean[] nulls, int from, int to, int depth) {
        boolean empty = true;
        for (int c = from; c < to && empty; c++) {
            empty = nulls[c];
        }
        if (empty) {
            return null;
        }
        ManagedAccess access = columns.get(from).accesses()[depth];
        Object[] components = new Object[access.attributes().size()];
        boolean[] read = new boolean[components.length];
        int c = from;
        while (c < to) {
            Column column = columns.get(c);
            int component = column.path()[depth];
            read[component] = true;
            if (column.path().length == depth + 1) {
                components[component] = values[c++];
                continue;
            }
            int end = c;
            while (end < to && columns.get(end).path()[depth] == component) {
                end++;
            }
            components[component] = embeddable(columns, values, nulls, c, end, depth + 1);
            c = end;
        }
        if (access.type().isRecord()) {
            return access.construct(components);
        }
        Object instance = access.instantiate();
        for (int component = 0; component < components.length; component++) {
            if (read[component]) { // a component without columns (an @MapsId part) is derived later
                access.set(instance, component, components[component]);
            }
        }
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
