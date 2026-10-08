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
package io.vidocq.mansart.jpa.processor;

import io.vidocq.mansart.jpa.core.model.AccessKind;
import io.vidocq.mansart.jpa.core.model.build.AccessPlanner;
import io.vidocq.mansart.jpa.core.model.build.AccessPlanner.Member;
import io.vidocq.mansart.jpa.core.model.source.ClassInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/**
 * Generates, for the entities of the compilation and the embeddables they embed, the accesses Mansart uses instead of
 * the hidden classes it would otherwise define at bootstrap: {@code X$$MansartAccess} in the package of each class, and
 * one {@code _MansartJpaAccess} per package that hands them over ({@code ManagedAccessProvider}). On the class path the
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
public final class MansartJpaProcessor extends AbstractProcessor {

    static final String SPI = "io.vidocq.mansart.jpa.core.spi.ManagedAccessProvider";
    static final String PROVIDER = "_MansartJpaAccess";
    private static final String CORE = "io.vidocq.mansart.jpa.core";
    private static final String ENTITY = "jakarta.persistence.Entity";
    private static final String EMBEDDABLE = "jakarta.persistence.Embeddable";
    private static final String SERVICES = "META-INF/services/" + SPI;
    private static final List<String> SUFFIXES = List.of("$$MansartAccess", "$$MansartFieldAccess", "$$MansartPropertyAccess");

    private Elements elements;
    private Filer filer;
    private Messager messager;
    private ElementInfos infos;
    private AccessPlanner planner;
    private AccessWriter writer;

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

    public MansartJpaProcessor() {
        // found by ServiceLoader
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        elements = processingEnv.getElementUtils();
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
        infos = new ElementInfos(elements, processingEnv.getTypeUtils());
        planner = new AccessPlanner(infos);
        writer = new AccessWriter(elements, infos);
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(ENTITY);
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
        if (generated.size() == before && deferred.isEmpty() && !pending.isEmpty()) {
            // a round that generated nothing: hand over what the previous rounds generated
            pending.forEach(this::provider);
            pending.clear();
        }
        return false;
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
            messager.printMessage(Diagnostic.Kind.WARNING, "Mansart cannot plan the access of " + info.name() + " ("
                + e.getMessage() + "); it is generated at bootstrap instead", type);
            return;
        }
        if (infos.unresolved()) {
            deferred.add(type.getQualifiedName().toString());
            return;
        }
        Set<Element> from = new LinkedHashSet<>();
        for (ClassInfo declaring : planner.hierarchy(info)) {
            TypeElement element = infos.element(declaring.name());
            if (element != null) {
                from.add(element);
            }
        }
        if (access(type, "$$MansartAccess", members, false, from)) {
            for (Member member : members) {
                embedded(type, member, from);
            }
        }
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
        boolean reads = ElementFilter.requiresIn(owner.getDirectives()).stream()
            .anyMatch(r -> r.getDependency().getQualifiedName().contentEquals(CORE));
        if (!reads && reported.add(owner.getQualifiedName().toString())) {
            messager.printMessage(Diagnostic.Kind.ERROR, "The accesses Mansart generates for the entities of "
                + owner.getQualifiedName() + " implement its SPI: add to its module-info.java\n    requires " + CORE + ";");
        }
        return reads;
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
            String suffix = record ? "$$MansartAccess" : access == AccessKind.FIELD ? "$$MansartFieldAccess" : "$$MansartPropertyAccess";
            List<Member> members = planner.embeddableMembers(info, member.access());
            // the access type of an embeddable may come from its owner: the owner is an origin of its access too
            Set<Element> from = new LinkedHashSet<>(ownerOrigins);
            from.add(type);
            if (access(type, suffix, members, record, from)) {
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
    private boolean access(TypeElement type, String suffix, List<Member> members, boolean record, Set<Element> from) {
        String pkg = AccessWriter.packageOf(type);
        String className = elements.getBinaryName(type).toString().substring(pkg.isEmpty() ? 0 : pkg.length() + 1) + suffix;
        String qualified = qualified(pkg, className);
        if (generated.contains(qualified)) {
            return true;
        }
        if (providers.contains(pkg)) {
            note(type, "its package already has its provider, written in an earlier round");
            return false;
        }
        String source;
        try {
            source = writer.write(type, className, members, record);
        } catch (AccessWriter.Unsupported e) {
            note(type, e.getMessage());
            return false;
        }
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

    /** The provider of a package: it hands over the accesses generated in it, and those an earlier build kept. */
    private void provider(String pkg, List<String> accesses) {
        Set<String> all = new LinkedHashSet<>(accesses);
        all.addAll(earlier(pkg));
        StringBuilder out = new StringBuilder();
        out.append("// Generated by mansart-jpa-processor: do not edit.\n");
        if (!pkg.isEmpty()) {
            out.append("package ").append(pkg).append(";\n\n");
        }
        out.append("public final class ").append(PROVIDER).append(" implements ").append(SPI).append(" {\n\n");
        out.append("    /** The accesses handed over, kept for the incremental builds that recompile part of the package. */\n");
        out.append("    public static final java.lang.String ACCESSES = \"").append(String.join(",", all)).append("\";\n\n");
        out.append("    public ").append(PROVIDER).append("() {\n        // found by ServiceLoader\n    }\n\n");
        out.append("    @java.lang.Override\n");
        out.append("    public java.util.List<io.vidocq.mansart.jpa.core.spi.ManagedAccess> accesses() {\n");
        out.append("        return java.util.List.of(");
        int i = 0;
        for (String access : all) {
            out.append(i++ == 0 ? "" : ", ").append("new ").append(access).append("()");
        }
        out.append(");\n    }\n}\n");
        if (write(qualified(pkg, PROVIDER), out.toString(), origins.getOrDefault(pkg, Set.of()))) {
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
                return managed != null && managed.getAnnotationMirrors().stream().anyMatch(a -> {
                    String name = ((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().toString();
                    return name.equals(ENTITY) || name.equals(EMBEDDABLE);
                });
            }
        }
        return false;
    }

    /** The last round: register the providers for the class path, check the module declares them. */
    private void finish() {
        if (!pending.isEmpty()) {
            // not reached in practice: every round that generates is followed by one that does not
            pending.forEach(this::provider);
            pending.clear();
        }
        for (String name : deferred) {
            messager.printMessage(Diagnostic.Kind.NOTE, "Mansart left the access of " + name + " to the bootstrap: some of its "
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
            messager.printMessage(Diagnostic.Kind.ERROR, "Mansart cannot register its accesses in " + SERVICES + ": " + e);
        }
        if (module != null && !module.isUnnamed()) {
            Set<String> declared = new LinkedHashSet<>();
            for (ModuleElement.ProvidesDirective provides : ElementFilter.providesIn(module.getDirectives())) {
                if (provides.getService().getQualifiedName().contentEquals(SPI)) {
                    provides.getImplementations().forEach(i -> declared.add(i.getQualifiedName().toString()));
                }
            }
            if (!declared.containsAll(names)) {
                messager.printMessage(Diagnostic.Kind.WARNING, "Mansart hands the accesses it generated over through "
                    + "ServiceLoader: declare them in the module-info.java of " + module.getQualifiedName() + ", or Mansart "
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
            messager.printMessage(Diagnostic.Kind.ERROR, "Mansart cannot write " + qualifiedName + ": " + e.getMessage());
            return false;
        }
    }

    private void note(TypeElement type, String reason) {
        messager.printMessage(Diagnostic.Kind.NOTE, "Mansart leaves the access of " + type.getQualifiedName()
            + " to the bootstrap: " + reason, type);
    }

    private static String qualified(String pkg, String simpleName) {
        return pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
    }
}
