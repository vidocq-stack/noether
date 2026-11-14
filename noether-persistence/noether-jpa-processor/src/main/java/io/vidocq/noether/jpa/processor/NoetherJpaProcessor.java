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
package io.vidocq.noether.jpa.processor;

import io.vidocq.noether.jpa.core.model.AccessKind;
import io.vidocq.noether.jpa.core.model.build.AccessPlanner;
import io.vidocq.noether.jpa.core.model.build.AccessPlanner.Member;
import io.vidocq.noether.jpa.core.model.source.ClassInfo;
import io.vidocq.noether.jpa.core.model.source.MethodInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.lang.classfile.ClassFile;
import java.lang.constant.ConstantDescs;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/**
 * Generates, for the entities of the compilation and the embeddables they embed, the accesses Noether uses instead of
 * the hidden classes it would otherwise define at bootstrap: {@code X$$NoetherAccess} in the package of each class, and
 * one {@code _NoetherJpaAccess} per package that hands them over ({@code ManagedAccessProvider}). On the class path the
 * processor registers the providers in {@code META-INF/services}; on the module path it checks that the module reads
 * the provider and declares them, and prints the lines to add otherwise.
 *
 * <p>The members are planned by the provider's own {@link AccessPlanner}, so an access lists its attributes as the
 * model built at bootstrap does. A class the processor cannot serve (no identifier, a member of another module, a type
 * the package cannot name) is left to the bootstrap. The processor claims no annotation: other processors, such as the
 * Jakarta Data one, see the entities too.
 *
 * <p>Incremental builds: a provider keeps, in its {@code ACCESSES} constant, the accesses it hands over; a build that
 * recompiles part of a package keeps those of the classes it did not recompile, as long as they are still managed
 * classes. The {@code META-INF/services} file keeps the providers of earlier compilations that still exist.
 */
public final class NoetherJpaProcessor extends AbstractProcessor {

    static final String SPI = "io.vidocq.noether.jpa.core.spi.ManagedAccessProvider";
    static final String PROVIDER = "_NoetherJpaAccess";
    private static final String CORE = "io.vidocq.noether.jpa.core";
    private static final String ENTITY = "jakarta.persistence.Entity";
    private static final String EMBEDDABLE = "jakarta.persistence.Embeddable";
    private static final String CONVERTER = "jakarta.persistence.Converter";
    private static final String ENUMERATED_VALUE = "jakarta.persistence.EnumeratedValue";
    private static final String ID_CLASS = "jakarta.persistence.IdClass";
    private static final String ID = "jakarta.persistence.Id";
    private static final String ACCESS = "$$NoetherAccess";
    private static final String ID_ACCESS = "$$NoetherIdAccess";
    private static final String RESULT_CONSTRUCTORS = "$$NoetherConstructors";
    private static final String NAMED_QUERY = "jakarta.persistence.NamedQuery";
    private static final String NAMED_QUERIES = "jakarta.persistence.NamedQueries";
    private static final String RESULT_SET_MAPPING = "jakarta.persistence.SqlResultSetMapping";
    private static final String RESULT_SET_MAPPINGS = "jakarta.persistence.SqlResultSetMappings";
    private static final String SERVICES = "META-INF/services/" + SPI;
    private static final List<String> SUFFIXES =
        List.of(ACCESS, "$$NoetherFieldAccess", "$$NoetherPropertyAccess", ID_ACCESS, RESULT_CONSTRUCTORS);
    /** The suffixes of what is generated for a class without a mapping annotation: kept while the class exists. */
    private static final Set<String> UNANNOTATED = Set.of(ID_ACCESS, RESULT_CONSTRUCTORS);
    /**
     * A constructor expression (§4.8.2) and the fully qualified name of its class. A string literal of the query that
     * looks like one only adds the constructors of a class of that name, if there is one.
     */
    private static final Pattern SELECT_NEW = Pattern.compile("\\bnew\\s+([\\w$]+(?:\\.[\\w$]+)*)\\s*\\(",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** The source of a provider, filled with formatted(): package line, class name, SPI, kept accesses, instances. */
    private static final String PROVIDER_SOURCE = """
        // Generated by noether-jpa-processor: do not edit.
        %1$spublic final class %2$s implements %3$s {

            /** The accesses handed over, kept for the incremental builds that recompile part of the package. */
            public static final java.lang.String ACCESSES = "%4$s";
            public static final java.lang.String METAMODELS = "%6$s";

            public %2$s() {
                // found by ServiceLoader
            }

            @java.lang.Override
            public java.util.List<io.vidocq.noether.jpa.core.spi.ManagedAccess> accesses() {
                return java.util.List.of(%5$s);
            }
        }
        """;

    /** The method of a provider that hands the result constructors of its package over. */
    private static final String RESULT_CONSTRUCTORS_METHOD = """

            @java.lang.Override
            public java.util.List<io.vidocq.noether.jpa.core.spi.ResultConstructorAccess> resultConstructors() {
                return java.util.List.of(%s);
            }
        """;

    private Elements elements;
    private javax.lang.model.util.Types types;
    private Filer filer;
    private Messager messager;
    private ElementInfos infos;
    private AccessPlanner planner;
    private IdClassMembers idClasses;
    private AccessWriter writer;
    private MetamodelWriter metamodelWriter;

    /** The accesses generated and not yet handed over, by package, with the elements they were generated from. */
    private final Map<String, List<String>> pending = new LinkedHashMap<>();
    private final Map<String, Set<Element>> origins = new LinkedHashMap<>();
    /** The packages whose provider is written. */
    private final Set<String> providers = new LinkedHashSet<>();
    private final Set<String> generated = new LinkedHashSet<>();
    /** Entities whose types were not all resolved yet, retried in the next round. */
    private final Set<String> deferred = new LinkedHashSet<>();
    private final Set<String> reported = new LinkedHashSet<>();
    private ModuleElement module;

    public NoetherJpaProcessor() {
        // found by ServiceLoader
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        elements = processingEnv.getElementUtils();
        types = processingEnv.getTypeUtils();
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
        infos = new ElementInfos(elements, processingEnv.getTypeUtils());
        planner = new AccessPlanner(infos);
        idClasses = new IdClassMembers(infos);
        writer = new AccessWriter(elements, infos);
        metamodelWriter = new MetamodelWriter(processingEnv);
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        infos.clear();
        if (round.processingOver()) {
            finish();
            return false;
        }
        metamodelWriter.emit();
        int before = generated.size();
        List<TypeElement> entities = new ArrayList<>();
        for (String name : List.copyOf(deferred)) {
            TypeElement type = elements.getTypeElement(name);
            if (type != null) {
                entities.add(type);
            }
        }
        deferred.clear();
        TypeElement entity = elements.getTypeElement(ENTITY);
        if (entity != null) {
            entities.addAll(ElementFilter.typesIn(round.getElementsAnnotatedWith(entity)));
        }
        for (TypeElement type : entities) {
            entity(type);
        }
        unmanagedCode(round);
        if (generated.size() == before && deferred.isEmpty() && !pending.isEmpty()) {
            // a round that generated nothing: hand over what the previous rounds generated
            pending.forEach(this::provider);
            pending.clear();
        }
        return false;
    }

    /**
     * The code a unit runs of classes it does not manage, compiled in this round: converters, enums mapped by their
     * {@code @EnumeratedValue} field, the classes the queries build.
     */
    private void unmanagedCode(RoundEnvironment round) {
        TypeElement converter = elements.getTypeElement(CONVERTER);
        if (converter != null) {
            ElementFilter.typesIn(round.getElementsAnnotatedWith(converter)).forEach(this::converter);
        }
        TypeElement enumeratedValue = elements.getTypeElement(ENUMERATED_VALUE);
        if (enumeratedValue != null) {
            ElementFilter.fieldsIn(round.getElementsAnnotatedWith(enumeratedValue)).forEach(this::enumeratedValue);
        }
        for (Element origin : annotatedWithAny(round, NAMED_QUERY, NAMED_QUERIES, RESULT_SET_MAPPING, RESULT_SET_MAPPINGS)) {
            resultClasses(origin).forEach(result -> resultConstructors(result, origin));
        }
    }

    private void entity(TypeElement type) {
        if (!readsProvider(type)) {
            return;
        }
        ClassInfo info = infos.read(type);
        List<Member> members;
        try {
            members = planner.entityMembers(info);
        } catch (RuntimeException e) {
            messager.printMessage(Diagnostic.Kind.WARNING, "Noether cannot plan the access of " + info.name() + " ("
                + e.getMessage() + "); it is generated at bootstrap instead", type);
            return;
        }
        if (infos.unresolved()) {
            deferred.add(type.getQualifiedName().toString());
            return;
        }
        List<ClassInfo> hierarchy = planner.hierarchy(info);
        Set<Element> from = metamodels(hierarchy, members);
        if (access(type, ACCESS, members, false, from, planner.callbacks(info))) {
            for (Member member : members) {
                embedded(type, member, from);
            }
            idClass(type, hierarchy, members, from);
        }
    }

    /**
     * Plans the canonical metamodel of each class of the hierarchy compiled here, with the members it declares or
     * inherits; returns the classes of the hierarchy, the origins of the entity's access.
     */
    private Set<Element> metamodels(List<ClassInfo> hierarchy, List<Member> members) {
        Set<Element> from = new LinkedHashSet<>();
        for (int i = 0; i < hierarchy.size(); i++) {
            TypeElement element = infos.element(hierarchy.get(i).name());
            if (element == null) {
                continue;
            }
            from.add(element);
            if (fromSource(element)) {
                Set<String> owners = hierarchy.subList(0, i + 1).stream().map(ClassInfo::name)
                    .collect(java.util.stream.Collectors.toSet());
                metamodelWriter.plan(element, members.stream().filter(m -> owners.contains(m.owner().name())).toList(),
                    i == 0 ? null : infos.element(hierarchy.get(i - 1).name()).getQualifiedName().toString());
            }
        }
        return from;
    }

    /**
     * The access of the id class of an entity (§2.4.1), which the bootstrap builds to read and to make its
     * identifiers: an id class of a package that is neither exported nor opened then needs no {@code opens}
     * (BUG-20261114-03). The nearest {@code @IdClass} of the hierarchy applies, as at bootstrap. The access of a record
     * lists the identifier attributes in the order of its components, which its canonical constructor takes: the
     * bootstrap reorders them to the entity's (BUG-20261114-06).
     */
    private void idClass(TypeElement entity, List<ClassInfo> hierarchy, List<Member> members, Set<Element> entityOrigins) {
        TypeElement type = hierarchy.stream().map(c -> c.annotation(ID_CLASS)).flatMap(Optional::stream)
            .reduce((first, second) -> second)
            .map(idClass -> infos.element(AccessWriter.binaryName(idClass.type("value"))))
            .orElse(null);
        if (type == null || !elements.getModuleOf(type).equals(elements.getModuleOf(entity)) || !fromSource(type)) {
            return; // compiled elsewhere: its package is not ours to add classes to
        }
        boolean isRecord = AccessWriter.isRecord(type);
        List<Member> parts = members.stream().filter(m -> m.element().isAnnotated(ID)).toList();
        Set<Element> from = new LinkedHashSet<>(entityOrigins);
        from.add(type);
        idClasses.of(infos.read(type), parts)
            .flatMap(keys -> isRecord ? inComponentOrder(keys, type) : Optional.of(keys))
            .ifPresent(keys -> access(type, ID_ACCESS, keys, isRecord, from, List.of()));
    }

    /** The keys of a record id class in the order of its components; empty when the components name other keys. */
    private static Optional<List<Member>> inComponentOrder(List<Member> keys, TypeElement idClass) {
        Map<String, Member> byName = new LinkedHashMap<>();
        keys.forEach(key -> byName.put(key.name(), key));
        List<String> components = idClass.getRecordComponents().stream()
            .map(component -> component.getSimpleName().toString()).toList();
        if (components.size() != keys.size() || !byName.keySet().equals(new HashSet<>(components))) {
            return Optional.empty();
        }
        return Optional.of(components.stream().map(byName::get).toList());
    }

    /**
     * The access of a converter (§3.9), which instantiates it: a converter of a package that is neither exported nor
     * opened then needs no {@code opens} (BUG-20261114-01).
     */
    private void converter(TypeElement type) {
        if (type.getKind() == ElementKind.CLASS && !type.getModifiers().contains(Modifier.ABSTRACT) && handsOver(type)) {
            access(type, ACCESS, List.of(), false, Set.of(type), List.of());
        }
    }

    /**
     * The access of an enum mapped by its {@code @EnumeratedValue} field (§11.1.18), which reads that field: such an
     * enum of a package that is neither exported nor opened then needs no {@code opens} (BUG-20261114-02).
     */
    private void enumeratedValue(VariableElement field) {
        if (!(field.getEnclosingElement() instanceof TypeElement type) || type.getKind() != ElementKind.ENUM
                || !handsOver(type)) {
            return;
        }
        ClassInfo info = infos.read(type);
        String name = field.getSimpleName().toString();
        info.field(name).ifPresent(declared -> access(type, ACCESS,
            List.of(new Member(name, AccessKind.FIELD, info, declared, declared.type(), declared.genericSignature())),
            false, Set.of(type), List.of()));
    }

    /**
     * Whether the processor hands over the code of a class that is not managed: compiled here, by a module that reads
     * the provider. Unlike an entity, such a class may sit in a library that never reads it: left to the bootstrap.
     */
    private boolean handsOver(TypeElement type) {
        ModuleElement owner = elements.getModuleOf(type);
        return fromSource(type) && (owner.isUnnamed() || reads(owner, CORE));
    }

    /**
     * A named module must read the provider: the generated accesses implement its SPI. Reported once, as an error,
     * rather than as the compilation errors of every generated class.
     */
    private boolean readsProvider(TypeElement type) {
        ModuleElement owner = elements.getModuleOf(type);
        if (owner.isUnnamed()) {
            return true;
        }
        boolean reads = reads(owner, CORE);
        if (!reads && reported.add(owner.getQualifiedName().toString())) {
            messager.printMessage(Diagnostic.Kind.ERROR, "The accesses Noether generates for the entities of "
                + owner.getQualifiedName() + " implement its SPI: add to its module-info.java\n    requires " + CORE + ";");
        }
        return reads;
    }

    /**
     * Whether {@code module} reads {@code target}: it requires it, or requires a module that requires it transitively,
     * through any chain of {@code requires transitive} (JLS §7.7.1) — a library that hands the provider on is enough
     * (BUG-20261112-01).
     */
    private static boolean reads(ModuleElement module, String target) {
        Deque<ModuleElement> pending = new ArrayDeque<>();
        Set<ModuleElement> seen = new HashSet<>();
        ElementFilter.requiresIn(module.getDirectives()).forEach(r -> pending.add(r.getDependency()));
        while (!pending.isEmpty()) {
            ModuleElement next = pending.poll();
            if (!seen.add(next)) {
                continue;
            }
            if (next.getQualifiedName().contentEquals(target)) {
                return true;
            }
            ElementFilter.requiresIn(next.getDirectives()).stream()
                .filter(ModuleElement.RequiresDirective::isTransitive)
                .forEach(r -> pending.add(r.getDependency()));
        }
        return false;
    }

    /** The access of the embeddable a member embeds, then of the embeddables that one embeds. */
    private void embedded(TypeElement owner, Member member, Set<Element> ownerOrigins) {
        planner.embedded(member).ifPresent(info -> {
            TypeElement type = infos.element(info.name());
            if (type == null || !elements.getModuleOf(type).equals(elements.getModuleOf(owner)) || !fromSource(type)) {
                return; // compiled elsewhere: its package is not ours to add classes to
            }
            AccessKind access = AccessPlanner.embeddableAccess(info, member.access());
            boolean record = AccessWriter.isRecord(type);
            String suffix = record ? "$$NoetherAccess" : access == AccessKind.FIELD ? "$$NoetherFieldAccess" : "$$NoetherPropertyAccess";
            List<Member> members = planner.embeddableMembers(info, member.access());
            metamodelWriter.plan(type, members, null);
            // the access type of an embeddable may come from its owner: the owner is an origin of its access too
            Set<Element> from = new LinkedHashSet<>(ownerOrigins);
            from.add(type);
            if (access(type, suffix, members, record, from, List.of())) {
                for (Member nested : members) {
                    embedded(type, nested, from);
                }
            }
        });
    }

    private boolean fromSource(TypeElement type) {
        try {
            JavaFileObject file = elements.getFileObjectOf(type);
            return file != null && file.getKind() == JavaFileObject.Kind.SOURCE;
        } catch (UnsupportedOperationException e) {
            return false; // a compiler that cannot tell: leave the class to the bootstrap
        }
    }

    /** Writes the access of {@code type} once; {@code false} if it cannot be generated. */
    private boolean access(TypeElement type, String suffix, List<Member> members, boolean record, Set<Element> from,
            List<AccessPlanner.Callback> callbacks) {
        String pkg = AccessWriter.packageOf(type);
        String className = generatedName(type, pkg, suffix);
        String qualified = qualified(pkg, className);
        if (generated.contains(qualified)) {
            return true;
        }
        if (providerWritten(type, pkg)) {
            return false;
        }
        String source;
        try {
            source = writer.write(type, className, members, record, callbacks);
            var info = infos.read(type);
            if (!record && type.getKind() == ElementKind.CLASS && !members.isEmpty() && !suffix.equals(ID_ACCESS)
                    && !type.getModifiers().contains(Modifier.SEALED)
                    && io.vidocq.noether.jpa.core.model.build.LazySubclassGenerator.supports(info, members)) {
                String lazy = io.vidocq.noether.jpa.core.model.build.LazySubclassGenerator.name(info.name());
                try (var output = filer.createClassFile(lazy, type).openOutputStream()) {
                    output.write(io.vidocq.noether.jpa.core.model.build.LazySubclassGenerator.bytes(info, members));
                }
                int end = source.lastIndexOf('}');
                source = source.substring(0, end)
                    + "    @java.lang.Override public java.lang.Object instantiateLazy() { return new " + lazy
                    + "(); }\n" + source.substring(end);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write lazy entity subclass", e);
        } catch (AccessWriter.Unsupported e) {
            note(type, e.getMessage());
            return false;
        }
        return handOver(type, pkg, className, source, from);
    }

    /** The simple name of the class generated for {@code type} in its package: its binary name and {@code suffix}. */
    private String generatedName(TypeElement type, String pkg, String suffix) {
        return elements.getBinaryName(type).toString().substring(pkg.isEmpty() ? 0 : pkg.length() + 1) + suffix;
    }

    /** Whether the provider of {@code pkg} is already written: what {@code type} needs is left to the bootstrap. */
    private boolean providerWritten(TypeElement type, String pkg) {
        if (providers.contains(pkg)) {
            note(type, "its package already has its provider, written in an earlier round");
            return true;
        }
        return false;
    }

    /** Writes a generated class and hands it over through the provider of its package. */
    private boolean handOver(TypeElement type, String pkg, String className, String source, Set<Element> from) {
        String qualified = qualified(pkg, className);
        if (!write(qualified, source, from)) {
            return false;
        }
        generated.add(qualified);
        pending.computeIfAbsent(pkg, p -> new ArrayList<>()).add(className);
        origins.computeIfAbsent(pkg, p -> new LinkedHashSet<>()).addAll(from);
        if (module == null) {
            module = elements.getModuleOf(type);
        }
        return true;
    }

    /** The elements of the round annotated with any of {@code annotations}, each once. */
    private Set<Element> annotatedWithAny(RoundEnvironment round, String... annotations) {
        Set<Element> found = new LinkedHashSet<>();
        for (String annotation : annotations) {
            TypeElement type = elements.getTypeElement(annotation);
            if (type != null) {
                found.addAll(round.getElementsAnnotatedWith(type));
            }
        }
        return found;
    }

    /**
     * The classes the queries declared on {@code origin} build without them being entities: those its named queries
     * name in a constructor expression (§4.8.2), and the targets of the constructor results of its result-set
     * mappings (§3.11.15).
     */
    private Set<TypeElement> resultClasses(Element origin) {
        Set<TypeElement> found = new LinkedHashSet<>();
        for (AnnotationMirror annotation : origin.getAnnotationMirrors()) {
            switch (((TypeElement) annotation.getAnnotationType().asElement()).getQualifiedName().toString()) {
                case NAMED_QUERY -> constructed(annotation, found);
                case NAMED_QUERIES -> nested(annotation, "value").forEach(query -> constructed(query, found));
                case RESULT_SET_MAPPING -> mapped(annotation, found);
                case RESULT_SET_MAPPINGS -> nested(annotation, "value").forEach(mapping -> mapped(mapping, found));
                default -> {
                    // another annotation: no query
                }
            }
        }
        return found;
    }

    /** The classes the constructor expressions of a {@code @NamedQuery} name. */
    private void constructed(AnnotationMirror namedQuery, Set<TypeElement> found) {
        Matcher expression = SELECT_NEW.matcher(String.valueOf(value(namedQuery, "query").getValue()));
        while (expression.find()) {
            TypeElement type = elements.getTypeElement(expression.group(1).replace('$', '.'));
            if (type != null) {
                found.add(type);
            }
        }
    }

    /** The target classes of the constructor results of a {@code @SqlResultSetMapping}. */
    private void mapped(AnnotationMirror mapping, Set<TypeElement> found) {
        for (AnnotationMirror result : nested(mapping, "classes")) {
            if (value(result, "targetClass").getValue() instanceof DeclaredType target
                    && target.asElement() instanceof TypeElement type) {
                found.add(type);
            }
        }
    }

    private AnnotationValue value(AnnotationMirror annotation, String member) {
        return elements.getElementValuesWithDefaults(annotation).entrySet().stream()
            .filter(entry -> entry.getKey().getSimpleName().contentEquals(member))
            .map(Map.Entry::getValue).findFirst().orElseThrow();
    }

    /** The annotations an annotation-array member holds. */
    private List<AnnotationMirror> nested(AnnotationMirror annotation, String member) {
        return value(annotation, member).getValue() instanceof List<?> values
            ? values.stream().filter(AnnotationValue.class::isInstance).map(v -> ((AnnotationValue) v).getValue())
                .filter(AnnotationMirror.class::isInstance).map(AnnotationMirror.class::cast).toList()
            : List.of();
    }

    /**
     * The public constructors of a class a query builds, called from its own package: a class of a package that is
     * neither exported nor opened then needs no {@code exports} (BUG-20261114-04). Left to the bootstrap: an
     * abstract class, an inner class, a class compiled by a module that does not read the provider, and a class
     * whose public constructor declares a checked exception.
     */
    private void resultConstructors(TypeElement type, Element origin) {
        boolean inner = type.getNestingKind() == NestingKind.MEMBER && type.getKind() == ElementKind.CLASS
            && !type.getModifiers().contains(Modifier.STATIC);
        if (type.getKind() != ElementKind.CLASS && type.getKind() != ElementKind.RECORD || inner
                || type.getModifiers().contains(Modifier.ABSTRACT) || !handsOver(type)) {
            return;
        }
        String pkg = AccessWriter.packageOf(type);
        String className = generatedName(type, pkg, RESULT_CONSTRUCTORS);
        if (generated.contains(qualified(pkg, className)) || providerWritten(type, pkg)) {
            return;
        }
        if (ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
                .filter(constructor -> constructor.getModifiers().contains(Modifier.PUBLIC))
                .flatMap(constructor -> constructor.getThrownTypes().stream()).anyMatch(this::checked)) {
            note(type, "a public constructor declares a checked exception");
            return;
        }
        List<MethodInfo> constructors = infos.read(type).methods().stream()
            .filter(m -> m.name().equals(ConstantDescs.INIT_NAME) && (m.flags() & ClassFile.ACC_PUBLIC) != 0).toList();
        try {
            handOver(type, pkg, className, writer.writeConstructors(type, className, constructors), Set.of(type, origin));
        } catch (AccessWriter.Unsupported e) {
            note(type, e.getMessage());
        }
    }

    private boolean checked(TypeMirror thrown) {
        return !types.isSubtype(thrown, elements.getTypeElement("java.lang.RuntimeException").asType())
            && !types.isSubtype(thrown, elements.getTypeElement("java.lang.Error").asType());
    }

    /** The provider of a package: it hands over the accesses generated in it, and those an earlier build kept. */
    private void provider(String pkg, List<String> accesses) {
        Set<String> all = new LinkedHashSet<>(accesses);
        all.addAll(earlier(pkg));
        StringBuilder instances = new StringBuilder();
        StringBuilder constructors = new StringBuilder();
        for (String access : all) {
            StringBuilder list = access.endsWith(RESULT_CONSTRUCTORS) ? constructors : instances;
            list.append(list.isEmpty() ? "" : ", ").append("new ").append(access).append("()");
        }
        Set<String> helpers = new LinkedHashSet<>(metamodelWriter.helpers());
        TypeElement previous = elements.getTypeElement(qualified(pkg, PROVIDER));
        if (previous != null) {
            for (VariableElement field : ElementFilter.fieldsIn(previous.getEnclosedElements())) {
                if (field.getSimpleName().contentEquals("METAMODELS") && field.getConstantValue() instanceof String list && !list.isEmpty()) {
                    for (String helper : list.split(",")) if (elements.getTypeElement(helper) != null) helpers.add(helper);
                }
            }
        }
        String source = PROVIDER_SOURCE.formatted(pkg.isEmpty() ? "" : "package " + pkg + ";\n\n", PROVIDER, SPI,
            String.join(",", all), instances, String.join(",", helpers));
        int closing = source.lastIndexOf('}');
        source = source.substring(0, closing) + metamodelWriter.initializer(helpers)
            + (constructors.isEmpty() ? "" : RESULT_CONSTRUCTORS_METHOD.formatted(constructors)) + source.substring(closing);
        if (write(qualified(pkg, PROVIDER), source, origins.getOrDefault(pkg, Set.of()))) {
            providers.add(pkg);
        }
    }

    /**
     * The accesses the provider of an earlier build of the package handed over, whose classes are still managed
     * classes and whose accesses still exist: an incremental build recompiles only part of a package.
     */
    private List<String> earlier(String pkg) {
        TypeElement previous = elements.getTypeElement(qualified(pkg, PROVIDER));
        if (previous == null) {
            return List.of();
        }
        List<String> kept = new ArrayList<>();
        for (VariableElement field : ElementFilter.fieldsIn(previous.getEnclosedElements())) {
            if (field.getSimpleName().contentEquals("ACCESSES") && field.getConstantValue() instanceof String list && !list.isEmpty()) {
                for (String access : Arrays.asList(list.split(","))) {
                    if (stillManaged(pkg, access)) {
                        kept.add(access);
                    }
                }
            }
        }
        return kept;
    }

    private boolean stillManaged(String pkg, String access) {
        if (elements.getTypeElement(qualified(pkg, access)) == null) {
            return false;
        }
        for (String suffix : SUFFIXES) {
            if (access.endsWith(suffix)) {
                String binary = access.substring(0, access.length() - suffix.length());
                TypeElement managed = elements.getTypeElement(qualified(pkg, binary.replace('$', '.')));
                // an id class or a result class carries no annotation: kept while it exists
                return managed != null && (UNANNOTATED.contains(suffix) || annotated(managed, ENTITY, EMBEDDABLE, CONVERTER)
                    || ElementFilter.fieldsIn(managed.getEnclosedElements()).stream()
                        .anyMatch(field -> annotated(field, ENUMERATED_VALUE)));
            }
        }
        return false;
    }

    private static boolean annotated(Element element, String... annotations) {
        return element.getAnnotationMirrors().stream().anyMatch(a -> List.of(annotations)
            .contains(((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().toString()));
    }

    /** The last round: register the providers for the class path, check the module declares them. */
    private void finish() {
        if (!pending.isEmpty()) {
            // not reached in practice: every round that generates is followed by one that does not
            pending.forEach(this::provider);
            pending.clear();
        }
        for (String name : deferred) {
            messager.printMessage(Diagnostic.Kind.NOTE, "Noether left the access of " + name + " to the bootstrap: some of its "
                + "types were never resolved");
        }
        if (providers.isEmpty()) {
            return;
        }
        List<String> names = providers.stream().map(p -> qualified(p, PROVIDER)).toList();
        Set<String> services = new LinkedHashSet<>(earlierServices());
        services.addAll(names);
        try {
            FileObject file = filer.createResource(StandardLocation.CLASS_OUTPUT, "", SERVICES);
            try (Writer out = file.openWriter()) {
                for (String name : services) {
                    out.write(name + "\n");
                }
            }
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Noether cannot register its accesses in " + SERVICES + ": " + e);
        }
        if (module != null && !module.isUnnamed()) {
            Set<String> declared = new LinkedHashSet<>();
            for (ModuleElement.ProvidesDirective provides : ElementFilter.providesIn(module.getDirectives())) {
                if (provides.getService().getQualifiedName().contentEquals(SPI)) {
                    provides.getImplementations().forEach(i -> declared.add(i.getQualifiedName().toString()));
                }
            }
            if (!declared.containsAll(names)) {
                messager.printMessage(Diagnostic.Kind.WARNING, "Noether hands the accesses it generated over through "
                    + "ServiceLoader: declare them in the module-info.java of " + module.getQualifiedName() + ", or Noether "
                    + "will need the entity packages opened to " + CORE + ":\n    provides " + SPI + " with "
                    + String.join(", ", names) + ";");
            }
        }
    }

    /** The providers an earlier compilation registered in the same output, that still exist. */
    private List<String> earlierServices() {
        List<String> kept = new ArrayList<>();
        try {
            FileObject existing = filer.getResource(StandardLocation.CLASS_OUTPUT, "", SERVICES);
            try (BufferedReader in = new BufferedReader(existing.openReader(true))) {
                for (String line = in.readLine(); line != null; line = in.readLine()) {
                    String name = line.strip();
                    if (!name.isEmpty() && !name.startsWith("#") && elements.getTypeElement(name) != null) {
                        kept.add(name);
                    }
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            // no earlier registration
        }
        return kept;
    }

    private boolean write(String qualifiedName, String source, Set<Element> from) {
        try {
            var file = filer.createSourceFile(qualifiedName, from.toArray(Element[]::new));
            try (Writer out = file.openWriter()) {
                out.write(source);
            }
            return true;
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Noether cannot write " + qualifiedName + ": " + e.getMessage());
            return false;
        }
    }

    private void note(TypeElement type, String reason) {
        messager.printMessage(Diagnostic.Kind.NOTE, "Noether leaves the access of " + type.getQualifiedName()
            + " to the bootstrap: " + reason, type);
    }

    private static String qualified(String pkg, String simpleName) {
        return pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
    }
}
