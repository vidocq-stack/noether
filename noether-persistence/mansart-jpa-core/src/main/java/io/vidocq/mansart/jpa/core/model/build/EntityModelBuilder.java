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
package io.vidocq.mansart.jpa.core.model.build;

import io.vidocq.mansart.jpa.core.model.AccessKind;
import io.vidocq.mansart.jpa.core.model.AssociationAttribute;
import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.BasicAttribute;
import io.vidocq.mansart.jpa.core.model.CallbackModel;
import io.vidocq.mansart.jpa.core.model.CollectionIndex;
import io.vidocq.mansart.jpa.core.model.CollectionTableModel;
import io.vidocq.mansart.jpa.core.model.ColumnModel;
import io.vidocq.mansart.jpa.core.model.ConverterModel;
import io.vidocq.mansart.jpa.core.model.ElementCollectionAttribute;
import io.vidocq.mansart.jpa.core.model.EmbeddableModel;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.model.EntityModel;
import io.vidocq.mansart.jpa.core.model.GenerationModel;
import io.vidocq.mansart.jpa.core.model.IdModel;
import io.vidocq.mansart.jpa.core.model.NamedQueryModel;
import io.vidocq.mansart.jpa.core.model.NamedStoredProcedureModel;
import io.vidocq.mansart.jpa.core.model.JoinColumnModel;
import io.vidocq.mansart.jpa.core.model.JoinTableModel;
import io.vidocq.mansart.jpa.core.model.PersistenceUnitModel;
import io.vidocq.mansart.jpa.core.model.SecondaryTableModel;
import io.vidocq.mansart.jpa.core.model.SqlResultSetMappingModel;
import io.vidocq.mansart.jpa.core.model.SequenceGeneratorModel;
import io.vidocq.mansart.jpa.core.model.TableGeneratorModel;
import io.vidocq.mansart.jpa.core.model.TableModel;
import io.vidocq.mansart.jpa.core.model.ValueConversion;
import io.vidocq.mansart.jpa.core.model.build.AccessPlanner.Member;
import io.vidocq.mansart.jpa.core.model.source.Annotated;
import io.vidocq.mansart.jpa.core.model.source.AnnotationInfo;
import io.vidocq.mansart.jpa.core.model.source.ClassFileSource;
import io.vidocq.mansart.jpa.core.model.source.ClassInfos;
import io.vidocq.mansart.jpa.core.model.source.ClassInfo;
import io.vidocq.mansart.jpa.core.model.source.FieldInfo;
import io.vidocq.mansart.jpa.core.model.source.MethodInfo;
import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import jakarta.persistence.GenerationType;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TemporalType;
import java.io.Serializable;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the entity model of a persistence unit from its managed classes (Jakarta Persistence 3.2, chapter 2 and
 * chapter 11), whatever read them: today the class files, through {@link ClassFileSource}. Every mapping error is a
 * {@link PersistenceException} naming the class and the attribute, raised when the factory is created.
 */
public final class EntityModelBuilder {

    private static final String JPA = "jakarta.persistence.";
    private static final String ENTITY = JPA + "Entity";
    private static final String CONVERTER = JPA + "Converter";
    private static final String ID = JPA + "Id";
    private static final String EMBEDDED_ID = JPA + "EmbeddedId";
    private static final String ATTRIBUTE_OVERRIDE = JPA + "AttributeOverride";
    private static final String ATTRIBUTE_OVERRIDES = JPA + "AttributeOverrides";
    private static final Map<String, AssociationAttribute.Kind> ASSOCIATIONS = Map.of(
        JPA + "OneToOne", AssociationAttribute.Kind.ONE_TO_ONE, JPA + "ManyToOne", AssociationAttribute.Kind.MANY_TO_ONE,
        JPA + "OneToMany", AssociationAttribute.Kind.ONE_TO_MANY, JPA + "ManyToMany", AssociationAttribute.Kind.MANY_TO_MANY);

    private final ClassInfos source;
    private final AccessPlanner planner;
    private final ClassLoader loader;
    private final List<ClassInfo> listed = new ArrayList<>();
    private final List<ConverterModel> converters = new ArrayList<>();
    private final Map<String, EmbeddableModel> embeddables = new HashMap<>();

    private EntityModelBuilder(ClassInfos source, ClassLoader loader) {
        this.source = source;
        this.planner = new AccessPlanner(source);
        this.loader = loader;
    }

    /** The model of the managed classes named {@code classNames}, read from their class files with {@code loader}. */
    public static PersistenceUnitModel build(Collection<String> classNames, ClassLoader loader) {
        return build(classNames, new ClassFileSource(loader), loader);
    }

    /**
     * The model of the managed classes named {@code classNames} and of those the mapping files of {@code source} map,
     * as {@code source} describes them, loaded with {@code loader}.
     */
    public static PersistenceUnitModel build(Collection<String> classNames, ClassInfos source, ClassLoader loader) {
        return new EntityModelBuilder(source, loader).build(classNames);
    }

    private PersistenceUnitModel build(Collection<String> classNames) {
        Set<String> names = new LinkedHashSet<>(classNames);
        names.addAll(source.mappedClassNames());
        for (String name : names) {
            listed.add(source.read(name).orElseThrow(() -> new PersistenceException("The managed class " + name
                + " of the persistence unit cannot be found")));
        }
        for (ClassInfo info : listed) {
            if (info.isAnnotated(CONVERTER)) {
                converters.add(converter(info));
            }
        }
        List<EntityModel> entities = new ArrayList<>();
        List<NamedQueryModel> namedQueries = new ArrayList<>();
        List<SqlResultSetMappingModel> resultSetMappings = new ArrayList<>();
        List<NamedStoredProcedureModel> namedStoredProcedures = new ArrayList<>();
        for (ClassInfo info : listed) {
            if (info.isAnnotated(ENTITY)) {
                entities.add(entity(info));
            }
            namedQueries.addAll(namedQueries(info));
            resultSetMappings.addAll(sqlResultSetMappings(info));
            namedStoredProcedures.addAll(namedStoredProcedures(info));
        }
        // §12.2.2: the queries and mappings of the mapping files replace the annotated ones of the same name
        for (ClassInfo unit : mappingFileAnnotations()) {
            replaceByName(namedQueries, namedQueries(unit), NamedQueryModel::name);
            replaceByName(resultSetMappings, sqlResultSetMappings(unit), SqlResultSetMappingModel::name);
            replaceByName(namedStoredProcedures, namedStoredProcedures(unit), NamedStoredProcedureModel::name);
        }
        return new PersistenceUnitModel(entities, converters, namedQueries, resultSetMappings, namedStoredProcedures);
    }

    /** The annotations the mapping files write outside any class, each as the only annotation of a pseudo class. */
    private List<ClassInfo> mappingFileAnnotations() {
        return source.unitAnnotations().stream().map(annotation -> new ClassInfo("<mapping files>", null, List.of(), 0, null, false,
            List.of(annotation), List.of(), List.<MethodInfo>of())).toList();
    }

    private static <T> void replaceByName(List<T> known, List<T> written, java.util.function.Function<T, String> name) {
        for (T value : written) {
            known.removeIf(k -> name.apply(k).equals(name.apply(value)));
            known.add(value);
        }
    }

    private List<NamedStoredProcedureModel> namedStoredProcedures(ClassInfo info) {
        List<AnnotationInfo> declarations = new ArrayList<>();
        info.annotation(JPA + "NamedStoredProcedureQuery").ifPresent(declarations::add);
        info.annotation(JPA + "NamedStoredProcedureQueries").ifPresent(container -> declarations.addAll(container.annotations("value")));
        List<NamedStoredProcedureModel> procedures = new ArrayList<>();
        for (AnnotationInfo declaration : declarations) {
            List<NamedStoredProcedureModel.Parameter> parameters = declaration.annotations("parameters").stream()
                .map(parameter -> new NamedStoredProcedureModel.Parameter(parameter.string("name"),
                    Types.load(parameter.type("type"), loader),
                    jakarta.persistence.ParameterMode.valueOf(parameter.enumConstant("mode"))))
                .toList();
            List<Class<?>> resultClasses = new ArrayList<>();
            declaration.types("resultClasses").forEach(type -> resultClasses.add(Types.load(type, loader)));
            procedures.add(new NamedStoredProcedureModel(declaration.string("name"), declaration.string("procedureName"), parameters,
                resultClasses, declaration.strings("resultSetMappings"), hints(declaration)));
        }
        return procedures;
    }

    private List<SqlResultSetMappingModel> sqlResultSetMappings(ClassInfo info) {
        List<AnnotationInfo> mappings = new ArrayList<>();
        info.annotation(JPA + "SqlResultSetMapping").ifPresent(mappings::add);
        info.annotation(JPA + "SqlResultSetMappings").ifPresent(container -> mappings.addAll(container.annotations("value")));
        List<SqlResultSetMappingModel> result = new ArrayList<>();
        for (AnnotationInfo mapping : mappings) {
            List<SqlResultSetMappingModel.Result> items = new ArrayList<>();
            for (AnnotationInfo entity : mapping.annotations("entities")) {
                List<SqlResultSetMappingModel.FieldResult> fields = entity.annotations("fields").stream()
                    .map(field -> new SqlResultSetMappingModel.FieldResult(field.string("name"), field.string("column"))).toList();
                items.add(new SqlResultSetMappingModel.EntityResult(Types.load(entity.type("entityClass"), loader), fields,
                    entity.string("discriminatorColumn")));
            }
            for (AnnotationInfo constructor : mapping.annotations("classes")) {
                List<SqlResultSetMappingModel.ColumnResult> columns = constructor.annotations("columns").stream()
                    .map(this::columnResult).toList();
                items.add(new SqlResultSetMappingModel.ConstructorResult(Types.load(constructor.type("targetClass"), loader), columns));
            }
            mapping.annotations("columns").stream().map(this::columnResult).forEach(items::add);
            result.add(new SqlResultSetMappingModel(mapping.string("name"), items));
        }
        return result;
    }

    private SqlResultSetMappingModel.ColumnResult columnResult(AnnotationInfo column) {
        ClassDesc type = column.type("type");
        Class<?> resultType = type == null || type.descriptorString().equals("V") ? null : Types.load(type, loader);
        return new SqlResultSetMappingModel.ColumnResult(column.string("name"), resultType);
    }

    // ---- named queries (§10.4.1) -------------------------------------------------------------------------

    /** The {@code @NamedQuery}s and {@code @NamedNativeQuery}s of a managed class, repeated or in their containers. */
    private List<NamedQueryModel> namedQueries(ClassInfo info) {
        List<NamedQueryModel> queries = new ArrayList<>();
        List<AnnotationInfo> jpql = new ArrayList<>();
        info.annotation(JPA + "NamedQuery").ifPresent(jpql::add);
        info.annotation(JPA + "NamedQueries").ifPresent(container -> jpql.addAll(container.annotations("value")));
        for (AnnotationInfo query : jpql) {
            queries.add(new NamedQueryModel(query.string("name"), query.string("query"), false, resultClass(query),
                query.enumConstant("lockMode"), hints(query), null));
        }
        List<AnnotationInfo> sql = new ArrayList<>();
        info.annotation(JPA + "NamedNativeQuery").ifPresent(sql::add);
        info.annotation(JPA + "NamedNativeQueries").ifPresent(container -> sql.addAll(container.annotations("value")));
        for (AnnotationInfo query : sql) {
            queries.add(new NamedQueryModel(query.string("name"), query.string("query"), true, resultClass(query), "NONE", hints(query),
                nonEmpty(query.string("resultSetMapping"))));
        }
        return queries;
    }

    private Class<?> resultClass(AnnotationInfo query) {
        ClassDesc type = query.type("resultClass");
        return type == null || type.descriptorString().equals("V") ? null : Types.load(type, loader);
    }

    private static Map<String, String> hints(AnnotationInfo query) {
        Map<String, String> hints = new LinkedHashMap<>();
        query.annotations("hints").forEach(hint -> hints.put(hint.string("name"), hint.string("value")));
        return hints;
    }

    // ---- converters (§3.9) ------------------------------------------------------------------------------

    private ConverterModel converter(ClassInfo info) {
        List<Class<?>> arguments = GenericSignatures.typeArguments(info.name(), JPA + "AttributeConverter", source, loader);
        if (arguments == null || arguments.size() != 2) {
            throw new PersistenceException("The converter " + info.name() + " must implement AttributeConverter<X, Y> with "
                + "actual type arguments");
        }
        return new ConverterModel(Types.load(info.name(), loader), arguments.get(0), arguments.get(1),
            info.annotation(CONVERTER).orElseThrow().bool("autoApply"));
    }

    // ---- entities (§2.1) --------------------------------------------------------------------------------

    private EntityModel entity(ClassInfo info) {
        checkEntityClass(info);
        Class<?> type = Types.load(info.name(), loader);
        String entityName = nonEmpty(info.annotation(ENTITY).orElseThrow().string("name"));
        if (entityName == null) {
            entityName = simpleName(info.name());
        }
        List<ClassInfo> hierarchy = planner.hierarchy(info);
        AccessKind access = planner.defaultAccess(info, hierarchy);
        Map<String, AnnotationInfo> overrides = overrides(info);
        Map<String, AnnotationInfo> converts = converts(info);
        Map<String, AnnotationInfo> associationOverrides = new LinkedHashMap<>();
        for (ClassInfo declaring : hierarchy) {
            declaring.annotation(JPA + "AssociationOverride").ifPresent(o -> associationOverrides.put(o.string("name"), o));
            declaring.annotation(JPA + "AssociationOverrides").ifPresent(container ->
                container.annotations("value").forEach(o -> associationOverrides.put(o.string("name"), o)));
        }

        List<AttributeModel> attributes = new ArrayList<>();
        Map<AttributeModel, Member> members = new LinkedHashMap<>();
        for (ClassInfo declaring : hierarchy) {
            for (Member member : planner.members(declaring, AccessPlanner.classAccess(declaring, access))) {
                boolean inherited = declaring != info;
                AttributeModel attribute = attribute(member, inherited ? overrides : Map.of(), inherited ? converts : Map.of());
                if (attribute instanceof EmbeddedAttribute embedded) {
                    Map<String, AnnotationInfo> nested = prefixed(associationOverrides, member.name() + ".");
                    attribute = new EmbeddedAttribute(embedded.name(), embedded.javaType(), embedded.access(),
                        embedded.declaringClass(), associationOverrides(embedded.embeddable(), nested), embedded.columns());
                    associationOverrides.keySet().removeIf(path -> path.startsWith(member.name() + "."));
                }
                AnnotationInfo associationOverride = associationOverrides.remove(member.name());
                if (associationOverride != null) {
                    if (!inherited || !declaring.isAnnotated(JPA + "MappedSuperclass")
                            || !(attribute instanceof AssociationAttribute relationship)) {
                        throw new PersistenceException("The @AssociationOverride " + member.name() + " of " + info.name()
                            + " must name a relationship inherited from a mapped superclass (§11.1.2)");
                    }
                    attribute = associationOverride(relationship, associationOverride);
                }
                attributes.add(attribute);
                members.put(attribute, member);
            }
        }
        if (!associationOverrides.isEmpty()) {
            throw new PersistenceException("The @AssociationOverride paths " + associationOverrides.keySet() + " of "
                + info.name() + " are unknown");
        }
        IdModel id = id(info, hierarchy, members);
        Optional<BasicAttribute> version = attributes.stream().<BasicAttribute>mapMulti((attribute, versions) -> {
            if (attribute instanceof BasicAttribute basic && basic.version()) {
                versions.accept(basic);
            }
        }).findFirst();
        Optional<Class<?>> superEntity = hierarchy.stream().filter(c -> c != info && c.isAnnotated(ENTITY))
            .reduce((first, second) -> second).map(c -> Types.load(c.name(), loader));
        return new EntityModel(type, entityName, table(info, entityName), AccessPlanner.classAccess(info, access), id, attributes, version,
            superEntity, secondaryTables(info), callbacks(info),
            info.annotation(JPA + "Cacheable").map(annotation -> annotation.bool("value")).orElse(null));
    }

    private void checkEntityClass(ClassInfo info) {
        if (info.isInterface() || info.isEnum() || info.isRecord()) {
            throw new PersistenceException("The entity " + info.name() + " must be a class, not an interface, an enum or a record (§2.1)");
        }
        if (info.isFinal()) {
            throw new PersistenceException("The entity class " + info.name() + " must not be final (§2.1)");
        }
        boolean noArgConstructor = info.methods().stream().anyMatch(m -> m.name().equals(ConstantDescs.INIT_NAME)
            && m.type().parameterCount() == 0 && (m.flags() & (ClassFile.ACC_PUBLIC | ClassFile.ACC_PROTECTED)) != 0);
        if (!info.isAbstract() && !noArgConstructor) {
            throw new PersistenceException("The entity class " + info.name()
                + " must have a public or protected no-arg constructor (§2.1)");
        }
    }

    /** §3.6: the lifecycle callbacks planned by {@link AccessPlanner}, with their classes loaded. */
    private List<CallbackModel> callbacks(ClassInfo info) {
        return planner.callbacks(info).stream().map(c -> new CallbackModel(c.kind(), Types.load(c.owner(), loader), c.method(),
            c.listener() == null ? null : Types.load(c.listener(), loader),
            c.parameter() == null ? null : Types.load(c.parameter(), loader))).toList();
    }

    /** §11.1.46: the {@code @SecondaryTable}s of the entity, with their {@code @PrimaryKeyJoinColumn} names. */
    private static List<SecondaryTableModel> secondaryTables(ClassInfo info) {
        List<AnnotationInfo> declared = new ArrayList<>();
        info.annotation(JPA + "SecondaryTable").ifPresent(declared::add);
        info.annotation(JPA + "SecondaryTables").ifPresent(container -> declared.addAll(container.annotations("value")));
        List<SecondaryTableModel> tables = new ArrayList<>();
        for (AnnotationInfo table : declared) {
            List<String> joinColumns = table.annotations("pkJoinColumns").stream().map(c -> nonEmpty(c.string("name")))
                .filter(Objects::nonNull).toList();
            tables.add(new SecondaryTableModel(new TableModel(table.string("name"), nonEmpty(table.string("schema")),
                nonEmpty(table.string("catalog"))), joinColumns));
        }
        return tables;
    }

    private TableModel table(ClassInfo info, String entityName) {
        return info.annotation(JPA + "Table")
            .map(t -> new TableModel(Optional.ofNullable(nonEmpty(t.string("name"))).orElse(entityName), nonEmpty(t.string("schema")),
                nonEmpty(t.string("catalog"))))
            .orElse(new TableModel(entityName, null, null));
    }

    // ---- persistent attributes (§2.2, §2.3) -------------------------------------------------------------

    /**
     * @param entityOverrides the {@code @AttributeOverride}s of the entity, for an attribute it inherits
     * @param entityConverts the {@code @Convert}s of the entity naming an attribute, for an attribute it inherits
     */
    private AttributeModel attribute(Member member, Map<String, AnnotationInfo> entityOverrides,
            Map<String, AnnotationInfo> entityConverts) {
        Annotated element = member.element();
        Class<?> type = Types.load(member.type(), loader);
        Class<?> declaring = Types.load(member.owner().name(), loader);
        if (element.isAnnotated(EMBEDDED_ID)) {
            return embedded(member, type, declaring, Map.of());
        }
        for (Map.Entry<String, AssociationAttribute.Kind> association : ASSOCIATIONS.entrySet()) {
            Optional<AnnotationInfo> annotation = element.annotation(association.getKey());
            if (annotation.isPresent()) {
                return association(member, type, declaring, association.getValue(), annotation.get());
            }
        }
        if (element.isAnnotated(JPA + "ElementCollection")) {
            return elementCollection(member, type, declaring);
        }
        if (planner.embedded(member).isPresent()) {
            return embedded(member, type, declaring, prefixed(entityConverts, member.name() + "."));
        }
        AnnotationInfo entityConvert = entityConverts.get(member.name());
        ValueConversion conversion = entityConvert == null ? conversion(member, type)
            : explicit(entityConvert).orElseGet(() -> conversion(member, type));
        if (!(conversion instanceof ValueConversion.Converted) && !Types.isBasic(type) && !Serializable.class.isAssignableFrom(type)) {
            throw new PersistenceException("The attribute " + member.name() + " of " + member.owner().name() + " has type "
                + type.getName() + ", which is neither basic, embeddable, serializable nor converted (§2.8)");
        }
        return basic(member, type, declaring, entityOverrides.get(member.name()), conversion);
    }

    // ---- relationships (§2.10, §11.1.25 to §11.1.43) -------------------------------------------------------

    private static AssociationAttribute associationOverride(AssociationAttribute base, AnnotationInfo override) {
        if (!base.owning()) {
            throw new PersistenceException("The @AssociationOverride " + base.name() + " must name an owning relationship (§11.1.2)");
        }
        List<JoinColumnModel> columns = override.annotations("joinColumns").stream()
            .map(EntityModelBuilder::joinColumn).toList();
        boolean tableWritten = override.isWritten("joinTable");
        if (tableWritten && !columns.isEmpty()) {
            throw new PersistenceException("The @AssociationOverride " + base.name() + " cannot specify both joinColumns and joinTable");
        }
        boolean usesJoinTable = base.joinTable() != null || base.kind() == AssociationAttribute.Kind.MANY_TO_MANY
            || base.kind() == AssociationAttribute.Kind.ONE_TO_MANY && base.joinColumns().isEmpty();
        if (tableWritten && !usesJoinTable || !tableWritten && usesJoinTable && !columns.isEmpty()) {
            throw new PersistenceException("The @AssociationOverride " + base.name()
                + " must retain the relationship's foreign-key or join-table mapping (§11.1.2)");
        }
        AnnotationInfo table = tableWritten ? override.annotation("joinTable") : null;
        JoinTableModel joinTable = table == null ? base.joinTable() : new JoinTableModel(nonEmpty(table.string("name")),
            nonEmpty(table.string("schema")), nonEmpty(table.string("catalog")),
            table.annotations("joinColumns").stream().map(EntityModelBuilder::joinColumn).toList(),
            table.annotations("inverseJoinColumns").stream().map(EntityModelBuilder::joinColumn).toList());
        return new AssociationAttribute(base.name(), base.javaType(), base.access(), base.declaringClass(), base.kind(),
            base.targetEntity(), base.mappedBy(), base.cascade(), base.orphanRemoval(), base.fetch(), base.optional(),
            tableWritten ? List.of() : columns, joinTable, base.orderBy(), base.index(), base.mapsId());
    }

    private AttributeModel association(Member member, Class<?> type, Class<?> declaring, AssociationAttribute.Kind kind,
            AnnotationInfo relationship) {
        Annotated element = member.element();
        Class<?> target = target(member, type, kind, relationship);
        if (target == null) {
            throw new PersistenceException("The relationship " + member.name() + " of " + member.owner().name() + " is a raw "
                + type.getName() + ": give its targetEntity, or the type of its elements (§2.10)");
        }
        String mappedBy = relationship.has("mappedBy") ? nonEmpty(relationship.string("mappedBy")) : null;
        Set<CascadeType> cascade = EnumSet.noneOf(CascadeType.class);
        relationship.enumConstants("cascade").forEach(constant -> cascade.add(CascadeType.valueOf(constant)));
        boolean orphanRemoval = relationship.has("orphanRemoval") && relationship.bool("orphanRemoval");
        FetchType fetch = FetchType.valueOf(relationship.enumConstant("fetch"));
        boolean optional = !relationship.has("optional") || relationship.bool("optional");
        JoinTableModel joinTable = element.annotation(JPA + "JoinTable").map(t -> new JoinTableModel(nonEmpty(t.string("name")),
            nonEmpty(t.string("schema")), nonEmpty(t.string("catalog")), t.annotations("joinColumns").stream()
                .map(EntityModelBuilder::joinColumn).toList(),
            t.annotations("inverseJoinColumns").stream().map(EntityModelBuilder::joinColumn).toList())).orElse(null);
        String orderBy = element.annotation(JPA + "OrderBy").map(o -> o.string("value").trim()).orElse(null);
        String mapsId = element.annotation(JPA + "MapsId").map(m -> m.string("value")).orElse(null);
        return new AssociationAttribute(member.name(), type, member.access(), declaring, kind, target, mappedBy, cascade, orphanRemoval,
            fetch, optional, joinColumns(element), joinTable, orderBy, index(member, type, declaring), mapsId);
    }

    /**
     * The entity a relationship references (§11.1.26, §11.1.30, §11.1.38, §11.1.40): its {@code targetEntity}, else the
     * declared type of a single-valued relationship, the element type of a collection, the value type of a map;
     * {@code null} for a raw collection without {@code targetEntity}.
     */
    private Class<?> target(Member member, Class<?> type, AssociationAttribute.Kind kind, AnnotationInfo relationship) {
        ClassDesc written = relationship.type("targetEntity");
        if (written != null && !written.descriptorString().equals("V")) {
            return Types.load(written, loader);
        }
        if (kind == AssociationAttribute.Kind.MANY_TO_ONE || kind == AssociationAttribute.Kind.ONE_TO_ONE) {
            return type;
        }
        List<Class<?>> arguments = GenericSignatures.memberTypeArguments(member.signature(), member.element() instanceof MethodInfo,
            loader);
        if (arguments == null) {
            return null;
        }
        return Map.class.isAssignableFrom(type) && arguments.size() == 2 ? arguments.get(1) : arguments.getFirst();
    }

    // ---- element collections (§2.7, §11.1.8, §11.1.16) ---------------------------------------------------

    /**
     * An element collection: its element is read as an attribute of the collection — an embeddable with the
     * {@code @AttributeOverride}s of the collection, or a basic value with its {@code @Column} (default: the name of the
     * attribute) and its conversion — and its collection table as written.
     */
    private AttributeModel elementCollection(Member member, Class<?> type, Class<?> declaring) {
        Annotated element = member.element();
        AnnotationInfo collection = element.annotation(JPA + "ElementCollection").orElseThrow();
        ClassDesc written = collection.type("targetClass");
        Class<?> elementType;
        if (written != null && !written.descriptorString().equals("V")) {
            elementType = Types.load(written, loader);
        } else {
            List<Class<?>> arguments = GenericSignatures.memberTypeArguments(member.signature(), element instanceof MethodInfo, loader);
            elementType = arguments == null ? null : Map.class.isAssignableFrom(type) && arguments.size() == 2 ? arguments.get(1)
                : arguments.getFirst();
        }
        if (elementType == null) {
            throw new PersistenceException("The element collection " + member.name() + " of " + member.owner().name() + " is a raw "
                + type.getName() + ": give its targetClass, or the type of its elements (§2.7)");
        }
        boolean embeddable = source.read(elementType.getName()).map(i -> i.isAnnotated(JPA + "Embeddable")).orElse(false);
        // §11.1.4: the overrides of the values of a map name them "value.<attribute>"
        AttributeModel value = embeddable ? embedded(member, elementType, declaring, Map.of(), Map.class.isAssignableFrom(type) ? "value." : "")
            : basic(member, elementType, declaring, null, conversion(member, elementType));
        CollectionTableModel table = element.annotation(JPA + "CollectionTable").map(t -> new CollectionTableModel(nonEmpty(t.string("name")),
            nonEmpty(t.string("schema")), nonEmpty(t.string("catalog")), t.annotations("joinColumns").stream()
                .map(EntityModelBuilder::joinColumn).toList())).orElse(CollectionTableModel.defaults());
        String orderBy = element.annotation(JPA + "OrderBy").map(o -> o.string("value").trim()).orElse(null);
        FetchType fetch = FetchType.valueOf(collection.enumConstant("fetch"));
        return new ElementCollectionAttribute(member.name(), type, member.access(), declaring, member.signature(), value, table, orderBy,
            index(member, type, declaring), fetch);
    }

    // ---- map keys and order columns (§2.7, §11.1.30 to §11.1.42) ---------------------------------------------

    /**
     * How the collection {@code member} indexes its elements: the {@code @OrderColumn} of a list; the key of a map — an
     * attribute of the target ({@code @MapKey}), an entity ({@code @MapKeyJoinColumn}, or a key class that is an
     * entity), else a basic value in its {@code @MapKeyColumn}. {@code null} for an unindexed collection.
     */
    private CollectionIndex index(Member member, Class<?> type, Class<?> declaring) {
        Annotated element = member.element();
        if (!Map.class.isAssignableFrom(type)) {
            return element.annotation(JPA + "OrderColumn").<CollectionIndex>map(o -> {
                String name = nonEmpty(o.string("name"));
                ColumnModel column = new ColumnModel(name != null ? name : member.name() + "_ORDER", null, o.bool("nullable"),
                    o.bool("insertable"), o.bool("updatable"), false, 255, 0, 0, nonEmpty(o.string("columnDefinition")));
                return new CollectionIndex.ByPosition(new BasicAttribute(column.name(), Integer.class, member.access(), declaring, column,
                    true, FetchType.EAGER, false, new ValueConversion.None(), false));
            }).orElse(null);
        }
        Optional<AnnotationInfo> mapKey = element.annotation(JPA + "MapKey");
        if (mapKey.isPresent()) {
            return new CollectionIndex.ByAttribute(mapKey.get().has("name") ? mapKey.get().string("name") : "");
        }
        Class<?> keyType = element.annotation(JPA + "MapKeyClass").map(k -> Types.load(k.type("value"), loader)).orElse(null);
        if (keyType == null) {
            List<Class<?>> arguments = GenericSignatures.memberTypeArguments(member.signature(), element instanceof MethodInfo, loader);
            if (arguments == null || arguments.size() != 2) {
                return new CollectionIndex.Unsupported("a raw map without @MapKeyClass");
            }
            keyType = arguments.getFirst();
        }
        List<JoinColumnModel> keyJoins = new ArrayList<>();
        element.annotation(JPA + "MapKeyJoinColumn").ifPresent(c -> keyJoins.add(joinColumn(c)));
        element.annotation(JPA + "MapKeyJoinColumns").ifPresent(container ->
            container.annotations("value").forEach(c -> keyJoins.add(joinColumn(c))));
        Optional<ClassInfo> keyInfo = source.read(keyType.getName());
        if (!keyJoins.isEmpty() || keyInfo.map(i -> i.isAnnotated(JPA + "Entity")).orElse(false)) {
            return new CollectionIndex.ByEntity(keyType, keyJoins);
        }
        if (keyInfo.map(i -> i.isAnnotated(JPA + "Embeddable")).orElse(false)) {
            return new CollectionIndex.ByEmbedded(embedded(member, keyType, declaring,
                prefixed(converts(element), "key."), "key."));
        }
        String defaultName = member.name() + "_KEY";
        ColumnModel column = element.annotation(JPA + "MapKeyColumn").map(c -> column(c, defaultName))
            .orElse(ColumnModel.defaultFor(defaultName));
        return new CollectionIndex.ByColumn(new BasicAttribute(defaultName, keyType, member.access(), declaring, column, true,
            FetchType.EAGER, false, keyConversion(member, keyType), false));
    }

    /** The conversion of a basic map key: {@code @Convert(attributeName = "key")}, auto-applied, {@code @MapKeyEnumerated}, {@code @MapKeyTemporal}. */
    private ValueConversion keyConversion(Member member, Class<?> type) {
        AnnotationInfo convert = converts(member.element()).get("key");
        Optional<ValueConversion> explicit = convert == null ? Optional.empty() : explicit(convert);
        if (explicit.isPresent()) {
            return explicit.get();
        }
        Class<?> boxed = Types.box(type);
        for (ConverterModel candidate : converters) {
            if (candidate.autoApply() && candidate.attributeType() == boxed) {
                return new ValueConversion.Converted(candidate.converterClass(), candidate.databaseType());
            }
        }
        if (type.isEnum()) {
            String enumType = member.element().annotation(JPA + "MapKeyEnumerated").map(e -> e.enumConstant("value")).orElse("ORDINAL");
            return "STRING".equals(enumType) ? new ValueConversion.EnumString() : new ValueConversion.EnumOrdinal();
        }
        if (Types.isLegacyTemporal(type)) {
            String temporal = member.element().annotation(JPA + "MapKeyTemporal").map(t -> t.enumConstant("value")).orElse("TIMESTAMP");
            return new ValueConversion.Temporal(TemporalType.valueOf(temporal));
        }
        return new ValueConversion.None();
    }

    /** The {@code @JoinColumn}s written on an element, in order. */
    private static List<JoinColumnModel> joinColumns(Annotated element) {
        List<JoinColumnModel> columns = new ArrayList<>();
        element.annotation(JPA + "JoinColumn").ifPresent(c -> columns.add(joinColumn(c)));
        element.annotation(JPA + "JoinColumns").ifPresent(container ->
            container.annotations("value").forEach(c -> columns.add(joinColumn(c))));
        return columns;
    }

    private static JoinColumnModel joinColumn(AnnotationInfo column) {
        return new JoinColumnModel(nonEmpty(column.string("name")), nonEmpty(column.string("referencedColumnName")),
            nonEmpty(column.string("table")), column.bool("nullable"), column.bool("insertable"), column.bool("updatable"));
    }

    // ---- basic attributes (§2.8, §11.1.6) ----------------------------------------------------------------

    private BasicAttribute basic(Member member, Class<?> type, Class<?> declaring, AnnotationInfo override,
            ValueConversion conversion) {
        Annotated element = member.element();
        AnnotationInfo columnAnnotation = override != null ? override.annotation("column")
            : element.annotation(JPA + "Column").orElse(null);
        ColumnModel column = columnAnnotation == null ? ColumnModel.defaultFor(member.name()) : column(columnAnnotation, member.name());
        Optional<AnnotationInfo> basicAnnotation = element.annotation(JPA + "Basic");
        boolean optional = !type.isPrimitive() && basicAnnotation.map(b -> b.bool("optional")).orElse(true);
        FetchType fetch = basicAnnotation.map(b -> FetchType.valueOf(b.enumConstant("fetch"))).orElse(FetchType.EAGER);
        return new BasicAttribute(member.name(), type, member.access(), declaring, column, optional, fetch,
            element.isAnnotated(JPA + "Lob"), conversion, element.isAnnotated(JPA + "Version"));
    }

    private static ColumnModel column(AnnotationInfo column, String attributeName) {
        String name = nonEmpty(column.string("name"));
        return new ColumnModel(name == null ? attributeName : name, nonEmpty(column.string("table")), column.bool("nullable"),
            column.bool("insertable"), column.bool("updatable"), column.bool("unique"), column.integer("length"),
            column.integer("precision"), column.integer("scale"), nonEmpty(column.string("columnDefinition")));
    }

    /** §3.9 converters (explicit, then auto-applied), then enums (§11.1.18, @EnumeratedValue), then legacy temporals. */
    private ValueConversion conversion(Member member, Class<?> type) {
        Optional<ValueConversion> explicit = member.element().annotation(JPA + "Convert").flatMap(this::explicit);
        if (explicit.isPresent()) {
            return explicit.get();
        }
        Class<?> boxed = Types.box(type);
        for (ConverterModel candidate : converters) {
            if (candidate.autoApply() && candidate.attributeType() == boxed) {
                return new ValueConversion.Converted(candidate.converterClass(), candidate.databaseType());
            }
        }
        if (type.isEnum()) {
            ClassInfo enumInfo = source.read(type.getName()).orElse(null);
            if (enumInfo != null) {
                for (FieldInfo field : enumInfo.fields()) {
                    if (field.isAnnotated(JPA + "EnumeratedValue")) {
                        return new ValueConversion.EnumByValue(field.name(), Types.load(field.type(), loader));
                    }
                }
            }
            String enumType = member.element().annotation(JPA + "Enumerated").map(e -> e.enumConstant("value")).orElse("ORDINAL");
            return "STRING".equals(enumType) ? new ValueConversion.EnumString() : new ValueConversion.EnumOrdinal();
        }
        if (Types.isLegacyTemporal(type)) {
            String temporal = member.element().annotation(JPA + "Temporal").map(t -> t.enumConstant("value")).orElse("TIMESTAMP");
            return new ValueConversion.Temporal(TemporalType.valueOf(temporal));
        }
        return new ValueConversion.None();
    }

    /** The conversion a {@code @Convert} asks for: its converter, or none; empty if it names neither. */
    private Optional<ValueConversion> explicit(AnnotationInfo convert) {
        if (convert.bool("disableConversion")) {
            return Optional.of(new ValueConversion.None());
        }
        ClassDesc converter = convert.type("converter");
        if (converter == null || converter.descriptorString().equals("Ljakarta/persistence/AttributeConverter;")) {
            return Optional.empty();
        }
        Class<?> converterClass = Types.load(converter, loader);
        ConverterModel known = converters.stream().filter(c -> c.converterClass() == converterClass).findFirst()
            .orElseGet(() -> converter(source.read(converterClass.getName()).orElseThrow()));
        return Optional.of(new ValueConversion.Converted(converterClass, known.databaseType()));
    }

    /** §11.1.10: the {@code @Convert}s written on an element that name the attribute they convert, by name or dotted path. */
    private static Map<String, AnnotationInfo> converts(Annotated element) {
        Map<String, AnnotationInfo> converts = new LinkedHashMap<>();
        List<AnnotationInfo> declared = new ArrayList<>();
        element.annotation(JPA + "Convert").ifPresent(declared::add);
        element.annotation(JPA + "Converts").ifPresent(container -> declared.addAll(container.annotations("value")));
        for (AnnotationInfo convert : declared) {
            String attributeName = convert.has("attributeName") ? nonEmpty(convert.string("attributeName")) : null;
            if (attributeName != null) {
                converts.put(attributeName, convert);
            }
        }
        return converts;
    }

    /** The entries of {@code byPath} under {@code prefix}, the prefix removed. */
    private static Map<String, AnnotationInfo> prefixed(Map<String, AnnotationInfo> byPath, String prefix) {
        Map<String, AnnotationInfo> inner = new LinkedHashMap<>();
        byPath.forEach((path, value) -> {
            if (path.startsWith(prefix)) {
                inner.put(path.substring(prefix.length()), value);
            }
        });
        return inner;
    }

    // ---- embeddables (§2.6) ------------------------------------------------------------------------------

    /** @param entityConverts the {@code @Convert}s of the entity naming attributes of this one, by path inside it */
    private EmbeddedAttribute embedded(Member member, Class<?> type, Class<?> declaring, Map<String, AnnotationInfo> entityConverts) {
        return embedded(member, type, declaring, entityConverts, "");
    }

    /** @param overridePrefix the prefix of the paths the {@code @AttributeOverride}s name ({@code "value."} for map values) */
    private EmbeddedAttribute embedded(Member member, Class<?> type, Class<?> declaring, Map<String, AnnotationInfo> entityConverts,
            String overridePrefix) {
        Map<String, AnnotationInfo> converts = new LinkedHashMap<>(entityConverts);
        converts.putAll(converts(member.element())); // the attribute's own win
        EmbeddableModel embeddable = converted(embeddable(type, member.access()), converts);
        List<AnnotationInfo> associationOverrides = new ArrayList<>();
        source.read(type.getName()).ifPresent(info -> {
            info.annotation(JPA + "AssociationOverride").ifPresent(associationOverrides::add);
            info.annotation(JPA + "AssociationOverrides").ifPresent(a -> associationOverrides.addAll(a.annotations("value")));
        });
        member.element().annotation(JPA + "AssociationOverride").ifPresent(associationOverrides::add);
        member.element().annotation(JPA + "AssociationOverrides").ifPresent(a -> associationOverrides.addAll(a.annotations("value")));
        Map<String, AnnotationInfo> effective = new LinkedHashMap<>();
        associationOverrides.forEach(a -> effective.put(a.string("name"), a));
        embeddable = associationOverrides(embeddable, effective);
        Map<String, ColumnModel> columns = new LinkedHashMap<>();
        flatten(embeddable, "", columns);
        overrides(member.element()).forEach((written, override) -> {
            String path = written.startsWith(overridePrefix) ? written.substring(overridePrefix.length()) : written;
            if (columns.containsKey(path)) {
                columns.put(path, column(override.annotation("column"), path.substring(path.lastIndexOf('.') + 1)));
            }
        });
        return new EmbeddedAttribute(member.name(), type, member.access(), declaring, embeddable, columns);
    }

    private static EmbeddableModel associationOverrides(EmbeddableModel model, Map<String, AnnotationInfo> overrides) {
        if (overrides.isEmpty()) return model;
        Map<String, AnnotationInfo> remaining = new LinkedHashMap<>(overrides);
        List<AttributeModel> attributes = new ArrayList<>();
        for (AttributeModel attribute : model.attributes()) {
            AnnotationInfo override = remaining.remove(attribute.name());
            if (override != null) {
                if (!(attribute instanceof AssociationAttribute association)) {
                    throw new PersistenceException("The @AssociationOverride " + attribute.name() + " of "
                        + model.javaType().getName() + " does not name a relationship");
                }
                attribute = associationOverride(association, override);
            }
            if (attribute instanceof EmbeddedAttribute nested) {
                Map<String, AnnotationInfo> paths = prefixed(remaining, nested.name() + ".");
                attribute = new EmbeddedAttribute(nested.name(), nested.javaType(), nested.access(), nested.declaringClass(),
                    associationOverrides(nested.embeddable(), paths), nested.columns());
                remaining.keySet().removeIf(path -> path.startsWith(nested.name() + "."));
            }
            attributes.add(attribute);
        }
        if (!remaining.isEmpty()) {
            throw new PersistenceException("The @AssociationOverride paths " + remaining.keySet() + " of "
                + model.javaType().getName() + " are unknown");
        }
        return new EmbeddableModel(model.javaType(), model.access(), model.isRecord(), attributes);
    }

    /**
     * The embeddable as its owner sees it: the basic attributes the owner's {@code @Convert}s name (by dotted path for
     * nested embeddables) take the conversion they ask for. The shared model is kept when nothing is converted.
     */
    private EmbeddableModel converted(EmbeddableModel embeddable, Map<String, AnnotationInfo> converts) {
        if (converts.isEmpty()) {
            return embeddable;
        }
        List<AttributeModel> attributes = new ArrayList<>();
        for (AttributeModel attribute : embeddable.attributes()) {
            AnnotationInfo convert = converts.get(attribute.name());
            Optional<ValueConversion> conversion = convert == null ? Optional.empty() : explicit(convert);
            attributes.add(switch (attribute) {
                case BasicAttribute basic when conversion.isPresent() -> new BasicAttribute(basic.name(), basic.javaType(),
                    basic.access(), basic.declaringClass(), basic.column(), basic.optional(), basic.fetch(), basic.lob(),
                    conversion.get(), basic.version());
                case EmbeddedAttribute nested -> new EmbeddedAttribute(nested.name(), nested.javaType(), nested.access(),
                    nested.declaringClass(), converted(nested.embeddable(), prefixed(converts, nested.name() + ".")), nested.columns());
                default -> attribute;
            });
        }
        return new EmbeddableModel(embeddable.javaType(), embeddable.access(), embeddable.isRecord(), attributes);
    }

    private static void flatten(EmbeddableModel embeddable, String prefix, Map<String, ColumnModel> into) {
        for (AttributeModel attribute : embeddable.attributes()) {
            switch (attribute) {
                case BasicAttribute basic -> into.put(prefix + basic.name(), basic.column());
                case EmbeddedAttribute nested -> nested.columns().forEach((path, column) -> into.put(prefix + nested.name() + "." + path, column));
                default -> {
                    // relationships and element collections of embeddables are milestone P5
                }
            }
        }
    }

    /** §2.3.3: an embeddable takes the access type of its owner, unless it says otherwise; a record is read by field. */
    private EmbeddableModel embeddable(Class<?> type, AccessKind ownerAccess) {
        ClassInfo info = source.read(type.getName()).orElseThrow(() -> new PersistenceException("The embeddable "
            + type.getName() + " cannot be found"));
        AccessKind access = AccessPlanner.embeddableAccess(info, ownerAccess);
        String key = type.getName() + "/" + access;
        EmbeddableModel known = embeddables.get(key);
        if (known != null) {
            return known;
        }
        List<AttributeModel> attributes = new ArrayList<>();
        for (Member member : planner.members(info, access)) {
            attributes.add(attribute(member, Map.of(), Map.of()));
        }
        EmbeddableModel model = new EmbeddableModel(type, access, info.isRecord(), attributes);
        embeddables.put(key, model);
        return model;
    }

    /** The {@code @AttributeOverride}s written on an element, by attribute name or dotted path. */
    private static Map<String, AnnotationInfo> overrides(Annotated element) {
        Map<String, AnnotationInfo> overrides = new LinkedHashMap<>();
        element.annotation(ATTRIBUTE_OVERRIDE).ifPresent(o -> overrides.put(o.string("name"), o));
        element.annotation(ATTRIBUTE_OVERRIDES).ifPresent(container ->
            container.annotations("value").forEach(o -> overrides.put(o.string("name"), o)));
        return overrides;
    }

    // ---- identifiers (§2.4, §11.1.20) -------------------------------------------------------------------

    private IdModel id(ClassInfo entity, List<ClassInfo> hierarchy, Map<AttributeModel, Member> members) {
        List<AttributeModel> ids = new ArrayList<>();
        Member single = null;
        for (Map.Entry<AttributeModel, Member> entry : members.entrySet()) {
            Annotated element = entry.getValue().element();
            if (element.isAnnotated(EMBEDDED_ID)) {
                return new IdModel.Embedded((EmbeddedAttribute) entry.getKey());
            }
            if (element.isAnnotated(ID)) {
                switch (entry.getKey()) {
                    case BasicAttribute basic -> ids.add(basic);
                    // §2.4.1: a derived identity, the relationship to the parent entity is (part of) the identifier
                    case AssociationAttribute relationship when relationship.kind() == AssociationAttribute.Kind.MANY_TO_ONE
                        || relationship.kind() == AssociationAttribute.Kind.ONE_TO_ONE -> ids.add(relationship);
                    default -> throw new PersistenceException("The identifier " + entry.getKey().name() + " of " + entity.name()
                        + " must be a basic attribute or a many-to-one or one-to-one relationship; an identifier of an "
                        + "embeddable type is an @EmbeddedId (§2.4)");
                }
                single = entry.getValue();
            }
        }
        Optional<AnnotationInfo> idClass = hierarchy.stream().map(c -> c.annotation(JPA + "IdClass")).flatMap(Optional::stream)
            .reduce((first, second) -> second);
        if (idClass.isPresent()) {
            return new IdModel.ByIdClass(Types.load(idClass.get().type("value"), loader), ids);
        }
        if (ids.size() > 1) {
            throw new PersistenceException("The entity " + entity.name() + " has several @Id attributes but no @IdClass (§2.4.1)");
        }
        if (ids.isEmpty()) {
            throw new PersistenceException("The entity " + entity.name() + " has no identifier: no @Id or @EmbeddedId (§2.4)");
        }
        return switch (ids.getFirst()) {
            case BasicAttribute basic -> new IdModel.Single(basic, generation(single, entity));
            case AssociationAttribute relationship -> new IdModel.Derived(relationship);
            default -> throw new PersistenceException("The identifier " + ids.getFirst().name() + " of " + entity.name()
                + " must be a basic attribute or a many-to-one or one-to-one relationship (§2.4)");
        };
    }

    private Optional<GenerationModel> generation(Member member, ClassInfo entity) {
        Optional<AnnotationInfo> generated = member.element().annotation(JPA + "GeneratedValue");
        if (generated.isEmpty()) {
            return Optional.empty();
        }
        GenerationType strategy = GenerationType.valueOf(generated.get().enumConstant("strategy"));
        String generator = nonEmpty(generated.get().string("generator"));
        Optional<AnnotationInfo> sequence = generatorAnnotation("SequenceGenerator", generator, member.element(), entity);
        Optional<AnnotationInfo> table = generatorAnnotation("TableGenerator", generator, member.element(), entity);
        return Optional.of(new GenerationModel(strategy, generator,
            sequence.map(s -> new SequenceGeneratorModel(s.string("name"), nonEmpty(s.string("sequenceName")),
                nonEmpty(s.string("schema")), nonEmpty(s.string("catalog")), s.integer("initialValue"), s.integer("allocationSize"))),
            table.map(t -> new TableGeneratorModel(t.string("name"), nonEmpty(t.string("table")), nonEmpty(t.string("schema")),
                nonEmpty(t.string("catalog")), nonEmpty(t.string("pkColumnName")), nonEmpty(t.string("valueColumnName")),
                nonEmpty(t.string("pkColumnValue")), t.integer("initialValue"), t.integer("allocationSize")))));
    }

    /**
     * The generator named {@code name} (generators are global to the unit, §11.1.51), searched on the identifier, its
     * entity, then every listed class; without a name, the one written on the identifier or its entity (3.2).
     */
    private Optional<AnnotationInfo> generatorAnnotation(String kind, String name, Annotated idElement, ClassInfo entity) {
        List<Annotated> places = new ArrayList<>();
        places.add(idElement);
        places.add(entity);
        if (name != null) {
            // the generators of the mapping files, outside any class, override the annotated ones (§12.2.2)
            places.addAll(mappingFileAnnotations());
            for (ClassInfo info : listed) {
                places.add(info);
                places.addAll(info.fields());
                places.addAll(info.methods());
            }
        }
        for (Annotated place : places) {
            for (AnnotationInfo generator : generators(place, kind)) {
                if (name == null || name.equals(generator.string("name"))) {
                    return Optional.of(generator);
                }
            }
        }
        return Optional.empty();
    }

    private static List<AnnotationInfo> generators(Annotated element, String kind) {
        List<AnnotationInfo> found = new ArrayList<>();
        element.annotation(JPA + kind).ifPresent(found::add);
        element.annotation(JPA + kind + "s").ifPresent(container -> found.addAll(container.annotations("value")));
        return found;
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private static String nonEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** The unqualified name of a class (§2.1 entity name): {@code com.acme.Outer$Inner} gives {@code Inner}. */
    private static String simpleName(String binaryName) {
        String name = binaryName.substring(binaryName.lastIndexOf('.') + 1);
        return name.substring(name.lastIndexOf('$') + 1);
    }
}
