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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/** Compiles fixture sources with {@link MansartJpaProcessor}, as an application build would. */
record Compilation(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics, Path classes, Path generated) {

    /** The fixtures of the tests: sources compiled by the tests, not by Maven. */
    static final Path FIXTURES = Path.of("src/test/fixtures");

    /** Compiles every source under {@code roots} on the class path (an unnamed module). */
    static Compilation onClassPath(Path out, Path... roots) {
        return compile(out, List.of("-classpath", dependencies()), roots);
    }

    static Compilation withProcessors(Path out, List<javax.annotation.processing.Processor> processors, Path... roots) {
        return compile(out, List.of("-classpath", dependencies()), processors, roots);
    }

    /** Compiles {@code roots} against the classes of an earlier compilation, as an incremental build does. */
    static Compilation incrementally(Path out, Compilation earlier, Path... roots) {
        return compile(out, List.of("-classpath", earlier.classes() + File.pathSeparator + dependencies()), roots);
    }

    /** Compiles every source under {@code roots}, a {@code module-info.java} among them, on the module path. */
    static Compilation onModulePath(Path out, Path... roots) {
        return compile(out, List.of("--module-path", dependencies()), roots);
    }

    /** What the application compiles against: jakarta.persistence and the provider SPI, as the tests run with them. */
    private static String dependencies() {
        String modulePath = System.getProperty("jdk.module.path");
        return modulePath != null ? modulePath : System.getProperty("java.class.path");
    }

    private static Compilation compile(Path out, List<String> options, Path... roots) {
        return compile(out, options, List.of(new MansartJpaProcessor()), roots);
    }

    private static Compilation compile(Path out, List<String> options, List<javax.annotation.processing.Processor> processors, Path... roots) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path classes = out.resolve("classes");
        Path generated = out.resolve("generated");
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            Files.createDirectories(classes);
            Files.createDirectories(generated);
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            files.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(generated));
            List<File> sources = new ArrayList<>();
            for (Path root : roots) {
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(p -> p.toString().endsWith(".java")).map(Path::toFile).forEach(sources::add);
                }
            }
            List<String> all = new ArrayList<>(options);
            all.add("-Xlint:all,-processing");
            JavaCompiler.CompilationTask task = compiler.getTask(null, files, diagnostics, all, null,
                files.getJavaFileObjectsFromFiles(sources));
            task.setProcessors(processors);
            boolean success = task.call();
            return new Compilation(success, diagnostics.getDiagnostics(), classes, generated);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The messages of a kind, for assertions. */
    List<String> messages(Diagnostic.Kind kind) {
        return diagnostics.stream().filter(d -> d.getKind() == kind).map(d -> d.getMessage(Locale.ROOT)).toList();
    }

    String source(String relativePath) {
        try {
            return Files.readString(generated.resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
