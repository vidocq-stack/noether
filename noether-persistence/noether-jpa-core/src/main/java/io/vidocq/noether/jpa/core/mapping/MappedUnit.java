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

import io.vidocq.noether.jpa.core.access.Accesses;
import io.vidocq.noether.jpa.core.jdbc.type.ValueBinder;
import io.vidocq.noether.jpa.core.jdbc.type.ValueBinders;
import io.vidocq.noether.jpa.dialect.sql.Expression;
import io.vidocq.noether.jpa.dialect.sql.Identifier;
import io.vidocq.noether.jpa.dialect.sql.Query;
import io.vidocq.noether.jpa.dialect.sql.SelectStatement;
import io.vidocq.noether.jpa.dialect.sql.SetQuery;
import io.vidocq.noether.jpa.dialect.sql.Table;
import io.vidocq.noether.jpa.core.model.AccessKind;
import io.vidocq.noether.jpa.core.model.AssociationAttribute;
import io.vidocq.noether.jpa.core.model.AttributeModel;
import io.vidocq.noether.jpa.core.model.BasicAttribute;
import io.vidocq.noether.jpa.core.model.CallbackModel;
import io.vidocq.noether.jpa.core.model.CollectionIndex;
import io.vidocq.noether.jpa.core.model.ColumnModel;
import io.vidocq.noether.jpa.core.model.ElementCollectionAttribute;
import io.vidocq.noether.jpa.core.model.EmbeddableModel;
import io.vidocq.noether.jpa.core.model.EmbeddedAttribute;
import io.vidocq.noether.jpa.core.model.EntityModel;
import io.vidocq.noether.jpa.core.model.IdModel;
import io.vidocq.noether.jpa.core.model.PersistenceUnitModel;
import io.vidocq.noether.jpa.core.model.ValueConversion;
import io.vidocq.noether.jpa.core.model.build.EntityModelBuilder;
import io.vidocq.noether.jpa.core.model.build.AccessPlanner;
import io.vidocq.noether.jpa.core.model.build.LazySubclassGenerator;
import io.vidocq.noether.jpa.core.model.build.Types;
import io.vidocq.noether.jpa.core.model.source.ClassFileSource;
import io.vidocq.noether.jpa.core.model.source.ClassInfos;
import io.vidocq.noether.jpa.core.spi.ManagedAccess;
import io.vidocq.noether.jpa.core.spi.ManagedAccessProvider;
import jakarta.persistence.FetchType;
import jakarta.persistence.PersistenceException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.Set;

/**
 * A persistence unit ready to run: its entity model, the generated access of every entity and embeddable, and the
 * binder of every basic attribute. Everything is built when the factory is created, so that a mapping error, a
 * package not opened to Noether or a converter that cannot be instantiated fails {@code createEntityManagerFactory}
 * rather than the first operation that needs it. Immutable once built, shared by every entity manager of the factory.
 */
public final class MappedUnit {

    private static final System.Logger LOGGER = System.getLogger(MappedUnit.class.getName());

    private final PersistenceUnitModel model;
    private final Map<Class<?>, ManagedAccess> entities;
    private final Map<EmbeddableModel, ManagedAccess> embeddables;
    private final Map<BasicAttribute, ValueBinder> binders;
    private final Map<Class<?>, MappedEntity> mapped;
    private final ClassLoader loader;
    private final ClassInfos source;
    private final Map<Class<?>, InheritanceMapping> inheritance;
    private final Set<AssociationAttribute> lazyReferences;
    private final Map<MappedEntity, Set<Integer>> businessKeyAttributes = new IdentityHashMap<>();

    private MappedUnit(PersistenceUnitModel model, Map<Class<?>, ManagedAccess> entities,
            Map<EmbeddableModel, ManagedAccess> embeddables, Map<BasicAttribute, ValueBinder> binders,
            Map<Class<?>, MappedEntity> mapped, ClassLoader loader, ClassInfos source, Map<Class<?>, InheritanceMapping> inheritance) {
        this.model = model;
        this.loader = loader;
        this.source = source;
        this.entities = entities;
        this.embeddables = embeddables;
        this.binders = binders;
        this.mapped = mapped;
        this.inheritance = inheritance;
        validateBusinessKeys();
        this.lazyReferences = lazyReferences();
    }

    private void validateBusinessKeys() {
        for (MappedEntity owner : mapped.values()) {
            if (!owner.statements().supported()) continue;
            for (var reference : owner.statements().references()) {
                if (!reference.businessKey()) continue;
                var target = mapped.get(reference.target());
                if (!owner.model().secondaryTables().isEmpty() || inheritance.get(owner.model().javaType()).hierarchy().size() != 1) {
                    throw new PersistenceException("Business-key joins do not support owner inheritance or secondary tables");
                }
                var columns = owner.statements().referencedColumns(reference);
                for (int c : reference.columns()) {
                    var column = owner.statements().columns().get(c);
                    if (owner.statements().columns().stream().filter(other -> EntityStatements.sameColumn(other.name(), column.name())).count() != 1) {
                        throw new PersistenceException("Business-key join columns must have distinct, unshared owner column names");
                    }
                }
                businessKeyAttributes.computeIfAbsent(target, _ -> new LinkedHashSet<>())
                    .addAll(columns.stream().map(EntityStatements.Column::attribute).toList());
                boolean unique = columns.size() == 1
                    && ((BasicAttribute) target.model().attributes().get(columns.getFirst().attribute())).column().unique();
                var table = source.read(target.model().javaType().getName()).orElseThrow().annotation("jakarta.persistence.Table").orElse(null);
                if (table != null) {
                    unique |= table.annotations("uniqueConstraints").stream().anyMatch(u -> {
                        var names = u.strings("columnNames").stream().map(this::identifier).toList();
                        return names.size() == columns.size() && columns.stream()
                            .allMatch(c -> names.stream().anyMatch(n -> EntityStatements.sameColumn(n, c.name())));
                    });
                }
                if (!unique) throw new PersistenceException("Business-key join to " + target.model().entityName()
                    + " requires a matching unique constraint on the referenced BASIC columns");
                for (var attribute : target.model().attributes()) {
                    if (attribute instanceof AssociationAttribute inverse && !inverse.singleValued()
                            && inverse.targetEntity() == owner.model().javaType()
                            && inverse.mappedBy() != null && inverse.mappedBy().equals(
                                owner.model().attributes().get(reference.attribute()).name())) {
                        throw new PersistenceException("Business-key joins do not yet support inverse collections");
                    }
                }
            }
        }
    }

    /** Referenced immutable values are protected even against in-memory changes ignored by updatable=false. */
    public boolean businessKeyAttribute(MappedEntity type, int attribute) {
        return businessKeyAttributes.getOrDefault(type, Set.of()).contains(attribute);
    }

    private Set<AssociationAttribute> lazyReferences() {
        Set<AssociationAttribute> supported = new LinkedHashSet<>();
        ClassFileSource classes = new ClassFileSource(loader);
        AccessPlanner planner = new AccessPlanner(classes);
        for (MappedEntity owner : mapped.values()) {
            if (owner.model().javaType().accessFlags().contains(java.lang.reflect.AccessFlag.FINAL)) continue;
            for (AttributeModel attribute : owner.model().attributes()) {
                if (!(attribute instanceof AssociationAttribute association) || !association.singleValued()
                        || !association.owning() || association.fetch() != FetchType.LAZY
                        || association.access() != AccessKind.PROPERTY || association.name().contains(".")
                        || association.mapsId() != null
                        || owner.model().id() instanceof IdModel.Derived derived && derived.relationship().equals(association)) continue;
                if (owner.statements().reference(owner.model().attributes().indexOf(attribute))
                        .map(EntityStatements.Reference::businessKey).orElse(false)) continue;
                var declaring = classes.read(association.declaringClass().getName()).orElseThrow();
                String suffix = Character.toUpperCase(association.name().charAt(0)) + association.name().substring(1);
                boolean getter = declaring.methods().stream().anyMatch(m ->
                    (m.name().equals("get" + suffix) || m.name().equals("get" + association.name()))
                    && m.type().parameterCount() == 0 && !m.isPrivate()
                    && (m.flags() & java.lang.classfile.ClassFile.ACC_FINAL) == 0);
                MappedEntity target = mapped.get(association.targetEntity());
                if (!getter || target == null || !(target.model().id() instanceof IdModel.Single id)
                        || target.model().javaType().isSealed()
                        || !target.model().javaType().getModule().canRead(ManagedAccess.class.getModule())
                        || inheritance.get(association.targetEntity()).hierarchy().size() != 1) continue;
                var info = classes.read(association.targetEntity().getName()).orElseThrow();
                if (!info.isAnnotated("jakarta.persistence.Entity")
                        || info.methods().stream().noneMatch(m -> m.isAnnotated("jakarta.persistence.Id"))) continue;
                var members = planner.entityMembers(info);
                if (!LazySubclassGenerator.supports(info, members)
                        || !Accesses.descriptor(target.model().attributes()).equals(
                            members.stream().map(m -> m.name() + ":" + m.access()).toList())) continue;
                var identifier = members.stream().filter(m -> m.element().isAnnotated("jakarta.persistence.Id"))
                    .findFirst().orElseThrow();
                if (identifier.name().equals(id.attribute().name())) supported.add(association);
            }
        }
        return Set.copyOf(supported);
    }

    /** Whether the effective annotation/XML mapping permits a safely interceptable to-one reference. */
    public boolean lazyReference(AssociationAttribute association) {
        return lazyReferences.contains(association);
    }

    /** Maps the managed classes named {@code classNames}, loaded with {@code loader}. */
    public static MappedUnit of(Collection<String> classNames, ClassLoader loader) {
        return of(classNames, loader, new ClassFileSource(loader), List.of());
    }

    /**
     * Maps the managed classes named {@code classNames}, loaded with {@code loader}.
     *
     * @param source the managed classes as their class files and the unit's mapping files describe them; the classes
     *        its mapping files map are managed besides {@code classNames}
     * @param providers the accesses generated at build time; a managed class without one, or whose generated access
     *        does not list the attributes of the model (a class changed since it was compiled), gets a hidden class
     */
    public static MappedUnit of(Collection<String> classNames, ClassLoader loader, ClassInfos source,
            Iterable<ManagedAccessProvider> providers) {
        PersistenceUnitModel model = EntityModelBuilder.build(classNames, source, loader);
        for (EntityModel entity : model.entities()) {
            validateInverseElementPaths(entity.attributes(), entity.entityName(), model, source);
        }
        validateMapsIds(model);
        Map<Class<?>, InheritanceMapping> inheritance = new IdentityHashMap<>();
        for (EntityModel entity : model.entities()) {
            inheritance.put(entity.javaType(), new InheritanceMapping(entity, model, source));
        }
        Generated generated = new Generated(providers);
        ValueBinders valueBinders = new ValueBinders(loader);
        Map<Class<?>, ManagedAccess> entities = new IdentityHashMap<>();
        Map<EmbeddableModel, ManagedAccess> embeddables = new IdentityHashMap<>();
        Map<BasicAttribute, ValueBinder> binders = new IdentityHashMap<>();
        for (EntityModel entity : model.entities()) {
            List<String> callbacks = entity.callbacks().stream().map(CallbackModel::descriptor).toList();
            entities.put(entity.javaType(), generated.find(entity.javaType(), entity.attributes(), callbacks)
                .orElseGet(() -> Accesses.of(entity)));
            prepare(entity.attributes(), valueBinders, generated, embeddables, binders);
        }
        Map<Class<?>, EmbeddedPaths.Plan> plans = new IdentityHashMap<>();
        for (EntityModel entity : model.entities()) {
            plans.put(entity.javaType(), EmbeddedPaths.of(entity, entities.get(entity.javaType()), embeddables::get));
        }
        Map<Class<?>, Integer> ranks = ranks(new PersistenceUnitModel(
            model.entities().stream().map(e -> plans.get(e.javaType()).model()).toList(), model.converters()));
        Map<Class<?>, MappedEntity> mapped = new IdentityHashMap<>();
        for (EntityModel entity : model.entities()) {
            EmbeddedPaths.Plan plan = plans.get(entity.javaType());
            ManagedAccess embeddedId = entity.id() instanceof IdModel.Embedded embedded
                ? embeddables.get(embedded.attribute().embeddable()) : null;
            ManagedAccess idClass = entity.id() instanceof IdModel.ByIdClass byIdClass ? idClassAccess(byIdClass, loader) : null;
            StatePolicy state = StatePolicy.of(plan.model().attributes(), embeddables::get, valueBinders);
            mapped.put(entity.javaType(), new MappedEntity(plan.model(), plan.access(), root(model, entity), embeddedId,
                idClass, state, m -> EntityStatements.of(m, MappedEntity.idIndexes(m), binders::get, embeddables::get,
                    target -> plans.containsKey(target) ? plans.get(target).model() : null,
                    c -> mapped.get(entityClass(c)), inheritance.get(m.javaType()), inheritance::get,
                    source.delimitedIdentifiers()),
                ranks.get(entity.javaType()), c -> mapped.get(entityClass(c))));
        }
        for (EntityModel entity : model.entities()) {
            MappedEntity executable = mapped.get(entity.javaType());
            if (plans.get(entity.javaType()).model() == entity) continue;
            for (CollectionMapping collection : executable.statements().collections()) {
                if (collection.attribute() >= entity.attributes().size() && collection instanceof CollectionMapping.Unsupported unsupported) {
                    throw new PersistenceException("Embedded relationship " + unsupported.association().name()
                        + " of " + entity.javaType().getName() + " is not executable: " + unsupported.feature());
                }
            }
            for (int i = entity.attributes().size(); i < executable.model().attributes().size(); i++) {
                AttributeModel attribute = executable.model().attributes().get(i);
                if (attribute instanceof ElementCollectionAttribute && executable.statements().elementCollection(i).isEmpty()) {
                    throw new PersistenceException("Embedded element collection " + attribute.name() + " of "
                        + entity.javaType().getName() + " has no executable collection-table mapping");
                }
            }
        }
        return new MappedUnit(model, entities, embeddables, binders, mapped, loader, source, inheritance);
    }

    private static void validateInverseElementPaths(List<AttributeModel> attributes, String path,
            PersistenceUnitModel model, ClassInfos source) {
        for (AttributeModel attribute : attributes) {
            String name = path + "." + attribute.name();
            if (attribute instanceof EmbeddedAttribute embedded) {
                validateInverseElementPaths(embedded.embeddable().attributes(), name, model, source);
            } else if (attribute instanceof AssociationAttribute association) {
                EntityModel target = model.entity(association.targetEntity()).orElse(null);
                if (target == null && source.read(association.targetEntity().getName())
                        .map(info -> info.isAnnotated("jakarta.persistence.Embeddable")).orElse(false)) {
                    throw new PersistenceException("The relationship " + name
                        + " targets an embeddable, not an entity (§2.7): reference its containing entity instead");
                }
                if (target == null || association.mappedBy() == null) continue;
                List<AttributeModel> current = target.attributes();
                for (String segment : association.mappedBy().split("\\.")) {
                    AttributeModel step = current.stream().filter(a -> a.name().equals(segment)).findFirst().orElse(null);
                    if (step instanceof ElementCollectionAttribute) {
                        throw new PersistenceException("The relationship " + name + " mappedBy="
                            + association.mappedBy() + " traverses an element collection: Noether does not support"
                            + " inverse collection-table relationships; an element has no entity identity (§2.7–2.8)"
                            + " and a collection path is not a single-valued embedded path (§11.1.31, §11.1.42)");
                    }
                    if (!(step instanceof EmbeddedAttribute embedded)) break;
                    current = embedded.embeddable().attributes();
                }
            }
        }
    }

    private static void validateMapsIds(PersistenceUnitModel unit) {
        for (EntityModel owner : unit.entities()) {
            Set<String> mapped = new LinkedHashSet<>();
            for (AttributeModel attribute : owner.attributes()) {
                if (!(attribute instanceof AssociationAttribute association) || association.mapsId() == null) continue;
                String name = association.mapsId();
                String problem = "Invalid @MapsId relationship " + owner.entityName() + "." + association.name() + ": ";
                if (!association.singleValued() || !association.owning() || association.joinTable() != null) {
                    throw new PersistenceException(problem + "requires an owning to-one foreign key");
                }
                if (name.isEmpty() && association.kind() != AssociationAttribute.Kind.ONE_TO_ONE) {
                    throw new PersistenceException(problem + "a shared whole identifier requires OneToOne (§2.4.2.1)");
                }
                if (!mapped.add(name) || mapped.contains("") && mapped.size() > 1) {
                    throw new PersistenceException(problem + "overlapping identifier mappings");
                }
                EntityModel target = unit.entities().stream().filter(e -> e.javaType() == association.targetEntity())
                    .findFirst().orElseThrow(() -> new PersistenceException(problem + "target is not managed"));
                if (target.attributes().stream().anyMatch(a -> a instanceof AssociationAttribute r && r.mapsId() != null)) {
                    throw new PersistenceException(problem + "chained derived identifiers are not supported");
                }
                if (target.id() instanceof IdModel.Embedded embedded
                        && embedded.attribute().embeddable().attributes().stream().anyMatch(a -> !(a instanceof BasicAttribute))
                        || target.id() instanceof IdModel.ByIdClass byIdClass
                            && byIdClass.attributes().stream().anyMatch(a -> !(a instanceof BasicAttribute))) {
                    throw new PersistenceException(problem + "nested or relationship-valued target identifiers are not supported");
                }
                Class<?> targetId = switch (target.id()) {
                    case IdModel.Single single -> single.attribute().javaType();
                    case IdModel.Embedded embedded -> embedded.attribute().javaType();
                    case IdModel.ByIdClass byIdClass -> byIdClass.idClass();
                    case IdModel.Derived _ -> throw new PersistenceException(problem + "chained derived identifiers are not supported");
                };
                AttributeModel dependent = switch (owner.id()) {
                    case IdModel.Single single when name.isEmpty() && single.generation().isEmpty() -> single.attribute();
                    case IdModel.Embedded embedded when name.isEmpty() -> embedded.attribute();
                    case IdModel.Embedded embedded -> embedded.attribute().embeddable().attributes().stream()
                        .filter(a -> a.name().equals(name)).findFirst()
                        .orElseThrow(() -> new PersistenceException(problem + "no direct embedded identifier attribute " + name));
                    default -> throw new PersistenceException(problem + "requires an assigned simple or embedded identifier");
                };
                if (boxed(dependent.javaType()) != boxed(targetId)) {
                    throw new PersistenceException(problem + "identifier types must match the target identifier type");
                }
                if (association.joinColumns().stream().anyMatch(j -> !j.insertable() || j.table() != null)) {
                    throw new PersistenceException(problem + "requires insertable primary-table join columns");
                }
            }
            if (!mapped.isEmpty() && owner.id() instanceof IdModel.Embedded embedded
                    && embedded.attribute().embeddable().attributes().stream()
                        .anyMatch(a -> !(a instanceof BasicAttribute) && !mapped.contains(a.name()))) {
                throw new PersistenceException("Invalid @MapsId identifier of " + owner.entityName()
                    + ": unmapped nested embedded identifier parts are not supported");
            }
        }
    }

    private static Class<?> boxed(Class<?> type) {
        return type.isPrimitive() ? java.lang.invoke.MethodType.methodType(type).wrap().returnType() : type;
    }

    public InheritanceMapping inheritance(Class<?> type) {
        return inheritance.get(type);
    }

    /** A table-per-class range is a UNION ALL of concrete tables, filtered by SQL outside the union. */
    public Table queryTable(MappedEntity requested) {
        var mapping = inheritance(requested.model().javaType());
        if (!mapping.tablePerClass()) return requested.statements().select().table();
        var hierarchy = mapping.hierarchy().stream().filter(e -> !mapping.abstractEntity(e))
            .map(e -> entity(e.javaType()).orElseThrow()).toList();
        var definitions = new java.util.LinkedHashMap<Identifier, Map.Entry<MappedEntity, RangeColumn>>();
        for (var entity : hierarchy) {
            rangeColumns(entity).forEach((name, column) -> definitions.putIfAbsent(name, Map.entry(entity, column)));
        }
        var names = new ArrayList<>(definitions.keySet());
        var typeColumn = identifier(mapping.typeColumn());
        if (names.contains(typeColumn)) throw new PersistenceException("Reserved table-per-class column " + typeColumn);
        names.add(typeColumn);
        List<Query> operands = new ArrayList<>();
        for (var entity : hierarchy) {
            if (!requested.model().javaType().isAssignableFrom(entity.model().javaType())) continue;
            var columns = rangeColumns(entity);
            List<Expression> selected = new ArrayList<>();
            for (var definition : definitions.entrySet()) {
                var column = columns.get(definition.getKey());
                if (column != null) {
                    selected.add(new Expression.Column("n" + column.table(), column.name()));
                } else {
                    // An empty scalar subquery supplies an exactly typed NULL, including converters, binary and temporal values.
                    var declared = definition.getValue();
                    var table = declared.getKey().statements().tables().get(declared.getValue().table()).select().table();
                    selected.add(new Expression.Subquery(Query.from(new Query.From(table, "z", List.of()))
                        .select(new Expression.Column("z", declared.getValue().name()))
                        .where(new Expression.Literal(false)).build()));
                }
            }
            selected.add(new Expression.Literal(entity.model().entityName()));
            var tables = entity.statements().tables();
            List<Query.Join> joins = new ArrayList<>();
            for (int t = 1; t < tables.size(); t++) {
                Expression on = null;
                for (int k = 0; k < tables.get(t).select().conditions().size(); k++) {
                    var part = new Expression.Binary(
                        new Expression.Column("n" + t, tables.get(t).select().conditions().get(k)), Expression.Operator.EQ,
                        new Expression.Column("n0", tables.getFirst().select().conditions().get(k)));
                    on = on == null ? part : new Expression.Binary(on, Expression.Operator.AND, part);
                }
                joins.add(new Query.Join(Query.Join.Kind.LEFT, tables.get(t).select().table(), "n" + t, on));
            }
            operands.add(new Query(false, selected,
                List.of(new Query.From(tables.getFirst().select().table(), "n0", joins)),
                null, List.of(), null, List.of(), null, null));
        }
        if (operands.isEmpty()) throw new PersistenceException("TABLE_PER_CLASS query has no concrete managed subtype: "
            + requested.model().entityName());
        SelectStatement query = operands.size() == 1 ? operands.getFirst()
            : new SetQuery(operands, java.util.Collections.nCopies(operands.size() - 1,
                new SetQuery.Operation(SetQuery.Operator.UNION, true)), List.of(), null, null);
        return Table.derived(query, names);
    }

    public Identifier queryColumn(MappedEntity entity, EntityStatements.Column column) {
        var inheritance = inheritance(entity.model().javaType());
        if (!inheritance.tablePerClass()) return column.name();
        if (column.table() == 0) {
            var root = mapped.get(inheritance.root().javaType());
            String attribute = entity.model().attributes().get(column.attribute()).name();
            var inherited = root.statements().columns().stream()
                .filter(c -> root.model().attributes().get(c.attribute()).name().equals(attribute)
                    && java.util.Arrays.equals(c.path(), column.path()) && java.util.Objects.equals(c.foreignKey(), column.foreignKey()))
                .findFirst();
            if (inherited.isPresent()) return inherited.get().name();
            var declaring = inheritance.declaringEntity(entity.model().attributes().get(column.attribute()).declaringClass());
            return Identifier.quoted("_n_" + declaring.entityName() + "_" + column.name().name());
        }
        String table = entity.statements().tables().get(column.table()).select().table().name().name();
        return Identifier.quoted("_n_" + table + "_" + column.name().name());
    }

    private record RangeColumn(int table, Identifier name) {}

    private Map<Identifier, RangeColumn> rangeColumns(MappedEntity entity) {
        Map<Identifier, RangeColumn> columns = new java.util.LinkedHashMap<>();
        entity.statements().columns().forEach(c -> columns.put(queryColumn(entity, c), new RangeColumn(c.table(), c.name())));
        for (var owner : mapped.values()) {
            for (var collection : owner.statements().collections()) {
                if (!collection.association().targetEntity().isAssignableFrom(entity.model().javaType())) continue;
                Table table;
                List<Identifier> names = new ArrayList<>();
                if (collection instanceof CollectionMapping.ForeignKey foreign) {
                    table = foreign.table();
                    names.addAll(foreign.ownerColumns());
                    if (foreign.index() != null) names.addAll(foreign.index().columns());
                } else if (collection instanceof CollectionMapping.MappedBy inverse && inverse.indexUpdate() != null) {
                    table = inverse.indexUpdate().table();
                    names.addAll(inverse.index().columns());
                } else continue;
                var target = mapped.get(collection.association().targetEntity());
                var tables = collectionTables(target, table);
                for (int t = 0; t < entity.statements().tables().size(); t++) {
                    if (tables.contains(entity.statements().tables().get(t).select().table())) {
                        int index = t;
                        names.forEach(name -> columns.putIfAbsent(name, new RangeColumn(index, name)));
                    }
                }
            }
        }
        return columns;
    }

    /** The physical tables that contain a collection's target-side foreign key or index. */
    public List<Table> collectionTables(MappedEntity target, Table table) {
        var mapping = inheritance(target.model().javaType());
        if (!mapping.tablePerClass() || mapping.chain().stream()
                .noneMatch(e -> mapped.get(e.javaType()).statements().select().table().equals(table))) return List.of(table);
        return mapping.hierarchy().stream()
            .filter(e -> target.model().javaType().isAssignableFrom(e.javaType()) && !mapping.abstractEntity(e))
            .map(e -> mapped.get(e.javaType()).statements().select().table()).toList();
    }

    /** The identifier policy shared by generated DDL and every provider-generated SQL statement. */
    public io.vidocq.noether.jpa.dialect.sql.Identifier identifier(String name) {
        return name == null || name.isBlank() ? null
            : io.vidocq.noether.jpa.dialect.sql.Identifier.of(name, source.delimitedIdentifiers());
    }

    /** Qualified stored-procedure names use the same unit policy as other database objects (§2.15). */
    public List<io.vidocq.noether.jpa.dialect.sql.Identifier> routineName(String name) {
        return io.vidocq.noether.jpa.dialect.sql.Identifier.qualified(name, source.delimitedIdentifiers());
    }

    /**
     * Orders the entities for inserts (Kahn): an entity after those its owned to-one relationships reference. Entities
     * of a cycle keep the order of the unit, after the others; the flush writes the foreign keys a cycle leaves
     * dangling with an update once the rows exist.
     */
    private static Map<Class<?>, Integer> ranks(PersistenceUnitModel model) {
        Set<Class<?>> entities = new LinkedHashSet<>();
        model.entities().forEach(e -> entities.add(e.javaType()));
        Map<Class<?>, Set<Class<?>>> references = new LinkedHashMap<>();
        for (EntityModel entity : model.entities()) {
            Set<Class<?>> targets = new LinkedHashSet<>();
            for (AttributeModel attribute : entity.attributes()) {
                if (attribute instanceof AssociationAttribute association && association.owning() && association.singleValued()
                        && association.joinTable() == null && entities.contains(association.targetEntity())
                        && association.targetEntity() != entity.javaType()) {
                    targets.add(association.targetEntity());
                }
            }
            references.put(entity.javaType(), targets);
        }
        for (EntityModel entity : model.entities()) {
            for (AttributeModel attribute : entity.attributes()) {
                if (attribute instanceof AssociationAttribute association && association.owning()
                        && association.kind() == AssociationAttribute.Kind.ONE_TO_MANY && association.joinTable() == null
                        && !association.joinColumns().isEmpty() && entities.contains(association.targetEntity())
                        && association.targetEntity() != entity.javaType()) {
                    for (Class<?> candidate : entities) {
                        if (association.targetEntity().isAssignableFrom(candidate) && candidate != entity.javaType()) {
                            references.get(candidate).add(entity.javaType());
                        }
                    }
                }
            }
        }
        Map<Class<?>, Integer> ranks = new IdentityHashMap<>();
        int rank = 0;
        Set<Class<?>> placed = new LinkedHashSet<>();
        boolean progress = true;
        while (placed.size() < references.size() && progress) {
            progress = false;
            for (Map.Entry<Class<?>, Set<Class<?>>> entry : references.entrySet()) {
                if (!placed.contains(entry.getKey()) && placed.containsAll(entry.getValue())) {
                    ranks.put(entry.getKey(), rank++);
                    placed.add(entry.getKey());
                    progress = true;
                }
            }
        }
        for (Class<?> remaining : references.keySet()) {
            if (placed.add(remaining)) {
                ranks.put(remaining, rank++);
            }
        }
        return ranks;
    }

    /**
     * The access of an {@code @IdClass} (§2.4.1): one attribute per identifier attribute of the entity, in the same
     * order, read by field when the id class declares a field of that name, else by property. The part for a
     * relationship of a derived identity has the type the id class declares (the parent's key, §2.4.1.1).
     * {@code null} when a relationship part has no field of its name: the id class is then the parent's, the
     * relationship the whole identifier (§2.4.1.3 example 5a).
     */
    private static ManagedAccess idClassAccess(IdModel.ByIdClass id, ClassLoader loader) {
        ClassFileSource source = new ClassFileSource(loader);
        List<AttributeModel> attributes = new ArrayList<>();
        for (AttributeModel part : id.attributes()) {
            Class<?> owner = fieldOwner(id.idClass(), part.name(), source);
            Class<?> type = part.javaType();
            if (part instanceof AssociationAttribute) {
                if (owner == null) {
                    return null;
                }
                type = source.read(owner.getName()).flatMap(info -> info.field(part.name()))
                    .map(field -> Types.load(field.type(), loader)).orElseThrow();
            } else if (!(part instanceof BasicAttribute)) {
                return null;
            }
            attributes.add(new BasicAttribute(part.name(), type, owner != null ? AccessKind.FIELD : AccessKind.PROPERTY,
                owner != null ? owner : id.idClass(), ColumnModel.defaultFor(part.name()), true, FetchType.EAGER, false,
                new ValueConversion.None(), false));
        }
        return Accesses.of(id.idClass(), id.idClass().isRecord(), attributes);
    }

    /** The class of {@code type}'s hierarchy that declares the field {@code name}, or {@code null}. */
    private static Class<?> fieldOwner(Class<?> type, String name, ClassFileSource source) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            if (source.read(current.getName()).flatMap(info -> info.field(name)).filter(f -> !f.isStatic()).isPresent()) {
                return current;
            }
        }
        return null;
    }

    /** The topmost entity class of the hierarchy of {@code entity}. */
    private static Class<?> root(PersistenceUnitModel model, EntityModel entity) {
        EntityModel current = entity;
        while (current.superEntity().isPresent()) {
            Optional<EntityModel> parent = model.entity(current.superEntity().get());
            if (parent.isEmpty()) {
                return current.superEntity().get();
            }
            current = parent.get();
        }
        return current.javaType();
    }

    /** The accesses generated at build time, by managed class. */
    private static final class Generated {
        private final Map<Class<?>, List<ManagedAccess>> byType = new IdentityHashMap<>();

        Generated(Iterable<ManagedAccessProvider> providers) {
            try {
                for (ManagedAccessProvider provider : providers) {
                    for (ManagedAccess access : provider.accesses()) {
                        byType.computeIfAbsent(access.type(), t -> new ArrayList<>()).add(access);
                    }
                }
            } catch (ServiceConfigurationError e) {
                throw new PersistenceException("An access generated by noether-jpa-processor cannot be loaded: " + e.getMessage(), e);
            }
        }

        Optional<ManagedAccess> find(Class<?> type, List<AttributeModel> attributes, List<String> callbacks) {
            List<ManagedAccess> candidates = byType.getOrDefault(type, List.of());
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            List<String> descriptor = Accesses.descriptor(attributes);
            Optional<ManagedAccess> fitting = candidates.stream()
                .filter(a -> a.attributes().equals(descriptor) && a.callbacks().equals(callbacks)).findFirst();
            if (fitting.isEmpty()) {
                LOGGER.log(System.Logger.Level.WARNING, "The access generated for {0} lists {1} and callbacks {2}, but the "
                    + "unit maps {3} and callbacks {4}: a mapping file (orm.xml) changes its mapping, or it was compiled "
                    + "from another version of the class; Noether generates its access at bootstrap instead (members only "
                    + "the mapping maps need the package opened to io.vidocq.noether.jpa.core)",
                    type.getName(), candidates.getFirst().attributes(), candidates.getFirst().callbacks(), descriptor,
                    callbacks);
            }
            return fitting;
        }
    }

    private static void prepare(List<AttributeModel> attributes, ValueBinders valueBinders, Generated generated,
            Map<EmbeddableModel, ManagedAccess> embeddables, Map<BasicAttribute, ValueBinder> binders) {
        for (AttributeModel attribute : attributes) {
            switch (attribute) {
                case BasicAttribute basic -> binders.put(basic, bind(valueBinders, basic));
                case EmbeddedAttribute embedded -> {
                    EmbeddableModel embeddable = embedded.embeddable();
                    if (!embeddables.containsKey(embeddable)) {
                        embeddables.put(embeddable, generated.find(embeddable.javaType(), embeddable.attributes(), List.of())
                            .orElseGet(() -> Accesses.of(embeddable)));
                        prepare(embeddable.attributes(), valueBinders, generated, embeddables, binders);
                    }
                }
                case ElementCollectionAttribute elements -> {
                    if (elements.element() != null) {
                        prepare(List.of(elements.element()), valueBinders, generated, embeddables, binders);
                    }
                    prepareIndex(elements.index(), valueBinders, generated, embeddables, binders);
                }
                case AssociationAttribute association -> prepareIndex(association.index(), valueBinders, generated, embeddables, binders);
                default -> {
                    // relationships are bound with their target; pending attributes with their mapping file
                }
            }
        }
    }

    /** The binder of the column of a map key or of a list position. */
    private static void prepareIndex(CollectionIndex index, ValueBinders valueBinders, Generated generated,
            Map<EmbeddableModel, ManagedAccess> embeddables, Map<BasicAttribute, ValueBinder> binders) {
        switch (index) {
            case CollectionIndex.ByEmbedded(EmbeddedAttribute key) -> prepare(List.of(key), valueBinders, generated, embeddables, binders);
            case CollectionIndex.ByColumn(BasicAttribute key) -> binders.put(key, bind(valueBinders, key));
            case CollectionIndex.ByPosition(BasicAttribute position) -> binders.put(position, bind(valueBinders, position));
            case null, default -> {
                // no column of its own, or the columns of a key entity: bound with that entity
            }
        }
    }

    private static ValueBinder bind(ValueBinders valueBinders, BasicAttribute attribute) {
        try {
            return valueBinders.of(attribute);
        } catch (PersistenceException e) {
            throw new PersistenceException("The attribute " + attribute.name() + " of " + attribute.declaringClass().getName()
                + " cannot be stored: " + e.getMessage(), e);
        }
    }

    public PersistenceUnitModel model() {
        return model;
    }

    /** The managed classes as the unit's class files and mapping files describe them, the source of its model. */
    public ClassInfos source() {
        return source;
    }

    /** The class loader of the managed classes: the classes a query names (constructor results, enums) come from it. */
    public ClassLoader loader() {
        return loader;
    }

    /** The entity {@code type} as the persistence context and the flush engine use it. */
    public Optional<MappedEntity> entity(Class<?> type) {
        return Optional.ofNullable(mapped.get(entityClass(type)));
    }

    private static Class<?> entityClass(Class<?> type) {
        return io.vidocq.noether.jpa.core.spi.LazyReference.class.isAssignableFrom(type) ? type.getSuperclass() : type;
    }

    /** The access of {@code entity}. */
    public ManagedAccess access(EntityModel entity) {
        return entities.get(entity.javaType());
    }

    /** The access of {@code embeddable}, an embeddable model of this unit. */
    public ManagedAccess access(EmbeddableModel embeddable) {
        return embeddables.get(embeddable);
    }

    /** The binder of {@code attribute}, a basic attribute of this unit. */
    public ValueBinder binder(BasicAttribute attribute) {
        return binders.get(attribute);
    }
}
