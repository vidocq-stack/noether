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
package io.vidocq.mansart.jpa.core.model.xml;

import io.vidocq.mansart.jpa.core.model.build.AccessPlanner;
import io.vidocq.mansart.jpa.core.model.source.AnnotationInfo;
import io.vidocq.mansart.jpa.core.model.source.ClassFileSource;
import io.vidocq.mansart.jpa.core.model.source.ClassInfo;
import io.vidocq.mansart.jpa.core.model.source.ClassInfos;
import io.vidocq.mansart.jpa.core.model.source.EnumValue;
import io.vidocq.mansart.jpa.core.model.source.FieldInfo;
import io.vidocq.mansart.jpa.core.model.source.MethodInfo;
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
 * Every element Mansart cannot honour is an error naming the mapping file and the line, never silently ignored.
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
    private static final List<String> CALLBACKS = List.of("pre-persist", "post-persist", "pre-remove", "post-remove", "pre-update",
        "post-update", "post-load");
    private static final Set<String> RELATIONSHIPS = Set.of(JPA + "ManyToOne", JPA + "OneToOne", JPA + "OneToMany",
        JPA + "ManyToMany");
    private static final Set<String> TABLES = Set.of(JPA + "Table", JPA + "SecondaryTable", JPA + "JoinTable",
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
        "primary-key-join-column", "pkJoinColumns", "constraint-mode", "value");
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
    private Context unitDefaults;

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
                    "the class " + declaration.getKey() + " cannot be found");
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
        return new Context(file, root.childText("package"), root.childText("schema"), root.childText("catalog"),
            root.childText("access"));
    }

    /** {@code persistence-unit-metadata} (§12.2.1): the defaults of the whole unit, whichever file writes them. */
    private void unitMetadata(XmlElement root, Context context) {
        Optional<XmlElement> metadata = root.child("persistence-unit-metadata");
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
                case "description" -> {
                }
                case "schema" -> unitSchema = unitDefault(unitSchema, element, context);
                case "catalog" -> unitCatalog = unitDefault(unitCatalog, element, context);
                case "access" -> unitAccess = unitDefault(unitAccess, element, context);
                case "cascade-persist" -> cascadePersist = true;
                case "delimited-identifiers" -> delimitedIdentifiers = true;
                case "entity-listeners" -> {
                    for (XmlElement listener : element.children("entity-listener")) {
                        String name = listener(listener, context);
                        if (!defaultListeners.contains(name)) {
                            defaultListeners.add(name);
                        }
                    }
                }
                default -> throw unsupported(context, element);
            }
        }
        unitDefaults = context;
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
                case "description", "persistence-unit-metadata", "package", "schema", "catalog", "access" -> {
                }
                case "sequence-generator" -> unitAnnotations.add(withSchema(convert(JPA + "SequenceGenerator", element, context, Set.of()),
                    schema(context), catalog(context)));
                case "table-generator" -> unitAnnotations.add(withSchema(convert(JPA + "TableGenerator", element, context, Set.of()),
                    schema(context), catalog(context)));
                case "named-query" -> unitAnnotations.add(convert(JPA + "NamedQuery", element, context, Set.of()));
                case "named-native-query" -> unitAnnotations.add(convert(JPA + "NamedNativeQuery", element, context, Set.of()));
                case "named-stored-procedure-query" -> unitAnnotations.add(convert(JPA + "NamedStoredProcedureQuery", element, context,
                    Set.of()));
                case "sql-result-set-mapping" -> unitAnnotations.add(convert(JPA + "SqlResultSetMapping", element, context, Set.of()));
                case "entity", "mapped-superclass", "embeddable", "converter" -> {
                    String name = className(element.attribute("class"), context);
                    Declaration known = declarations.get(name);
                    if (known != null) {
                        throw error(context, element, "the class " + name + " is already mapped at line " + known.element().line()
                            + " of " + known.context().file().name());
                    }
                    declarations.put(name, new Declaration(element, context));
                    mapped.add(name);
                    element.child("entity-listeners").ifPresent(listeners ->
                        listeners.children("entity-listener").forEach(listener -> listener(listener, context)));
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
        String name = className(listener.attribute("class"), context);
        for (XmlElement callback : listener.children()) {
            if (callback.name().equals("description")) {
                continue;
            }
            String kind = kind(callback.name());
            Named named = new Named(callback.attribute("method-name"), context, callback.line());
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
                        new Named(callback.attribute("method-name"), declaration.getValue().context(), callback.line()), 0);
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
        for (Optional<ClassInfo> current = raw.read(className); current.isPresent(); ) {
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
            throw OrmXmlReader.error(named.context().file(), named.line(), "the class " + className + " cannot be found", null);
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
            if (declaration.element().name().equals("converter")) {
                String autoApply = declaration.element().attribute("auto-apply");
                remove(type, CONVERTER);
                type.add(raw.annotation(CONVERTER, autoApply == null ? Map.of() : Map.of("autoApply", parseBoolean(autoApply))));
            } else {
                declared(info, declaration, complete, type, fields, methods);
            }
        }
        for (String kind : replaced) {
            methods.forEach(annotations -> remove(annotations, JPA + kind));
        }
        for (Placement placement : placed) {
            for (int m = 0; m < info.methods().size(); m++) {
                MethodInfo method = info.methods().get(m);
                if (method.name().equals(placement.method()) && !method.isStatic()
                        && method.type().parameterCount() == placement.parameters()) {
                    replace(methods.get(m), JPA + placement.kind(), raw.annotation(JPA + placement.kind(), Map.of()));
                    break;
                }
            }
        }
        if (schema != null || catalog != null) {
            if (type.stream().anyMatch(a -> a.typeName().equals(ENTITY)) && type.stream().noneMatch(a -> a.typeName().equals(JPA + "Table"))) {
                type.add(raw.annotation(JPA + "Table", Map.of()));
            }
            schemas(type, schema, catalog);
            fields.forEach(annotations -> schemas(annotations, schema, catalog));
            methods.forEach(annotations -> schemas(annotations, schema, catalog));
        }
        if (cascadePersist) {
            fields.forEach(this::cascadePersist);
            methods.forEach(this::cascadePersist);
        }
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
        return new ClassInfo(name, info.superclassName(), info.interfaceNames(), info.flags(), info.genericSignature(), info.isRecord(),
            type, newFields, newMethods);
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

    /** An {@code entity}, {@code mapped-superclass} or {@code embeddable} element (§12.2.3 to §12.2.5). */
    private void declared(ClassInfo info, Declaration declaration, boolean complete, List<AnnotationInfo> type,
            List<List<AnnotationInfo>> fields, List<List<AnnotationInfo>> methods) {
        XmlElement element = declaration.element();
        Context context = declaration.context();
        String kind = switch (element.name()) {
            case "entity" -> ENTITY;
            case "mapped-superclass" -> MAPPED_SUPERCLASS;
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

        String access = access(info, element, context, type, fields, methods);
        if (element.attribute("cacheable") != null) {
            replace(type, JPA + "Cacheable", raw.annotation(JPA + "Cacheable", Map.of("value", parseBoolean(element.attribute("cacheable")))));
        }
        Map<String, List<AnnotationInfo>> merged = new LinkedHashMap<>();
        List<AnnotationInfo> secondaryTables = new ArrayList<>();
        List<AnnotationInfo> primaryKeyJoinColumns = new ArrayList<>();
        for (XmlElement child : element.children()) {
            switch (child.name()) {
                case "description", "attributes", "pre-persist", "post-persist", "pre-remove", "post-remove", "pre-update",
                    "post-update", "post-load" -> {
                }
                case "table" -> replace(type, JPA + "Table", convert(JPA + "Table", child, context, Set.of()));
                case "secondary-table" -> secondaryTables.add(convert(JPA + "SecondaryTable", child, context, Set.of()));
                case "primary-key-join-column" -> primaryKeyJoinColumns.add(convert(JPA + "PrimaryKeyJoinColumn", child, context, Set.of()));
                case "id-class" -> replace(type, JPA + "IdClass", convert(JPA + "IdClass", child, context, Set.of()));
                case "inheritance" -> replace(type, JPA + "Inheritance", convert(JPA + "Inheritance", child, context, Set.of()));
                case "discriminator-value" -> replace(type, JPA + "DiscriminatorValue", text(JPA + "DiscriminatorValue", child, context));
                case "discriminator-column" -> replace(type, JPA + "DiscriminatorColumn",
                    convert(JPA + "DiscriminatorColumn", child, context, Set.of()));
                case "sequence-generator", "table-generator", "named-query", "named-native-query", "named-stored-procedure-query",
                    "sql-result-set-mapping", "named-entity-graph", "attribute-override", "convert" -> {
                    String annotation = JPA + camel(child.name(), true);
                    merged.computeIfAbsent(annotation, _ -> new ArrayList<>()).add(convert(annotation, child, context, Set.of()));
                }
                case "association-override" -> {
                    merged.computeIfAbsent(JPA + "AssociationOverride", _ -> new ArrayList<>())
                        .add(convert(JPA + "AssociationOverride", child, context, Set.of()));
                }
                case "exclude-default-listeners" -> replace(type, JPA + "ExcludeDefaultListeners",
                    raw.annotation(JPA + "ExcludeDefaultListeners", Map.of()));
                case "exclude-superclass-listeners" -> replace(type, JPA + "ExcludeSuperclassListeners",
                    raw.annotation(JPA + "ExcludeSuperclassListeners", Map.of()));
                case "entity-listeners" -> {
                    List<ClassDesc> listeners = new ArrayList<>();
                    child.children("entity-listener").forEach(l -> listeners.add(ClassDesc.of(className(l.attribute("class"), context))));
                    replace(type, JPA + "EntityListeners", raw.annotation(JPA + "EntityListeners", Map.of("value", listeners)));
                }
                default -> throw unsupported(context, child);
            }
        }
        if (!secondaryTables.isEmpty()) {
            group(type, JPA + "SecondaryTable", secondaryTables);
        }
        if (!primaryKeyJoinColumns.isEmpty()) {
            group(type, JPA + "PrimaryKeyJoinColumn", primaryKeyJoinColumns);
        }
        merged.forEach((annotation, written2) -> merge(type, annotation, written2,
            annotation.equals(JPA + "Convert") ? "attributeName" : "name"));
        Optional<XmlElement> attributes = element.child("attributes");
        if (attributes.isPresent()) {
            for (XmlElement attribute : attributes.get().children()) {
                if (!attribute.name().equals("description")) {
                    attribute(info, attribute, context, access, fields, methods);
                }
            }
        }
    }

    /**
     * The access type of a class (§12.2.3.x, §2.3): its {@code access} attribute, else its {@code @Access} (unless
     * metadata-complete), else the {@code access} of its mapping file, else the default of the unit; each of those is
     * written as {@code @Access}. Otherwise the placement of the identifier decides, as for annotations, the mapping
     * file's identifier included; {@code null} when nothing decides it (an embeddable then takes its owner's).
     */
    private String access(ClassInfo info, XmlElement element, Context context, List<AnnotationInfo> type,
            List<List<AnnotationInfo>> fields, List<List<AnnotationInfo>> methods) {
        String written = element.attribute("access");
        if (written == null) {
            Optional<AnnotationInfo> annotated = type.stream().filter(a -> a.typeName().equals(ACCESS)).findFirst();
            if (annotated.isPresent()) {
                return annotated.get().enumConstant("value");
            }
            written = context.access() != null ? context.access() : unitAccess;
        }
        if (written != null) {
            replace(type, ACCESS, raw.annotation(ACCESS, Map.of("value", new EnumValue(ACCESS_TYPE, written))));
            return written;
        }
        if (fields.stream().anyMatch(a -> annotated(a, ID) || annotated(a, EMBEDDED_ID))) {
            return "FIELD";
        }
        if (methods.stream().anyMatch(a -> annotated(a, ID) || annotated(a, EMBEDDED_ID))) {
            return "PROPERTY";
        }
        Optional<XmlElement> id = element.child("attributes").flatMap(a -> a.children().stream()
            .filter(c -> c.name().equals("id") || c.name().equals("embedded-id")).findFirst());
        if (id.isPresent()) {
            return info.field(id.get().attribute("name")).isPresent() ? "FIELD" : "PROPERTY";
        }
        String superclass = info.superclassName();
        while (superclass != null && !superclass.startsWith("java.")) {
            Optional<ClassInfo> parent = read(superclass);
            if (parent.isEmpty()) {
                break;
            }
            Optional<AnnotationInfo> explicit = parent.get().annotation(ACCESS);
            if (explicit.isPresent()) {
                return explicit.get().enumConstant("value");
            }
            if (parent.get().fields().stream().anyMatch(f -> f.isAnnotated(ID) || f.isAnnotated(EMBEDDED_ID))) {
                return "FIELD";
            }
            if (parent.get().methods().stream().anyMatch(m -> m.isAnnotated(ID) || m.isAnnotated(EMBEDDED_ID))) {
                return "PROPERTY";
            }
            superclass = parent.get().superclassName();
        }
        return null;
    }

    private static boolean annotated(List<AnnotationInfo> annotations, String type) {
        return annotations.stream().anyMatch(a -> a.typeName().equals(type));
    }

    // ---- attributes (§12.2.3.x) ------------------------------------------------------------------------------

    /**
     * An element of {@code <attributes>}: the annotations it stands for replace those of the field and the property of
     * that name, on the member its access type reads (both when the class leaves it to its owner).
     */
    private void attribute(ClassInfo info, XmlElement attribute, Context context, String classAccess,
            List<List<AnnotationInfo>> fields, List<List<AnnotationInfo>> methods) {
        String name = attribute.attribute("name");
        int field = -1;
        for (int f = 0; f < info.fields().size(); f++) {
            if (info.fields().get(f).name().equals(name) && !info.fields().get(f).isStatic()) {
                field = f;
            }
        }
        int getter = -1;
        for (int m = 0; m < info.methods().size(); m++) {
            if (name.equals(AccessPlanner.propertyName(info.methods().get(m)))) {
                getter = m;
            }
        }
        if (field < 0 && getter < 0) {
            throw error(context, attribute, "the class " + info.name() + " has no field or property " + name);
        }
        if (field >= 0) {
            removeJpa(fields.get(field));
        }
        if (getter >= 0) {
            removeJpa(methods.get(getter));
        }
        if (attribute.name().equals("transient")) {
            AnnotationInfo transientAnnotation = raw.annotation(TRANSIENT, Map.of());
            if (field >= 0) {
                fields.get(field).add(transientAnnotation);
            }
            if (getter >= 0) {
                methods.get(getter).add(transientAnnotation);
            }
            return;
        }
        String memberAccess = attribute.attribute("access");
        String access = memberAccess != null ? memberAccess : classAccess;
        if ("FIELD".equals(access) && field < 0 || "PROPERTY".equals(access) && getter < 0) {
            throw error(context, attribute, "the class " + info.name() + " has no " + ("FIELD".equals(access) ? "field " : "property ")
                + name + " for its " + access + " access");
        }
        List<AnnotationInfo> annotations = mapping(attribute, context);
        if (memberAccess != null) {
            annotations.add(raw.annotation(ACCESS, Map.of("value", new EnumValue(ACCESS_TYPE, memberAccess))));
            // §2.3.2: the member the class access would read is not persistent besides the one named
            if (memberAccess.equals("FIELD") && getter >= 0) {
                methods.get(getter).add(raw.annotation(TRANSIENT, Map.of()));
            } else if (memberAccess.equals("PROPERTY") && field >= 0) {
                fields.get(field).add(raw.annotation(TRANSIENT, Map.of()));
            }
        }
        if (!"PROPERTY".equals(access) && field >= 0) {
            fields.get(field).addAll(annotations);
        }
        if (!"FIELD".equals(access) && getter >= 0) {
            methods.get(getter).addAll(annotations);
        }
    }

    /** The annotations an attribute mapping element stands for. */
    private List<AnnotationInfo> mapping(XmlElement attribute, Context context) {
        String main = MAPPINGS.get(attribute.name());
        if (main == null) {
            throw unsupported(context, attribute);
        }
        List<AnnotationInfo> annotations = new ArrayList<>();
        AnnotationInfo annotation = convertAttributes(main, attribute, context, Set.of("name", "access", "maps-id", "id"));
        if (RELATIONSHIPS.contains(main)) {
            List<EnumValue> cascade = new ArrayList<>();
            attribute.child("cascade").ifPresent(c -> c.children().forEach(type ->
                cascade.add(new EnumValue(CASCADE_TYPE, type.name().substring("cascade-".length()).toUpperCase(java.util.Locale.ROOT)))));
            if (!cascade.isEmpty()) {
                annotation = annotation.with("cascade", cascade);
            }
        }
        annotations.add(annotation);
        if (attribute.attribute("maps-id") != null) {
            annotations.add(raw.annotation(JPA + "MapsId", Map.of("value", attribute.attribute("maps-id"))));
        }
        if ("true".equals(attribute.attribute("id")) || "1".equals(attribute.attribute("id"))) {
            annotations.add(raw.annotation(ID, Map.of()));
        }
        Map<String, List<AnnotationInfo>> repeated = new LinkedHashMap<>();
        for (XmlElement child : attribute.children()) {
            switch (child.name()) {
                case "cascade" -> {
                }
                case "column", "join-table", "collection-table", "order-column", "map-key", "map-key-class", "map-key-column",
                    "generated-value", "sequence-generator", "table-generator" ->
                    annotations.add(convert(JPA + camel(child.name(), true), child, context, Set.of()));
                case "order-by" -> annotations.add(raw.annotation(JPA + "OrderBy", Map.of("value", child.text().strip())));
                case "temporal", "enumerated", "map-key-temporal", "map-key-enumerated" ->
                    annotations.add(text(JPA + camel(child.name(), true), child, context));
                case "lob" -> annotations.add(raw.annotation(JPA + "Lob", Map.of()));
                case "join-column", "primary-key-join-column", "map-key-join-column", "attribute-override", "association-override", "convert" -> {
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
                    AnnotationInfo convert = convert(JPA + "Convert", child, context, Set.of());
                    String path = convert.isWritten("attributeName") ? "key." + convert.string("attributeName") : "key";
                    repeated.computeIfAbsent(JPA + "Convert", _ -> new ArrayList<>()).add(convert.with("attributeName", path));
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
            if (child.name().equals("description")) {
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
        return raw.annotation(type, Map.of("value", value(members.get("value"), element.text().strip(), element, context)));
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
            throw error(context, element, "the annotation type " + type + " cannot be found");
        }
        return members;
    }

    /** The member an attribute or subelement names: its camel case, plural for an array, or a known exception. */
    private static String member(String type, Map<String, ClassDesc> members, String xmlName, XmlElement element, Context context) {
        List<String> candidates = new ArrayList<>();
        if (xmlName.equals("class")) {
            candidates.add("type");
            candidates.add("value");
        } else if (xmlName.equals("name") && type.equals(JPA + "NamedAttributeNode")) {
            candidates.add("value");
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
        throw error(context, element, "<" + element.name() + "> has no " + xmlName + " Mansart can map to @"
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
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
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
            List<AnnotationInfo> values = new ArrayList<>(annotation.annotations("value"));
            values.replaceAll(a -> withSchema(a, schema, catalog));
            return annotation.with("value", values);
        }
        return annotation;
    }

    /** §12.2.1.x {@code cascade-persist}: every relationship of the unit cascades the persist operation. */
    private void cascadePersist(List<AnnotationInfo> annotations) {
        annotations.replaceAll(a -> {
            if (!RELATIONSHIPS.contains(a.typeName()) || a.enumConstants("cascade").contains("PERSIST")
                    || a.enumConstants("cascade").contains("ALL")) {
                return a;
            }
            List<Object> cascade = new ArrayList<>(a.isWritten("cascade") ? listOf(a.value("cascade")) : List.of());
            cascade.add(new EnumValue(CASCADE_TYPE, "PERSIST"));
            return a.with("cascade", cascade);
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
        annotations.add(values.size() == 1 ? values.getFirst() : raw.annotation(container(type), Map.of("value", values)));
    }

    /** Adds {@code values} to the {@code type} annotations, each replacing the annotated one with the same {@code key}. */
    private void merge(List<AnnotationInfo> annotations, String type, List<AnnotationInfo> values, String key) {
        List<AnnotationInfo> all = new ArrayList<>();
        for (AnnotationInfo annotation : annotations) {
            if (annotation.typeName().equals(type)) {
                all.add(annotation);
            } else if (annotation.typeName().equals(container(type))) {
                all.addAll(annotation.annotations("value"));
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
        return error(context, element, "<" + element.name() + "> is not supported by Mansart");
    }
}
