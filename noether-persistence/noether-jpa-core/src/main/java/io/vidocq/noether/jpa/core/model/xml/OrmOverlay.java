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
package io.vidocq.noether.jpa.core.model.xml;

import io.vidocq.noether.jpa.core.model.build.AccessPlanner;
import io.vidocq.noether.jpa.core.model.source.AnnotationInfo;
import io.vidocq.noether.jpa.core.model.source.ClassFileSource;
import io.vidocq.noether.jpa.core.model.source.ClassInfo;
import io.vidocq.noether.jpa.core.model.source.ClassInfos;
import io.vidocq.noether.jpa.core.model.source.EnumValue;
import io.vidocq.noether.jpa.core.model.source.FieldInfo;
import io.vidocq.noether.jpa.core.model.source.MethodInfo;
import jakarta.persistence.PersistenceException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The managed classes of a persistence unit as its XML mapping files (Jakarta Persistence 3.2, chapter 12) and their
 * annotations describe them together: each class is read from its class file, then the elements of the mapping files
 * are written over it as the annotations they stand for (§12.1: the XML overrides the annotations it restates; a
 * {@code metadata-complete} mapping ignores them all). The model is then built from this view by the very builder and
 * access planner used without mapping files; no class is loaded or changed.
 * <p>
 * Every element Noether cannot honour is an error naming the mapping file and the line, never silently ignored.
 */
public final class OrmOverlay implements ClassInfos {

    private static final String JPA = "jakarta.persistence.";
    private static final String ENTITY = JPA + "Entity";
    private static final String MAPPED_SUPERCLASS = JPA + "MappedSuperclass";
    private static final String EMBEDDABLE = JPA + "Embeddable";
    private static final String ACCESS = JPA + "Access";
    private static final String ACCESS_TYPE = JPA + "AccessType";
    private static final String ID = JPA + "Id";
    private static final String EMBEDDED_ID = JPA + "EmbeddedId";
    private static final String TRANSIENT = JPA + "Transient";
    private static final String CONVERTER = JPA + "Converter";
    private static final String CASCADE_TYPE = JPA + "CascadeType";
    private static final String TABLE = JPA + "Table";
    private static final String SECONDARY_TABLE = JPA + "SecondaryTable";
    private static final String CONVERT = JPA + "Convert";
    /** The annotation members written more than once. */
    private static final String VALUE = "value";
    private static final String ATTRIBUTE_NAME = "attributeName";
    private static final String CASCADE = "cascade";
    /** The values of {@code AccessType}, as an {@code access} attribute writes them. */
    private static final String FIELD_ACCESS = "FIELD";
    private static final String PROPERTY_ACCESS = "PROPERTY";
    private static final String THE_CLASS = "the class ";
    private static final String CANNOT_BE_FOUND = " cannot be found";
    private static final List<String> CALLBACKS = List.of("pre-persist", "post-persist", "pre-remove", "post-remove", "pre-update",
        "post-update", "post-load");
    private static final Set<String> RELATIONSHIPS = Set.of(JPA + "ManyToOne", JPA + "OneToOne", JPA + "OneToMany",
        JPA + "ManyToMany");
    private static final Set<String> TABLES = Set.of(TABLE, SECONDARY_TABLE, JPA + "JoinTable",
        JPA + "CollectionTable", JPA + "SequenceGenerator", JPA + "TableGenerator");
    /** The attribute mappings of {@code <attributes>} and the annotation each one stands for (§12.2.3.x). */
    private static final Map<String, String> MAPPINGS = Map.ofEntries(Map.entry("id", ID), Map.entry("embedded-id", EMBEDDED_ID),
        Map.entry("basic", JPA + "Basic"), Map.entry("version", JPA + "Version"), Map.entry("many-to-one", JPA + "ManyToOne"),
        Map.entry("one-to-many", JPA + "OneToMany"), Map.entry("one-to-one", JPA + "OneToOne"),
        Map.entry("many-to-many", JPA + "ManyToMany"), Map.entry("element-collection", JPA + "ElementCollection"),
        Map.entry("embedded", JPA + "Embedded"));
    /** The subelements whose annotation member is not the camel case of their name. */
    private static final Map<String, String> MEMBERS = Map.of("entity-result", "entities", "constructor-result", "classes",
        "column-result", "columns", "field-result", "fields", "check-constraint", "check", "named-attribute-node", "attributeNodes",
        XmlNames.PRIMARY_KEY_JOIN_COLUMN, "pkJoinColumns", "constraint-mode", VALUE);
    private static final Map<String, String> PRIMITIVES = Map.of("boolean", "Z", "byte", "B", "char", "C", "short", "S", "int", "I",
        "long", "J", "float", "F", "double", "D");

    /** The context of the elements of one mapping file: its {@code package}, {@code schema}, {@code catalog} and {@code access}. */
    private record Context(MappingFile file, String packageName, String schema, String catalog, String access) {
    }

    /** An {@code entity}, {@code mapped-superclass}, {@code embeddable} or {@code converter} element. */
    private record Declaration(XmlElement element, Context context) {
    }

    /** A callback method a mapping file names, and where. */
    private record Named(String method, Context context, int line) {
    }

    /** A callback method to annotate: the method {@code method} with {@code parameters} parameters gets {@code @kind}. */
    private record Placement(String kind, String method, int parameters) {
    }

    private final ClassFileSource raw;
    private final Map<String, Declaration> declarations = new LinkedHashMap<>();
    private final List<String> mapped = new ArrayList<>();
    private final List<String> defaultListeners = new ArrayList<>();
    private final List<AnnotationInfo> unitAnnotations = new ArrayList<>();
    /** The callbacks of listener classes named by {@code entity-listener} subelements, by listener then annotation. */
    private final Map<String, Map<String, Named>> listenerCallbacks = new LinkedHashMap<>();
    /** The callback annotations written per class, resolved where the methods are declared. */
    private final Map<String, List<Placement>> placements = new HashMap<>();
    /** The callback annotations a mapping file replaces, per class. */
    private final Map<String, Set<String>> replacedCallbacks = new HashMap<>();
    private final Map<String, Optional<ClassInfo>> classes = new ConcurrentHashMap<>();
    private boolean metadataComplete;
    private boolean cascadePersist;
    private boolean delimitedIdentifiers;
    private String unitSchema;
    private String unitCatalog;
    private String unitAccess;

    private OrmOverlay(ClassFileSource raw) {
        this.raw = raw;
    }

    /**
     * The managed classes of a unit whose mapping files are {@code files}, read with {@code loader}. The files are
     * read and checked now: an invalid document, an unknown class, attribute or method fails here.
     */
    public static OrmOverlay of(List<MappingFile> files, ClassLoader loader) {
        OrmOverlay overlay = new OrmOverlay(new ClassFileSource(loader));
        Map<MappingFile, XmlElement> roots = new LinkedHashMap<>();
        for (MappingFile file : files) {
            roots.put(file, OrmXmlReader.read(file));
        }
        roots.forEach((file, root) -> overlay.unitMetadata(root, overlay.context(root, file)));
        roots.forEach((file, root) -> overlay.declare(root, overlay.context(root, file)));
        overlay.resolveCallbacks();
        for (Map.Entry<String, Declaration> declaration : overlay.declarations.entrySet()) {
            if (overlay.read(declaration.getKey()).isEmpty()) {
                throw error(declaration.getValue().context(), declaration.getValue().element(),
                    THE_CLASS + declaration.getKey() + CANNOT_BE_FOUND);
            }
        }
        return overlay;
    }

    @Override
    public Optional<ClassInfo> read(String binaryName) {
        Optional<ClassInfo> known = classes.get(binaryName);
        if (known != null) {
            return known;
        }
        // not computeIfAbsent: overlaying a class reads its superclasses
        Optional<ClassInfo> overlaid = raw.read(binaryName).map(this::overlay);
        Optional<ClassInfo> previous = classes.putIfAbsent(binaryName, overlaid);
        return previous != null ? previous : overlaid;
    }

    @Override
    public List<String> mappedClassNames() {
        return List.copyOf(mapped);
    }

    @Override
    public List<String> defaultListeners() {
        return List.copyOf(defaultListeners);
    }

    @Override
    public List<AnnotationInfo> unitAnnotations() {
        return List.copyOf(unitAnnotations);
    }

    @Override
    public boolean delimitedIdentifiers() {
        return delimitedIdentifiers;
    }

    // ---- reading the documents (§12.2.1, §12.2.2) -----------------------------------------------------------

    private Context context(XmlElement root, MappingFile file) {
        return new Context(file, root.childText(XmlNames.PACKAGE), root.childText(XmlNames.SCHEMA), root.childText(XmlNames.CATALOG),
            root.childText(XmlNames.ACCESS));
    }

    /** {@code persistence-unit-metadata} (§12.2.1): the defaults of the whole unit, whichever file writes them. */
    private void unitMetadata(XmlElement root, Context context) {
        Optional<XmlElement> metadata = root.child(XmlNames.PERSISTENCE_UNIT_METADATA);
        if (metadata.isEmpty()) {
            return;
        }
        metadataComplete |= metadata.get().child("xml-mapping-metadata-complete").isPresent();
        Optional<XmlElement> defaults = metadata.get().child("persistence-unit-defaults");
        if (defaults.isEmpty()) {
            return;
        }
        for (XmlElement element : defaults.get().children()) {
            switch (element.name()) {
                case XmlNames.DESCRIPTION -> {
                    // documentation only
                }
                case XmlNames.SCHEMA -> unitSchema = unitDefault(unitSchema, element, context);
                case XmlNames.CATALOG -> unitCatalog = unitDefault(unitCatalog, element, context);
                case XmlNames.ACCESS -> unitAccess = unitDefault(unitAccess, element, context);
                case "cascade-persist" -> cascadePersist = true;
                case "delimited-identifiers" -> delimitedIdentifiers = true;
                case XmlNames.ENTITY_LISTENERS -> {
                    for (XmlElement listener : element.children(XmlNames.ENTITY_LISTENER)) {
                        String name = listener(listener, context);
                        if (!defaultListeners.contains(name)) {
                            defaultListeners.add(name);
                        }
                    }
                }
                default -> throw unsupported(context, element);
            }
        }
    }

    private static String unitDefault(String known, XmlElement element, Context context) {
        String value = element.text().strip();
        if (known != null && !known.equals(value)) {
            throw error(context, element, "the persistence unit default <" + element.name() + "> is " + known
                + " in another mapping file, " + value + " here");
        }
        return value;
    }

    private void declare(XmlElement root, Context context) {
        for (XmlElement element : root.children()) {
            switch (element.name()) {
                case XmlNames.DESCRIPTION, XmlNames.PERSISTENCE_UNIT_METADATA, XmlNames.PACKAGE, XmlNames.SCHEMA, XmlNames.CATALOG,
                    XmlNames.ACCESS -> {
                    // documentation, or read by unitMetadata and context
                }
                case XmlNames.SEQUENCE_GENERATOR -> unitAnnotations.add(withSchema(
                    convert(JPA + "SequenceGenerator", element, context, Set.of()), schema(context), catalog(context)));
                case XmlNames.TABLE_GENERATOR -> unitAnnotations.add(withSchema(
                    convert(JPA + "TableGenerator", element, context, Set.of()), schema(context), catalog(context)));
                case XmlNames.NAMED_QUERY -> unitAnnotations.add(convert(JPA + "NamedQuery", element, context, Set.of()));
                case "named-native-query" -> unitAnnotations.add(convert(JPA + "NamedNativeQuery", element, context, Set.of()));
                case "named-stored-procedure-query" -> unitAnnotations.add(convert(JPA + "NamedStoredProcedureQuery", element, context,
                    Set.of()));
                case "sql-result-set-mapping" -> unitAnnotations.add(convert(JPA + "SqlResultSetMapping", element, context, Set.of()));
                case XmlNames.ENTITY, XmlNames.MAPPED_SUPERCLASS, XmlNames.EMBEDDABLE, XmlNames.CONVERTER -> {
                    String name = className(element.attribute(XmlNames.CLASS), context);
                    Declaration known = declarations.get(name);
                    if (known != null) {
                        throw error(context, element, THE_CLASS + name + " is already mapped at line " + known.element().line()
                            + " of " + known.context().file().name());
                    }
                    declarations.put(name, new Declaration(element, context));
                    mapped.add(name);
                    element.child(XmlNames.ENTITY_LISTENERS).ifPresent(listeners ->
                        listeners.children(XmlNames.ENTITY_LISTENER).forEach(listener -> listener(listener, context)));
                    for (XmlElement callback : element.children()) {
                        if (CALLBACKS.contains(callback.name())) {
                            replacedCallbacks.computeIfAbsent(name, _ -> new LinkedHashSet<>()).add(kind(callback.name()));
                        }
                    }
                }
                default -> throw unsupported(context, element);
            }
        }
    }

    /** An {@code entity-listener}: its class, and the callback methods its subelements name (§12.2.2.x). */
    private String listener(XmlElement listener, Context context) {
        String name = className(listener.attribute(XmlNames.CLASS), context);
        for (XmlElement callback : listener.children()) {
            if (callback.name().equals(XmlNames.DESCRIPTION)) {
                continue;
            }
            String kind = kind(callback.name());
            Named named = new Named(callback.attribute(XmlNames.METHOD_NAME), context, callback.line());
            Named known = listenerCallbacks.computeIfAbsent(name, _ -> new LinkedHashMap<>()).putIfAbsent(kind, named);
            if (known != null && !known.method().equals(named.method())) {
                throw error(context, callback, "the " + kind + " method of the listener " + name + " is " + known.method()
                    + " at line " + known.line() + " of " + known.context().file().name() + ", not " + named.method());
            }
        }
        return name;
    }

    /**
     * Where the callback methods the mapping files name are declared: a method of the class or of one of its
     * superclasses, without parameter for an entity or mapped superclass, with one (the entity) for a listener.
     */
    private void resolveCallbacks() {
        listenerCallbacks.forEach((listener, callbacks) -> {
            replacedCallbacks.computeIfAbsent(listener, _ -> new LinkedHashSet<>()).addAll(callbacks.keySet());
            callbacks.forEach((kind, named) -> place(listener, kind, named, 1));
        });
        for (Map.Entry<String, Declaration> declaration : declarations.entrySet()) {
            for (XmlElement callback : declaration.getValue().element().children()) {
                if (CALLBACKS.contains(callback.name())) {
                    place(declaration.getKey(), kind(callback.name()),
                        new Named(callback.attribute(XmlNames.METHOD_NAME), declaration.getValue().context(), callback.line()), 0);
                }
            }
        }
        for (String listener : defaultListeners) {
            if (raw.read(listener).isEmpty()) {
                throw new PersistenceException("The default entity listener " + listener + " of the mapping files cannot be found");
            }
        }
    }

    private void place(String className, String kind, Named named, int parameters) {
        Optional<ClassInfo> current = raw.read(className);
        while (current.isPresent()) {
            ClassInfo info = current.get();
            boolean declared = info.methods().stream().anyMatch(m -> m.name().equals(named.method()) && !m.isStatic()
                && m.type().parameterCount() == parameters);
            if (declared) {
                placements.computeIfAbsent(info.name(), _ -> new ArrayList<>()).add(new Placement(kind, named.method(), parameters));
                return;
            }
            String superclass = info.superclassName();
            current = superclass == null || superclass.startsWith("java.") ? Optional.empty() : raw.read(superclass);
        }
        if (raw.read(className).isEmpty()) {
            throw OrmXmlReader.error(named.context().file(), named.line(), THE_CLASS + className + CANNOT_BE_FOUND, null);
        }
        throw OrmXmlReader.error(named.context().file(), named.line(), "the " + kind + " callback " + named.method() + " of "
            + className + " is not a method " + (parameters == 0 ? "without parameter" : "with one parameter")
            + " of the class or its superclasses (§3.6.1)", null);
    }

    // ---- overlaying a class -----------------------------------------------------------------------------------

    private ClassInfo overlay(ClassInfo info) {
        String name = info.name();
        Declaration declaration = declarations.get(name);
        Set<String> replaced = replacedCallbacks.getOrDefault(name, Set.of());
        List<Placement> placed = placements.getOrDefault(name, List.of());
        String schema = declaration != null ? schema(declaration.context()) : unitSchema;
        String catalog = declaration != null ? catalog(declaration.context()) : unitCatalog;
        if (declaration == null && replaced.isEmpty() && placed.isEmpty() && !metadataComplete && !cascadePersist && schema == null
                && catalog == null) {
            return info;
        }
        boolean complete = metadataComplete || declaration != null && "true".equals(declaration.element().attribute("metadata-complete"));
        List<AnnotationInfo> type = kept(info.annotations(), complete);
        List<List<AnnotationInfo>> fields = new ArrayList<>();
        info.fields().forEach(f -> fields.add(kept(f.annotations(), complete)));
        List<List<AnnotationInfo>> methods = new ArrayList<>();
        info.methods().forEach(m -> methods.add(kept(m.annotations(), complete)));
        if (declaration != null) {
            declared(info, declaration, type, fields, methods);
        }
        for (String kind : replaced) {
            methods.forEach(annotations -> remove(annotations, JPA + kind));
        }
        for (Placement placement : placed) {
            placeCallback(info, placement, methods);
        }
        if (schema != null || catalog != null) {
            defaultSchemas(type, fields, methods, schema, catalog);
        }
        if (cascadePersist) {
            fields.forEach(this::cascadePersist);
            methods.forEach(this::cascadePersist);
        }
        return overlaid(info, type, fields, methods);
    }

    /** The annotations a class keeps: all of them, or, for a metadata-complete mapping, none of Jakarta Persistence (§12.2.3.1). */
    private static List<AnnotationInfo> kept(List<AnnotationInfo> annotations, boolean complete) {
        List<AnnotationInfo> kept = new ArrayList<>();
        for (AnnotationInfo annotation : annotations) {
            if (!complete || !annotation.typeName().startsWith(JPA)) {
                kept.add(annotation);
            }
        }
        return kept;
    }

    /** Writes the callback annotation of {@code placement} on the method it names (§3.6.1). */
    private void placeCallback(ClassInfo info, Placement placement, List<List<AnnotationInfo>> methods) {
        for (int m = 0; m < info.methods().size(); m++) {
            MethodInfo method = info.methods().get(m);
            if (method.name().equals(placement.method()) && !method.isStatic()
                    && method.type().parameterCount() == placement.parameters()) {
                replace(methods.get(m), JPA + placement.kind(), raw.annotation(JPA + placement.kind(), Map.of()));
                return;
            }
        }
    }

    /** §12.2.1.x, §12.2.2.x: the default schema and catalog, on the tables and generators of a class that do not name theirs. */
    private void defaultSchemas(List<AnnotationInfo> type, List<List<AnnotationInfo>> fields, List<List<AnnotationInfo>> methods,
            String schema, String catalog) {
        if (type.stream().anyMatch(a -> a.typeName().equals(ENTITY)) && type.stream().noneMatch(a -> a.typeName().equals(TABLE))) {
            type.add(raw.annotation(TABLE, Map.of()));
        }
        schemas(type, schema, catalog);
        fields.forEach(annotations -> schemas(annotations, schema, catalog));
        methods.forEach(annotations -> schemas(annotations, schema, catalog));
    }

    /** {@code info} with the annotations of the class, of its fields and of its methods replaced. */
    private static ClassInfo overlaid(ClassInfo info, List<AnnotationInfo> type, List<List<AnnotationInfo>> fields,
            List<List<AnnotationInfo>> methods) {
        List<FieldInfo> newFields = new ArrayList<>();
        for (int f = 0; f < info.fields().size(); f++) {
            FieldInfo field = info.fields().get(f);
            newFields.add(new FieldInfo(field.name(), field.type(), field.genericSignature(), field.flags(), fields.get(f)));
        }
        List<MethodInfo> newMethods = new ArrayList<>();
        for (int m = 0; m < info.methods().size(); m++) {
            MethodInfo method = info.methods().get(m);
            newMethods.add(new MethodInfo(method.name(), method.type(), method.genericSignature(), method.flags(), methods.get(m)));
        }
        return new ClassInfo(info.name(), info.superclassName(), info.interfaceNames(), info.flags(), info.genericSignature(),
            info.isRecord(), type, newFields, newMethods);
    }

    /** An {@code entity}, {@code mapped-superclass}, {@code embeddable} (§12.2.3 to §12.2.5) or {@code converter} element. */
    private void declared(ClassInfo info, Declaration declaration, List<AnnotationInfo> type, List<List<AnnotationInfo>> fields,
            List<List<AnnotationInfo>> methods) {
        XmlElement element = declaration.element();
        if (element.name().equals(XmlNames.CONVERTER)) {
            String autoApply = element.attribute("auto-apply");
            remove(type, CONVERTER);
            type.add(raw.annotation(CONVERTER, autoApply == null ? Map.of() : Map.of("autoApply", parseBoolean(autoApply))));
            return;
        }
        Context context = declaration.context();
        managedType(element, type);
        String access = access(info, element, context, type, fields, methods);
        if (element.attribute("cacheable") != null) {
            replace(type, JPA + "Cacheable", raw.annotation(JPA + "Cacheable", Map.of(VALUE, parseBoolean(element.attribute("cacheable")))));
        }
        classElements(element, context, type);
        Optional<XmlElement> attributes = element.child(XmlNames.ATTRIBUTES);
        if (attributes.isPresent()) {
            for (XmlElement attribute : attributes.get().children()) {
                if (!attribute.name().equals(XmlNames.DESCRIPTION)) {
                    attribute(info, attribute, context, access, fields, methods);
                }
            }
        }
    }

    /** The {@code @Entity}, {@code @MappedSuperclass} or {@code @Embeddable} an element stands for, with its entity name. */
    private void managedType(XmlElement element, List<AnnotationInfo> type) {
        String kind = switch (element.name()) {
            case XmlNames.ENTITY -> ENTITY;
            case XmlNames.MAPPED_SUPERCLASS -> MAPPED_SUPERCLASS;
            default -> EMBEDDABLE;
        };
        Map<String, Object> written = new LinkedHashMap<>();
        if (kind.equals(ENTITY)) {
            String entityName = element.attribute("name");
            if (entityName == null) {
                // §12.2.3.1: without a name, the one the annotation gives, if its annotations are read
                entityName = type.stream().filter(a -> a.typeName().equals(ENTITY)).findFirst().map(a -> a.string("name"))
                    .filter(n -> !n.isEmpty()).orElse(null);
            }
            if (entityName != null) {
                written.put("name", entityName);
            }
        }
        remove(type, ENTITY, MAPPED_SUPERCLASS, EMBEDDABLE);
        type.add(raw.annotation(kind, written));
    }

    /** The subelements of an {@code entity}, {@code mapped-superclass} or {@code embeddable} that stand for class annotations. */
    private void classElements(XmlElement element, Context context, List<AnnotationInfo> type) {
        Map<String, List<AnnotationInfo>> merged = new LinkedHashMap<>();
        List<AnnotationInfo> secondaryTables = new ArrayList<>();
        List<AnnotationInfo> primaryKeyJoinColumns = new ArrayList<>();
        for (XmlElement child : element.children()) {
            switch (child.name()) {
                case XmlNames.DESCRIPTION, XmlNames.ATTRIBUTES, "pre-persist", "post-persist", "pre-remove", "post-remove", "pre-update",
                    "post-update", "post-load" -> {
                    // documentation, or read by declared (attributes) and by resolveCallbacks (callbacks)
                }
                case "table" -> replace(type, TABLE, convert(TABLE, child, context, Set.of()));
                case "secondary-table" -> secondaryTables.add(convert(SECONDARY_TABLE, child, context, Set.of()));
                case XmlNames.PRIMARY_KEY_JOIN_COLUMN -> primaryKeyJoinColumns.add(convert(JPA + "PrimaryKeyJoinColumn", child, context,
                    Set.of()));
                case "id-class" -> replace(type, JPA + "IdClass", convert(JPA + "IdClass", child, context, Set.of()));
                case "inheritance" -> replace(type, JPA + "Inheritance", convert(JPA + "Inheritance", child, context, Set.of()));
                case "discriminator-value" -> replace(type, JPA + "DiscriminatorValue", text(JPA + "DiscriminatorValue", child, context));
                case "discriminator-column" -> replace(type, JPA + "DiscriminatorColumn",
                    convert(JPA + "DiscriminatorColumn", child, context, Set.of()));
                case XmlNames.SEQUENCE_GENERATOR, XmlNames.TABLE_GENERATOR, XmlNames.NAMED_QUERY, "named-native-query",
                    "named-stored-procedure-query", "sql-result-set-mapping", "named-entity-graph", XmlNames.ATTRIBUTE_OVERRIDE,
                    XmlNames.CONVERT -> {
                    String annotation = JPA + camel(child.name(), true);
                    merged.computeIfAbsent(annotation, _ -> new ArrayList<>()).add(convert(annotation, child, context, Set.of()));
                }
                case XmlNames.ASSOCIATION_OVERRIDE -> merged.computeIfAbsent(JPA + "AssociationOverride", _ -> new ArrayList<>())
                    .add(convert(JPA + "AssociationOverride", child, context, Set.of()));
                case "exclude-default-listeners" -> replace(type, JPA + "ExcludeDefaultListeners",
                    raw.annotation(JPA + "ExcludeDefaultListeners", Map.of()));
                case "exclude-superclass-listeners" -> replace(type, JPA + "ExcludeSuperclassListeners",
                    raw.annotation(JPA + "ExcludeSuperclassListeners", Map.of()));
                case XmlNames.ENTITY_LISTENERS -> {
                    List<ClassDesc> listeners = new ArrayList<>();
                    child.children(XmlNames.ENTITY_LISTENER).forEach(l ->
                        listeners.add(ClassDesc.of(className(l.attribute(XmlNames.CLASS), context))));
                    replace(type, JPA + "EntityListeners", raw.annotation(JPA + "EntityListeners", Map.of(VALUE, listeners)));
                }
                default -> throw unsupported(context, child);
            }
        }
        if (!secondaryTables.isEmpty()) {
            group(type, SECONDARY_TABLE, secondaryTables);
        }
        if (!primaryKeyJoinColumns.isEmpty()) {
            group(type, JPA + "PrimaryKeyJoinColumn", primaryKeyJoinColumns);
        }
        merged.forEach((annotation, written) -> merge(type, annotation, written,
            annotation.equals(CONVERT) ? ATTRIBUTE_NAME : "name"));
    }

    /**
     * The access type of a class (§12.2.3.x, §2.3): its {@code access} attribute, else its {@code @Access} (unless
     * metadata-complete), else the {@code access} of its mapping file, else the default of the unit; each of those is
     * written as {@code @Access}. Otherwise the placement of the identifier decides, as for annotations, the mapping
     * file's identifier included; {@code null} when nothing decides it (an embeddable then takes its owner's).
     */
    private String access(ClassInfo info, XmlElement element, Context context, List<AnnotationInfo> type,
            List<List<AnnotationInfo>> fields, List<List<AnnotationInfo>> methods) {
        String written = element.attribute(XmlNames.ACCESS);
        if (written == null) {
            Optional<AnnotationInfo> annotated = type.stream().filter(a -> a.typeName().equals(ACCESS)).findFirst();
            if (annotated.isPresent()) {
                return annotated.get().enumConstant(VALUE);
            }
            written = context.access() != null ? context.access() : unitAccess;
        }
        if (written != null) {
            replace(type, ACCESS, raw.annotation(ACCESS, Map.of(VALUE, new EnumValue(ACCESS_TYPE, written))));
            return written;
        }
        if (fields.stream().anyMatch(OrmOverlay::identifier)) {
            return FIELD_ACCESS;
        }
        if (methods.stream().anyMatch(OrmOverlay::identifier)) {
            return PROPERTY_ACCESS;
        }
        Optional<XmlElement> id = element.child(XmlNames.ATTRIBUTES).flatMap(a -> a.children().stream()
            .filter(c -> c.name().equals("id") || c.name().equals("embedded-id")).findFirst());
        if (id.isPresent()) {
            return info.field(id.get().attribute("name")).isPresent() ? FIELD_ACCESS : PROPERTY_ACCESS;
        }
        return inheritedAccess(info.superclassName());
    }

    /** Whether the annotations of a member make it an identifier. */
    private static boolean identifier(List<AnnotationInfo> annotations) {
        return annotated(annotations, ID) || annotated(annotations, EMBEDDED_ID);
    }

    private static boolean annotated(List<AnnotationInfo> annotations, String type) {
        return annotations.stream().anyMatch(a -> a.typeName().equals(type));
    }

    /** The access type the first superclass that decides one gives, from {@code superclassName} up; {@code null} if none does. */
    private String inheritedAccess(String superclassName) {
        String superclass = superclassName;
        while (superclass != null && !superclass.startsWith("java.")) {
            Optional<ClassInfo> parent = read(superclass);
            if (parent.isEmpty()) {
                return null;
            }
            String decided = decidedAccess(parent.get());
            if (decided != null) {
                return decided;
            }
            superclass = parent.get().superclassName();
        }
        return null;
    }

    /** The access type a class decides: its {@code @Access}, else the placement of its identifier; {@code null} if neither. */
    private static String decidedAccess(ClassInfo info) {
        Optional<AnnotationInfo> explicit = info.annotation(ACCESS);
        if (explicit.isPresent()) {
            return explicit.get().enumConstant(VALUE);
        }
        if (info.fields().stream().anyMatch(f -> f.isAnnotated(ID) || f.isAnnotated(EMBEDDED_ID))) {
            return FIELD_ACCESS;
        }
        if (info.methods().stream().anyMatch(m -> m.isAnnotated(ID) || m.isAnnotated(EMBEDDED_ID))) {
            return PROPERTY_ACCESS;
        }
        return null;
    }

    // ---- attributes (§12.2.3.x) ------------------------------------------------------------------------------

    /**
     * An element of {@code <attributes>}: the annotations it stands for replace those of the field and the property of
     * that name, on the member its access type reads (both when the class leaves it to its owner).
     */
    private void attribute(ClassInfo info, XmlElement attribute, Context context, String classAccess,
            List<List<AnnotationInfo>> fields, List<List<AnnotationInfo>> methods) {
        String name = attribute.attribute("name");
        int fieldIndex = fieldIndex(info, name);
        int getterIndex = getterIndex(info, name);
        List<AnnotationInfo> field = fieldIndex < 0 ? null : fields.get(fieldIndex);
        List<AnnotationInfo> getter = getterIndex < 0 ? null : methods.get(getterIndex);
        if (field == null && getter == null) {
            throw error(context, attribute, THE_CLASS + info.name() + " has no field or property " + name);
        }
        if (field != null) {
            removeJpa(field);
        }
        if (getter != null) {
            removeJpa(getter);
        }
        if (attribute.name().equals("transient")) {
            AnnotationInfo transientAnnotation = raw.annotation(TRANSIENT, Map.of());
            if (field != null) {
                field.add(transientAnnotation);
            }
            if (getter != null) {
                getter.add(transientAnnotation);
            }
            return;
        }
        persistent(info, attribute, context, classAccess, field, getter);
    }

    /** The index of the instance field named {@code name}, or -1. */
    private static int fieldIndex(ClassInfo info, String name) {
        int field = -1;
        for (int f = 0; f < info.fields().size(); f++) {
            if (info.fields().get(f).name().equals(name) && !info.fields().get(f).isStatic()) {
                field = f;
            }
        }
        return field;
    }

    /** The index of the getter of the property named {@code name}, or -1. */
    private static int getterIndex(ClassInfo info, String name) {
        int getter = -1;
        for (int m = 0; m < info.methods().size(); m++) {
            if (name.equals(AccessPlanner.propertyName(info.methods().get(m)))) {
                getter = m;
            }
        }
        return getter;
    }

    /**
     * A persistent attribute: the annotations its element stands for, on the field ({@code null} if none), the getter
     * ({@code null} if none) or both, as its access type reads them.
     */
    private void persistent(ClassInfo info, XmlElement attribute, Context context, String classAccess, List<AnnotationInfo> field,
            List<AnnotationInfo> getter) {
        String memberAccess = attribute.attribute(XmlNames.ACCESS);
        String access = memberAccess != null ? memberAccess : classAccess;
        if (FIELD_ACCESS.equals(access) && field == null || PROPERTY_ACCESS.equals(access) && getter == null) {
            throw error(context, attribute, THE_CLASS + info.name() + " has no " + (FIELD_ACCESS.equals(access) ? "field " : "property ")
                + attribute.attribute("name") + " for its " + access + " access");
        }
        List<AnnotationInfo> annotations = mapping(attribute, context);
        if (memberAccess != null) {
            ownAccess(memberAccess, annotations, field, getter);
        }
        if (!PROPERTY_ACCESS.equals(access) && field != null) {
            field.addAll(annotations);
        }
        if (!FIELD_ACCESS.equals(access) && getter != null) {
            getter.addAll(annotations);
        }
    }

    /**
     * An attribute's own {@code access}, written as {@code @Access}; §2.3.2: the member the access type of its class
     * would read is not persistent besides the one it names.
     */
    private void ownAccess(String memberAccess, List<AnnotationInfo> annotations, List<AnnotationInfo> field,
            List<AnnotationInfo> getter) {
        annotations.add(raw.annotation(ACCESS, Map.of(VALUE, new EnumValue(ACCESS_TYPE, memberAccess))));
        if (memberAccess.equals(FIELD_ACCESS) && getter != null) {
            getter.add(raw.annotation(TRANSIENT, Map.of()));
        } else if (memberAccess.equals(PROPERTY_ACCESS) && field != null) {
            field.add(raw.annotation(TRANSIENT, Map.of()));
        }
    }

    /** The annotations an attribute mapping element stands for. */
    private List<AnnotationInfo> mapping(XmlElement attribute, Context context) {
        String main = MAPPINGS.get(attribute.name());
        if (main == null) {
            throw unsupported(context, attribute);
        }
        List<AnnotationInfo> annotations = new ArrayList<>();
        AnnotationInfo annotation = convertAttributes(main, attribute, context, Set.of("name", XmlNames.ACCESS, XmlNames.MAPS_ID, "id"));
        if (RELATIONSHIPS.contains(main)) {
            List<EnumValue> cascade = new ArrayList<>();
            attribute.child(XmlNames.CASCADE).ifPresent(c -> c.children().forEach(type ->
                cascade.add(new EnumValue(CASCADE_TYPE, type.name().substring("cascade-".length()).toUpperCase(java.util.Locale.ROOT)))));
            if (!cascade.isEmpty()) {
                annotation = annotation.with(CASCADE, cascade);
            }
        }
        annotations.add(annotation);
        if (attribute.attribute(XmlNames.MAPS_ID) != null) {
            annotations.add(raw.annotation(JPA + "MapsId", Map.of(VALUE, attribute.attribute(XmlNames.MAPS_ID))));
        }
        if ("true".equals(attribute.attribute("id")) || "1".equals(attribute.attribute("id"))) {
            annotations.add(raw.annotation(ID, Map.of()));
        }
        Map<String, List<AnnotationInfo>> repeated = new LinkedHashMap<>();
        for (XmlElement child : attribute.children()) {
            switch (child.name()) {
                case XmlNames.CASCADE -> {
                    // read above, with the relationship
                }
                case "column", "join-table", "collection-table", "order-column", "map-key", "map-key-class", "map-key-column",
                    "generated-value", XmlNames.SEQUENCE_GENERATOR, XmlNames.TABLE_GENERATOR ->
                    annotations.add(convert(JPA + camel(child.name(), true), child, context, Set.of()));
                case "order-by" -> annotations.add(raw.annotation(JPA + "OrderBy", Map.of(VALUE, child.text().strip())));
                case "temporal", "enumerated", "map-key-temporal", "map-key-enumerated" ->
                    annotations.add(text(JPA + camel(child.name(), true), child, context));
                case "lob" -> annotations.add(raw.annotation(JPA + "Lob", Map.of()));
                case "join-column", XmlNames.PRIMARY_KEY_JOIN_COLUMN, "map-key-join-column", XmlNames.ATTRIBUTE_OVERRIDE,
                    XmlNames.ASSOCIATION_OVERRIDE, XmlNames.CONVERT -> {
                    String type = JPA + camel(child.name(), true);
                    repeated.computeIfAbsent(type, _ -> new ArrayList<>()).add(convert(type, child, context, Set.of()));
                }
                case "map-key-attribute-override" -> {
                    AnnotationInfo override = convert(JPA + "AttributeOverride", child, context, Set.of());
                    // §11.1.4: the overrides of an embeddable map key name its attributes "key.<attribute>"
                    repeated.computeIfAbsent(JPA + "AttributeOverride", _ -> new ArrayList<>())
                        .add(override.with("name", "key." + override.string("name")));
                }
                case "map-key-convert" -> {
                    AnnotationInfo convert = convert(CONVERT, child, context, Set.of());
                    String path = convert.isWritten(ATTRIBUTE_NAME) ? "key." + convert.string(ATTRIBUTE_NAME) : "key";
                    repeated.computeIfAbsent(CONVERT, _ -> new ArrayList<>()).add(convert.with(ATTRIBUTE_NAME, path));
                }
                default -> throw unsupported(context, child);
            }
        }
        repeated.forEach((type, values) -> group(annotations, type, values));
        return annotations;
    }

    // ---- elements to annotations ------------------------------------------------------------------------------

    /** The annotation {@code type} an element stands for: its attributes and subelements are the members of the same name. */
    private AnnotationInfo convert(String type, XmlElement element, Context context, Set<String> skipped) {
        Map<String, ClassDesc> members = members(type, element, context);
        Map<String, Object> written = attributes(type, members, element, context, skipped);
        for (XmlElement child : element.children()) {
            if (child.name().equals(XmlNames.DESCRIPTION)) {
                continue;
            }
            String member = member(type, members, child.name(), element, context);
            ClassDesc memberType = members.get(member);
            boolean array = memberType.isArray();
            ClassDesc component = array ? memberType.componentType() : memberType;
            Object value = isAnnotation(component) ? convert(ClassFileSource.binaryName(component), child, context, Set.of())
                : value(component, child.text().strip(), child, context);
            put(written, member, value, array);
        }
        return raw.annotation(type, written);
    }

    /** The annotation {@code type} from the attributes of an element only, its subelements read by the caller. */
    private AnnotationInfo convertAttributes(String type, XmlElement element, Context context, Set<String> skipped) {
        return raw.annotation(type, attributes(type, members(type, element, context), element, context, skipped));
    }

    private Map<String, Object> attributes(String type, Map<String, ClassDesc> members, XmlElement element, Context context,
            Set<String> skipped) {
        Map<String, Object> written = new LinkedHashMap<>();
        for (Map.Entry<String, String> attribute : element.attributes().entrySet()) {
            if (skipped.contains(attribute.getKey())) {
                continue;
            }
            String member = member(type, members, attribute.getKey(), element, context);
            ClassDesc memberType = members.get(member);
            boolean array = memberType.isArray();
            put(written, member, value(array ? memberType.componentType() : memberType, attribute.getValue(), element, context), array);
        }
        return written;
    }

    /** An annotation whose {@code value} is the text of the element: {@code <temporal>DATE</temporal>}. */
    private AnnotationInfo text(String type, XmlElement element, Context context) {
        Map<String, ClassDesc> members = members(type, element, context);
        return raw.annotation(type, Map.of(VALUE, value(members.get(VALUE), element.text().strip(), element, context)));
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> written, String member, Object value, boolean array) {
        if (array) {
            ((List<Object>) written.computeIfAbsent(member, _ -> new ArrayList<>())).add(value);
        } else {
            written.put(member, value);
        }
    }

    private Map<String, ClassDesc> members(String type, XmlElement element, Context context) {
        Map<String, ClassDesc> members = raw.annotationMembers(type);
        if (members.isEmpty() && !type.equals(JPA + "Lob") && !type.equals(ID) && !type.equals(EMBEDDED_ID) && !type.equals(JPA + "Version")
                && !type.equals(JPA + "Embedded") && !type.equals(TRANSIENT)) {
            throw error(context, element, "the annotation type " + type + CANNOT_BE_FOUND);
        }
        return members;
    }

    /** The member an attribute or subelement names: its camel case, plural for an array, or a known exception. */
    private static String member(String type, Map<String, ClassDesc> members, String xmlName, XmlElement element, Context context) {
        List<String> candidates = new ArrayList<>();
        if (xmlName.equals(XmlNames.CLASS)) {
            candidates.add("type");
            candidates.add(VALUE);
        } else if (xmlName.equals("name") && type.equals(JPA + "NamedAttributeNode")) {
            candidates.add(VALUE);
        }
        if (MEMBERS.containsKey(xmlName)) {
            candidates.add(MEMBERS.get(xmlName));
        }
        String camel = camel(xmlName, false);
        candidates.add(camel);
        candidates.add(camel + "s");
        candidates.add(camel + "es");
        for (String candidate : candidates) {
            if (members.containsKey(candidate)) {
                return candidate;
            }
        }
        throw error(context, element, "<" + element.name() + "> has no " + xmlName + " Noether can map to @"
            + type.substring(type.lastIndexOf('.') + 1));
    }

    /** A written value, as {@link ClassFileSource} encodes the member type {@code type}. */
    private Object value(ClassDesc type, String text, XmlElement element, Context context) {
        String descriptor = type.descriptorString();
        try {
            return switch (descriptor) {
                case "Ljava/lang/String;" -> text;
                case "Z" -> parseBoolean(text.strip());
                case "I" -> Integer.parseInt(text.strip());
                case "J" -> Long.parseLong(text.strip());
                case "S" -> Short.parseShort(text.strip());
                case "B" -> Byte.parseByte(text.strip());
                case "F" -> Float.parseFloat(text.strip());
                case "D" -> Double.parseDouble(text.strip());
                case "C" -> text.charAt(0);
                case "Ljava/lang/Class;" -> classDesc(text.strip(), context);
                default -> {
                    String name = ClassFileSource.binaryName(type);
                    Optional<ClassInfo> info = raw.read(name);
                    if (info.isPresent() && info.get().isEnum()) {
                        String constant = text.strip();
                        if (info.get().field(constant).isEmpty()) {
                            throw error(context, element, constant + " is not a constant of " + name);
                        }
                        yield new EnumValue(name, constant);
                    }
                    throw error(context, element, "a value of type " + name + " cannot be written in a mapping file");
                }
            };
        } catch (NumberFormatException | StringIndexOutOfBoundsException _) {
            throw error(context, element, "'" + text + "' is not a " + type.displayName());
        }
    }

    private boolean isAnnotation(ClassDesc type) {
        return type.isClassOrInterface() && raw.read(ClassFileSource.binaryName(type))
            .map(i -> (i.flags() & ClassFile.ACC_ANNOTATION) != 0).orElse(false);
    }

    private static Boolean parseBoolean(String text) {
        return "true".equals(text) || "1".equals(text);
    }

    /** {@code pre-persist} to {@code PrePersist}. */
    private static String kind(String element) {
        return camel(element, true);
    }

    private static String camel(String xmlName, boolean capitalized) {
        StringBuilder camel = new StringBuilder();
        boolean upper = capitalized;
        for (char c : xmlName.toCharArray()) {
            if (c == '-') {
                upper = true;
            } else {
                camel.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return camel.toString();
    }

    // ---- class names (§12.2.2.2) --------------------------------------------------------------------------------

    /** A class a mapping file names: unqualified, it is in the {@code package} of the file. */
    private String className(String written, Context context) {
        String name = written.strip();
        if (name.contains(".") || context.packageName() == null || context.packageName().isBlank()) {
            return name;
        }
        String qualified = context.packageName().strip() + "." + name;
        if (raw.read(qualified).isEmpty() && raw.read("java.lang." + name).isPresent()) {
            return "java.lang." + name;
        }
        return qualified;
    }

    private ClassDesc classDesc(String written, Context context) {
        if (written.endsWith("[]")) {
            return classDesc(written.substring(0, written.length() - 2).strip(), context).arrayType();
        }
        String primitive = PRIMITIVES.get(written);
        if (primitive != null) {
            return ClassDesc.ofDescriptor(primitive);
        }
        if (written.equals("void")) {
            return ClassDesc.ofDescriptor("V");
        }
        return ClassDesc.of(className(written, context));
    }

    // ---- schemas, cascades and annotation lists ------------------------------------------------------------------

    private String schema(Context context) {
        return context.schema() != null ? context.schema() : unitSchema;
    }

    private String catalog(Context context) {
        return context.catalog() != null ? context.catalog() : unitCatalog;
    }

    /** §12.2.1.x, §12.2.2.x: the default schema and catalog of the tables and generators that do not name theirs. */
    private void schemas(List<AnnotationInfo> annotations, String schema, String catalog) {
        annotations.replaceAll(a -> withSchema(a, schema, catalog));
    }

    private AnnotationInfo withSchema(AnnotationInfo annotation, String schema, String catalog) {
        String type = annotation.typeName();
        if (TABLES.contains(type)) {
            if (schema != null && !annotation.isWritten("schema")) {
                annotation = annotation.with("schema", schema);
            }
            if (catalog != null && !annotation.isWritten("catalog")) {
                annotation = annotation.with("catalog", catalog);
            }
            return annotation;
        }
        if (type.startsWith(JPA) && type.endsWith("s") && TABLES.contains(type.substring(0, type.length() - 1))) {
            List<AnnotationInfo> values = new ArrayList<>(annotation.annotations(VALUE));
            values.replaceAll(a -> withSchema(a, schema, catalog));
            return annotation.with(VALUE, values);
        }
        return annotation;
    }

    /** §12.2.1.x {@code cascade-persist}: every relationship of the unit cascades the persist operation. */
    private void cascadePersist(List<AnnotationInfo> annotations) {
        annotations.replaceAll(a -> {
            if (!RELATIONSHIPS.contains(a.typeName()) || a.enumConstants(CASCADE).contains("PERSIST")
                    || a.enumConstants(CASCADE).contains("ALL")) {
                return a;
            }
            List<Object> cascade = new ArrayList<>(a.isWritten(CASCADE) ? listOf(a.value(CASCADE)) : List.of());
            cascade.add(new EnumValue(CASCADE_TYPE, "PERSIST"));
            return a.with(CASCADE, cascade);
        });
    }

    private static List<?> listOf(Object value) {
        return value instanceof List<?> list ? list : List.of(value);
    }

    private static void remove(List<AnnotationInfo> annotations, String... types) {
        Set<String> removed = Set.of(types);
        annotations.removeIf(a -> removed.contains(a.typeName()));
    }

    private static void removeJpa(List<AnnotationInfo> annotations) {
        annotations.removeIf(a -> a.typeName().startsWith(JPA));
    }

    private static void replace(List<AnnotationInfo> annotations, String type, AnnotationInfo by) {
        remove(annotations, type);
        annotations.add(by);
    }

    private static String container(String type) {
        return type.endsWith("Query") ? type.substring(0, type.length() - 1) + "ies" : type + "s";
    }

    /** Replaces the {@code type} annotations, single or in their container, with {@code values}. */
    private void group(List<AnnotationInfo> annotations, String type, List<AnnotationInfo> values) {
        remove(annotations, type, container(type));
        annotations.add(values.size() == 1 ? values.getFirst() : raw.annotation(container(type), Map.of(VALUE, values)));
    }

    /** Adds {@code values} to the {@code type} annotations, each replacing the annotated one with the same {@code key}. */
    private void merge(List<AnnotationInfo> annotations, String type, List<AnnotationInfo> values, String key) {
        List<AnnotationInfo> all = new ArrayList<>();
        for (AnnotationInfo annotation : annotations) {
            if (annotation.typeName().equals(type)) {
                all.add(annotation);
            } else if (annotation.typeName().equals(container(type))) {
                all.addAll(annotation.annotations(VALUE));
            }
        }
        for (AnnotationInfo value : values) {
            String name = value.string(key);
            int known = -1;
            for (int i = 0; i < all.size(); i++) {
                if (name != null && !name.isEmpty() && name.equals(all.get(i).string(key))) {
                    known = i;
                }
            }
            if (known >= 0) {
                all.set(known, value);
            } else {
                all.add(value);
            }
        }
        group(annotations, type, all);
    }

    // ---- errors -------------------------------------------------------------------------------------------------

    private static PersistenceException error(Context context, XmlElement element, String message) {
        return OrmXmlReader.error(context.file(), element.line(), message, null);
    }

    private static PersistenceException unsupported(Context context, XmlElement element) {
        return error(context, element, "<" + element.name() + "> is not supported by Noether");
    }
}
