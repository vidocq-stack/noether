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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
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
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Generates, for the entities of the compilation and the embeddables they embed, the accesses Mansart uses instead of
 * the hidden classes it would otherwise define at bootstrap: {@code X$$MansartAccess} in the package of each class, and
 * one {@code _MansartJpaAccess} per package that hands them over ({@code ManagedAccessProvider}). On the class path the
 * processor registers the providers in {@code META-INF/services}; on the module path it checks that the module declares
 * them, and prints the {@code provides} line to add otherwise.
 *
 * <p>The members are planned by the provider's own {@link AccessPlanner}, so an access lists its attributes as the
 * model built at bootstrap does. A class the processor cannot serve (no identifier, a member of another module, a type
 * the package cannot name) is reported in a warning and left to the bootstrap. The processor claims no annotation:
 * other processors, such as the Jakarta Data one, see the entities too.
 */
public final class MansartJpaProcessor extends AbstractProcessor {

    static final String SPI = "io.vidocq.mansart.jpa.core.spi.ManagedAccessProvider";
    static final String PROVIDER = "_MansartJpaAccess";
    private static final String ENTITY = "jakarta.persistence.Entity";

    private Elements elements;
    private Filer filer;
    private Messager messager;
    private ElementInfos infos;
    private AccessPlanner planner;
    private AccessWriter writer;

    /** The generated accesses not yet handed over, by package; and those already handed over. */
    private final Map<String, List<String>> pending = new LinkedHashMap<>();
    private final Map<String, List<String>> providers = new LinkedHashMap<>();
    private final Set<String> generated = new LinkedHashSet<>();
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
        if (round.processingOver()) {
            finish();
            return false;
        }
        TypeElement entity = elements.getTypeElement(ENTITY);
        Set<TypeElement> entities = entity == null ? Set.of()
            : ElementFilter.typesIn(round.getElementsAnnotatedWith(entity));
        for (TypeElement type : entities) {
            entity(type);
        }
        if (entities.isEmpty() && !pending.isEmpty()) {
            // a round that brought no entity: hand over what the previous rounds generated
            pending.forEach(this::provider);
            pending.clear();
        }
        return false;
    }

    private void entity(TypeElement type) {
        ClassInfo info = infos.read(type);
        List<Member> members;
        try {
            members = planner.entityMembers(info);
        } catch (RuntimeException e) {
            warn(type, "Mansart cannot plan the access of " + info.name() + " (" + e.getMessage()
                + "); it is generated at bootstrap instead");
            return;
        }
        if (access(type, "$$MansartAccess", members, false)) {
            for (Member member : members) {
                embedded(type, member);
            }
        }
    }

    /** The access of the embeddable a member embeds, then of the embeddables that one embeds. */
    private void embedded(TypeElement owner, Member member) {
        planner.embedded(member).ifPresent(info -> {
            TypeElement type = infos.element(info.name());
            if (type == null || !elements.getModuleOf(type).equals(elements.getModuleOf(owner))
                    || elements.getFileObjectOf(type) == null
                    || elements.getFileObjectOf(type).getKind() != javax.tools.JavaFileObject.Kind.SOURCE) {
                return; // compiled elsewhere: its package is not ours to add classes to
            }
            AccessKind access = AccessPlanner.embeddableAccess(info, member.access());
            boolean record = AccessWriter.isRecord(type);
            String suffix = record ? "$$MansartAccess" : access == AccessKind.FIELD ? "$$MansartFieldAccess" : "$$MansartPropertyAccess";
            List<Member> members = planner.embeddableMembers(info, member.access());
            if (access(type, suffix, members, record)) {
                for (Member nested : members) {
                    embedded(type, nested);
                }
            }
        });
    }

    /** Writes the access of {@code type} once; {@code false} if it cannot be generated. */
    private boolean access(TypeElement type, String suffix, List<Member> members, boolean record) {
        String pkg = AccessWriter.packageOf(type);
        String className = elements.getBinaryName(type).toString().substring(pkg.isEmpty() ? 0 : pkg.length() + 1) + suffix;
        String qualified = pkg.isEmpty() ? className : pkg + "." + className;
        if (!generated.add(qualified)) {
            return true;
        }
        String source;
        try {
            source = writer.write(type, className, members, record);
        } catch (AccessWriter.Unsupported e) {
            warn(type, "Mansart cannot generate the access of " + type.getQualifiedName() + " (" + e.getMessage()
                + "); it is generated at bootstrap instead");
            return false;
        }
        write(qualified, source, type);
        pending.computeIfAbsent(pkg, p -> new ArrayList<>()).add(className);
        if (module == null) {
            module = elements.getModuleOf(type);
        }
        return true;
    }

    /** The provider of a package: it hands over every access generated in it. */
    private void provider(String pkg, List<String> accesses) {
        StringBuilder out = new StringBuilder();
        out.append("// Generated by mansart-jpa-processor: do not edit.\n");
        out.append("package ").append(pkg).append(";\n\n");
        out.append("public final class ").append(PROVIDER).append(" implements ").append(SPI).append(" {\n\n");
        out.append("    public ").append(PROVIDER).append("() {\n        // found by ServiceLoader\n    }\n\n");
        out.append("    @Override\n");
        out.append("    public java.util.List<io.vidocq.mansart.jpa.core.spi.ManagedAccess> accesses() {\n");
        out.append("        return java.util.List.of(");
        for (int i = 0; i < accesses.size(); i++) {
            out.append(i == 0 ? "" : ", ").append("new ").append(accesses.get(i)).append("()");
        }
        out.append(");\n    }\n}\n");
        write(pkg + "." + PROVIDER, out.toString(), null);
        providers.put(pkg, accesses);
    }

    /** The last round: register the providers for the class path, check the module declares them. */
    private void finish() {
        if (!pending.isEmpty()) {
            // not reached in practice: every round with entities is followed by one without
            pending.forEach(this::provider);
            pending.clear();
        }
        if (providers.isEmpty()) {
            return;
        }
        List<String> names = providers.keySet().stream().map(p -> p + "." + PROVIDER).toList();
        try {
            FileObject services = filer.createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/services/" + SPI);
            try (Writer out = services.openWriter()) {
                for (String name : names) {
                    out.write(name + "\n");
                }
            }
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Mansart cannot register its accesses in META-INF/services: " + e);
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
                    + "will need the entity packages opened to io.vidocq.mansart.jpa.core:\n    provides " + SPI + " with "
                    + String.join(", ", names) + ";");
            }
        }
    }

    private void write(String qualifiedName, String source, Element origin) {
        try {
            var file = origin == null ? filer.createSourceFile(qualifiedName) : filer.createSourceFile(qualifiedName, origin);
            try (Writer out = file.openWriter()) {
                out.write(source);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Mansart cannot write " + qualifiedName, e);
        }
    }

    private void warn(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.WARNING, message, element);
    }
}
