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
package io.vidocq.noether.jpa.core.bootstrap;

import jakarta.persistence.PersistenceException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;

/**
 * The managed classes of a persistence unit (§8.2.1.6): the listed classes, then, unless unlisted classes are
 * excluded, the classes annotated {@code @Entity}, {@code @Embeddable}, {@code @MappedSuperclass} or
 * {@code @Converter} found in the unit root and in its {@code jar-file}s. A class is recognised from its class file
 * alone, without being loaded.
 */
public final class ManagedClasses {

    private static final Set<String> MANAGED = Set.of("Ljakarta/persistence/Entity;", "Ljakarta/persistence/Embeddable;",
        "Ljakarta/persistence/MappedSuperclass;", "Ljakarta/persistence/Converter;");

    private ManagedClasses() {
    }

    /** The names of the managed classes of {@code unit}, the listed ones first, without duplicates. */
    public static List<String> of(PersistenceUnitDefinition unit) {
        Set<String> classes = new LinkedHashSet<>(unit.managedClassNames());
        if (!unit.excludeUnlistedClasses()) {
            if (unit.rootUrl() != null) {
                scan(unit.rootUrl(), classes);
            }
            for (URL jarFile : unit.jarFileUrls()) {
                scan(jarFile, classes);
            }
        }
        return List.copyOf(classes);
    }

    private static void scan(URL location, Set<String> into) {
        try {
            if (isDirectory(location)) {
                scanDirectory(Path.of(location.toURI()), into);
            } else {
                scanArchive(location, into);
            }
        } catch (IOException | UncheckedIOException | URISyntaxException | IllegalArgumentException e) {
            throw new PersistenceException("Unable to scan " + location + " for the managed classes of the persistence unit", e);
        }
    }

    private static void scanDirectory(Path root, Set<String> into) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String path = root.relativize(file).toString().replace(File.separatorChar, '/');
                if (isClassFile(path)) {
                    consider(Files.readAllBytes(file), into);
                }
            }
        }
    }

    private static void scanArchive(URL location, Set<String> into) throws IOException {
        // jar:file:/x.jar!/ names the whole archive
        URL archive = "jar".equals(location.getProtocol())
            ? ((JarURLConnection) location.openConnection()).getJarFileURL() : location;
        URLConnection connection = archive.openConnection();
        // the jar may have been rewritten under the same name since it was cached (see PersistenceUnits)
        connection.setUseCaches(false);
        try (InputStream in = connection.getInputStream(); JarInputStream jar = new JarInputStream(in)) {
            for (JarEntry entry = jar.getNextJarEntry(); entry != null; entry = jar.getNextJarEntry()) {
                if (!entry.isDirectory() && isClassFile(entry.getName())) {
                    consider(jar.readAllBytes(), into);
                }
            }
        }
    }

    /**
     * A directory of class files: a {@code file:} directory, or a module of the runtime image. The root of a unit
     * declared in a module that jlink linked into the image is {@code jrt:/<module>/}, a directory of the image's file
     * system and not a stream to read (BUG-20261113-02).
     */
    private static boolean isDirectory(URL location) throws URISyntaxException {
        return "jrt".equals(location.getProtocol())
            || "file".equals(location.getProtocol()) && Files.isDirectory(Path.of(location.toURI()));
    }

    /** A class file of the unnamed version; versioned entries, module and package descriptors are not classes. */
    private static boolean isClassFile(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith(".class") && !path.startsWith("META-INF/") && !name.equals("module-info.class")
            && !name.equals("package-info.class");
    }

    /** Parsing is lazy: an attribute Noether cannot read fails when it is reached, so the whole reading is guarded. */
    private static void consider(byte[] bytes, Set<String> into) {
        try {
            ClassModel model = ClassFile.of().parse(bytes);
            boolean managed = model.findAttribute(Attributes.runtimeVisibleAnnotations()).stream()
                .flatMap(a -> a.annotations().stream())
                .anyMatch(a -> MANAGED.contains(a.className().stringValue()));
            if (managed) {
                into.add(model.thisClass().asInternalName().replace('/', '.'));
            }
        } catch (IllegalArgumentException e) {
            // not a class file Noether can read (newer version, obfuscated attributes): not a managed class
        }
    }
}
