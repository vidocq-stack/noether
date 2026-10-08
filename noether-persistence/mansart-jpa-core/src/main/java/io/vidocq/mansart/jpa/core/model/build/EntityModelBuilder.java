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
import io.vidocq.mansart.jpa.core.model.ColumnModel;
import io.vidocq.mansart.jpa.core.model.ConverterModel;
import io.vidocq.mansart.jpa.core.model.ElementCollectionAttribute;
import io.vidocq.mansart.jpa.core.model.EmbeddableModel;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.model.EntityModel;
import io.vidocq.mansart.jpa.core.model.GenerationModel;
import io.vidocq.mansart.jpa.core.model.IdModel;
import io.vidocq.mansart.jpa.core.model.PersistenceUnitModel;
import io.vidocq.mansart.jpa.core.model.SequenceGeneratorModel;
import io.vidocq.mansart.jpa.core.model.TableGeneratorModel;
import io.vidocq.mansart.jpa.core.model.TableModel;
import io.vidocq.mansart.jpa.core.model.ValueConversion;
import io.vidocq.mansart.jpa.core.model.source.Annotated;
import io.vidocq.mansart.jpa.core.model.source.AnnotationInfo;
import io.vidocq.mansart.jpa.core.model.source.ClassFileSource;
import io.vidocq.mansart.jpa.core.model.source.ClassInfo;
import io.vidocq.mansart.jpa.core.model.source.FieldInfo;
import io.vidocq.mansart.jpa.core.model.source.MethodInfo;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the entity model of a persistence unit from its managed classes (Jakarta Persistence 3.2, chapter 2 and
 * chapter 11), whatever read them: today the class files, through {@link ClassFileSource}. Every mapping error is a
 * {@link PersistenceException} naming the class and the attribute, raised when the factory is created.
 */
public final class EntityModelBuilder {

    private static final String JPA = "jakarta.persistence.";
    private static final String ENTITY = JPA + "Entity";
    private static final String EMBEDDABLE = JPA + "Embeddable";
    private static final String MAPPED_SUPERCLASS = JPA + "MappedSuperclass";
    private static final String CONVERTER = JPA + "Converter";
    private static final String ACCESS = JPA + "Access";
    private static final String ID = JPA + "Id";
    private static final String EMBEDDED_ID = JPA + "EmbeddedId";
    private static final String TRANSIENT = JPA + "Transient";
    private static final String ATTRIBUTE_OVERRIDE = JPA + "AttributeOverride";
    private static final String ATTRIBUTE_OVERRIDES = JPA + "AttributeOverrides";
    private static final Map<String, AssociationAttribute.Kind> ASSOCIATIONS = Map.of(
        JPA + "OneToOne", AssociationAttribute.Kind.ONE_TO_ONE, JPA + "ManyToOne", AssociationAttribute.Kind.MANY_TO_ONE,
        JPA + "OneToMany", AssociationAttribute.Kind.ONE_TO_MANY, JPA + "ManyToMany", AssociationAttribute.Kind.MANY_TO_MANY);

    /** A persistent field or property, before it is classified. */
    private record Member(String name, AccessKind access, ClassInfo owner, Annotated element, ClassDesc type, String signature) {
    }

    private final ClassFileSource source;
    private final ClassLoader loader;
    private final List<ClassInfo> listed = new ArrayList<>();
    private final List<ConverterModel> converters = new ArrayList<>();
    private final Map<String, EmbeddableModel> embeddables = new HashMap<>();

    private EntityModelBuilder(ClassFileSource source, ClassLoader loader) {
        this.source = source;
        this.loader = loader;
    }

    /** The model of the managed classes named {@code classNames}, loaded with {@code loader}. */
    public static PersistenceUnitModel build(Collection<String> classNames, ClassLoader loader) {
        EntityModelBuilder builder = new EntityModelBuilder(new ClassFileSource(loader), loader);
        return builder.build(classNames);
    }

    private PersistenceUnitModel build(Collection<String> classNames) {
        for (String name : classNames) {
            listed.add(source.read(name).orElseThrow(() -> new PersistenceException("The managed class " + name
                + " of the persistence unit cannot be found")));
        }
        for (ClassInfo info : listed) {
            if (info.isAnnotated(CONVERTER)) {
                converters.add(converter(info));
            }
        }
        List<EntityModel> entities = new ArrayList<>();
        for (ClassInfo info : listed) {
            if (info.isAnnotated(ENTITY)) {
                entities.add(entity(info));
            }
        }
        return new PersistenceUnitModel(entities, converters);
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
        List<ClassInfo> hierarchy = hierarchy(info);
        AccessKind access = defaultAccess(info, hierarchy);
        Map<String, AnnotationInfo> overrides = overrides(info);

        List<AttributeModel> attributes = new ArrayList<>();
        Map<AttributeModel, Member> members = new LinkedHashMap<>();
        for (ClassInfo declaring : hierarchy) {
            for (Member member : members(declaring, classAccess(declaring, access))) {
                AttributeModel attribute = attribute(member, declaring == info ? Map.of() : overrides);
                attributes.add(attribute);
                members.put(attribute, member);
            }
        }
        IdModel id = id(info, hierarchy, members);
        Optional<BasicAttribute> version = attributes.stream()
            .filter(a -> a instanceof BasicAttribute b && b.version()).map(a -> (BasicAttribute) a).findFirst();
        Optional<Class<?>> superEntity = hierarchy.stream().filter(c -> c != info && c.isAnnotated(ENTITY))
            .reduce((first, second) -> second).map(c -> Types.load(c.name(), loader));
        return new EntityModel(type, entityName, table(info, entityName), access, id, attributes, version, superEntity);
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

    private TableModel table(ClassInfo info, String entityName) {
        return info.annotation(JPA + "Table")
            .map(t -> new TableModel(Optional.ofNullable(nonEmpty(t.string("name"))).orElse(entityName), nonEmpty(t.string("schema")),
                nonEmpty(t.string("catalog"))))
            .orElse(new TableModel(entityName, null, null));
    }

    /** The entity and its entity or mapped superclasses, the root first (§2.11). */
    private List<ClassInfo> hierarchy(ClassInfo info) {
        List<ClassInfo> chain = new ArrayList<>();
        ClassInfo current = info;
        while (current != null) {
            if (current == info || current.isAnnotated(ENTITY) || current.isAnnotated(MAPPED_SUPERCLASS)) {
                chain.addFirst(current);
            }
            String superclass = current.superclassName();
            current = superclass == null || superclass.startsWith("java.") ? null : source.read(superclass).orElse(null);
        }
        return chain;
    }

    /** §2.3.1: an explicit {@code @Access} on the entity, else the placement of the identifier in the hierarchy. */
    private AccessKind defaultAccess(ClassInfo entity, List<ClassInfo> hierarchy) {
        Optional<AccessKind> explicit = explicitAccess(entity);
        if (explicit.isPresent()) {
            return explicit.get();
        }
        for (ClassInfo info : hierarchy) {
            if (info.fields().stream().anyMatch(f -> f.isAnnotated(ID) || f.isAnnotated(EMBEDDED_ID))) {
                return AccessKind.FIELD;
            }
            if (info.methods().stream().anyMatch(m -> m.isAnnotated(ID) || m.isAnnotated(EMBEDDED_ID))) {
                return AccessKind.PROPERTY;
            }
        }
        throw new PersistenceException("The entity " + entity.name() + " has no identifier: no @Id or @EmbeddedId (§2.4)");
    }

    private AccessKind classAccess(ClassInfo info, AccessKind inherited) {
        return explicitAccess(info).orElse(inherited);
    }

    private static Optional<AccessKind> explicitAccess(Annotated element) {
        return element.annotation(ACCESS).map(a -> AccessKind.valueOf(a.enumConstant("value")));
    }

    // ---- persistent attributes (§2.2, §2.3) -------------------------------------------------------------

    /** The persistent fields or properties a class declares, in declaration order. */
    private List<Member> members(ClassInfo info, AccessKind access) {
        List<Member> members = new ArrayList<>();
        for (FieldInfo field : info.fields()) {
            if (field.isStatic() || field.isSynthetic() || field.isTransientModifier() || field.isAnnotated(TRANSIENT)) {
                continue;
            }
            AccessKind fieldAccess = explicitAccess(field).orElse(null);
            if (access == AccessKind.FIELD || fieldAccess == AccessKind.FIELD) {
                members.add(new Member(field.name(), AccessKind.FIELD, info, field, field.type(), field.genericSignature()));
            }
        }
        for (MethodInfo method : info.methods()) {
            String property = propertyName(method);
            if (property == null || method.isAnnotated(TRANSIENT)) {
                continue;
            }
            AccessKind methodAccess = explicitAccess(method).orElse(null);
            boolean persistent = access == AccessKind.PROPERTY ? methodAccess != AccessKind.FIELD : methodAccess == AccessKind.PROPERTY;
            if (persistent && hasSetter(info, method, property)) {
                members.add(new Member(property, AccessKind.PROPERTY, info, method, method.type().returnType(), method.genericSignature()));
            }
        }
        return members;
    }

    /** JavaBeans: {@code getX()}, or {@code isX()} for a {@code boolean}; {@code null} if not a getter. */
    private static String propertyName(MethodInfo method) {
        if (method.isStatic() || method.isSynthetic() || method.type().parameterCount() != 0) {
            return null;
        }
        ClassDesc returned = method.type().returnType();
        String name = method.name();
        String suffix;
        if (name.startsWith("get") && name.length() > 3 && !returned.equals(ConstantDescs.CD_void)) {
            suffix = name.substring(3);
        } else if (name.startsWith("is") && name.length() > 2 && returned.equals(ConstantDescs.CD_boolean)) {
            suffix = name.substring(2);
        } else {
            return null;
        }
        if (suffix.length() > 1 && Character.isUpperCase(suffix.charAt(0)) && Character.isUpperCase(suffix.charAt(1))) {
            return suffix;
        }
        return Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
    }

    private static boolean hasSetter(ClassInfo info, MethodInfo getter, String property) {
        String setter = "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        String alternative = "set" + property;
        return info.methods().stream().anyMatch(m -> (m.name().equals(setter) || m.name().equals(alternative)) && !m.isStatic()
            && m.type().parameterCount() == 1 && m.type().parameterType(0).equals(getter.type().returnType()));
    }

    private AttributeModel attribute(Member member, Map<String, AnnotationInfo> entityOverrides) {
        Annotated element = member.element();
        Class<?> type = Types.load(member.type(), loader);
        Class<?> declaring = Types.load(member.owner().name(), loader);
        if (element.isAnnotated(EMBEDDED_ID)) {
            return embedded(member, type, declaring);
        }
        for (Map.Entry<String, AssociationAttribute.Kind> association : ASSOCIATIONS.entrySet()) {
            Optional<AnnotationInfo> annotation = element.annotation(association.getKey());
            if (annotation.isPresent()) {
                String mappedBy = annotation.get().has("mappedBy") ? nonEmpty(annotation.get().string("mappedBy")) : null;
                return new AssociationAttribute(member.name(), type, member.access(), declaring, association.getValue(),
                    member.signature(), mappedBy);
            }
        }
        if (element.isAnnotated(JPA + "ElementCollection")) {
            return new ElementCollectionAttribute(member.name(), type, member.access(), declaring, member.signature());
        }
        if (element.isAnnotated(JPA + "Embedded") || isEmbeddable(type)) {
            return embedded(member, type, declaring);
        }
        return basic(member, type, declaring, entityOverrides.get(member.name()));
    }

    private boolean isEmbeddable(Class<?> type) {
        if (type.isPrimitive() || type.isArray() || type.getName().startsWith("java.")) {
            return false;
        }
        return source.read(type.getName()).map(i -> i.isAnnotated(EMBEDDABLE)).orElse(false);
    }

    // ---- basic attributes (§2.8, §11.1.6) ----------------------------------------------------------------

    private BasicAttribute basic(Member member, Class<?> type, Class<?> declaring, AnnotationInfo override) {
        Annotated element = member.element();
        ValueConversion conversion = conversion(member, type);
        if (!(conversion instanceof ValueConversion.Converted) && !Types.isBasic(type) && !Serializable.class.isAssignableFrom(type)) {
            throw new PersistenceException("The attribute " + member.name() + " of " + member.owner().name() + " has type "
                + type.getName() + ", which is neither basic, embeddable, serializable nor converted (§2.8)");
        }
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
        Optional<AnnotationInfo> convert = member.element().annotation(JPA + "Convert");
        if (convert.isPresent()) {
            if (convert.get().bool("disableConversion")) {
                return new ValueConversion.None();
            }
            ClassDesc converter = convert.get().type("converter");
            if (converter != null && !converter.descriptorString().equals("Ljakarta/persistence/AttributeConverter;")) {
                Class<?> converterClass = Types.load(converter, loader);
                ConverterModel known = converters.stream().filter(c -> c.converterClass() == converterClass).findFirst()
                    .orElseGet(() -> converter(source.read(converterClass.getName()).orElseThrow()));
                return new ValueConversion.Converted(converterClass, known.databaseType());
            }
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

    // ---- embeddables (§2.6) ------------------------------------------------------------------------------

    private EmbeddedAttribute embedded(Member member, Class<?> type, Class<?> declaring) {
        EmbeddableModel embeddable = embeddable(type, member.access());
        Map<String, ColumnModel> columns = new LinkedHashMap<>();
        flatten(embeddable, "", columns);
        overrides(member.element()).forEach((path, override) -> {
            if (columns.containsKey(path)) {
                columns.put(path, column(override.annotation("column"), path.substring(path.lastIndexOf('.') + 1)));
            }
        });
        return new EmbeddedAttribute(member.name(), type, member.access(), declaring, embeddable, columns);
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
        AccessKind access = info.isRecord() ? AccessKind.FIELD : classAccess(info, ownerAccess);
        String key = type.getName() + "/" + access;
        EmbeddableModel known = embeddables.get(key);
        if (known != null) {
            return known;
        }
        List<AttributeModel> attributes = new ArrayList<>();
        for (Member member : members(info, access)) {
            attributes.add(attribute(member, Map.of()));
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
        List<BasicAttribute> ids = new ArrayList<>();
        Member single = null;
        for (Map.Entry<AttributeModel, Member> entry : members.entrySet()) {
            Annotated element = entry.getValue().element();
            if (element.isAnnotated(EMBEDDED_ID)) {
                return new IdModel.Embedded((EmbeddedAttribute) entry.getKey());
            }
            if (element.isAnnotated(ID)) {
                if (!(entry.getKey() instanceof BasicAttribute basic)) {
                    throw new PersistenceException("The identifier " + entry.getKey().name() + " of " + entity.name()
                        + " must be a basic attribute; an identifier of an embeddable type is an @EmbeddedId (§2.4)");
                }
                ids.add(basic);
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
        return new IdModel.Single(ids.getFirst(), generation(single, entity));
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
