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

import io.vidocq.noether.jpa.core.spi.PersistenceUnitDeclaration;
import io.vidocq.noether.jpa.core.spi.PersistenceUnitSource;
import jakarta.persistence.PersistenceException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Finds the persistence units declared by the {@code META-INF/persistence.xml} files of a class loader (§8.2, §9.2), and
 * those that the {@link PersistenceUnitSource}s of a runtime declare or override.
 */
public final class PersistenceUnits {

    private static final String RESOURCE = "META-INF/persistence.xml";

    private PersistenceUnits() {
    }

    /**
     * Reads a {@code persistence.xml} without the JDK's jar URL cache: a cached {@code JarFile} stays open and reads
     * garbage once the jar is rewritten under the same name, as test harnesses (and redeployments) do.
     */
    private static InputStream open(URL file) throws IOException {
        URLConnection connection = file.openConnection();
        connection.setUseCaches(false);
        return connection.getInputStream();
    }

    /**
     * The unit named {@code name}; empty if there is none.
     *
     * @throws PersistenceException if several units have that name, in one file or in several
     */
    public static Optional<PersistenceUnitDefinition> find(String name, ClassLoader loader) {
        return find(name, loader, sources(loader));
    }

    static Optional<PersistenceUnitDefinition> find(String name, ClassLoader loader, List<PersistenceUnitSource> sources) {
        List<Declared> named = declared(loader, sources).stream()
            .filter(declared -> declared.unit().name().equals(name)).toList();
        if (named.size() > 1) {
            throw duplicate(name, named);
        }
        return named.stream().findFirst().map(Declared::unit);
    }

    /**
     * The names of the units of every {@code persistence.xml} of {@code loader}, in class path order. A file that the
     * loader lists twice, through a parent loader for instance, is read once.
     *
     * @throws PersistenceException if two units have the same name
     */
    public static List<String> names(ClassLoader loader) {
        return names(loader, sources(loader));
    }

    static List<String> names(ClassLoader loader, List<PersistenceUnitSource> sources) {
        return all(loader, sources).stream().map(PersistenceUnitDefinition::name).toList();
    }

    /**
     * The units of every {@code persistence.xml} of {@code loader}, in class path order. A file that the loader lists
     * twice, through a parent loader for instance, is read once.
     *
     * @throws PersistenceException if two units have the same name, in one file or in several
     */
    public static List<PersistenceUnitDefinition> all(ClassLoader loader) {
        return all(loader, sources(loader));
    }

    static List<PersistenceUnitDefinition> all(ClassLoader loader, List<PersistenceUnitSource> sources) {
        List<Declared> units = declared(loader, sources);
        Map<String, List<Declared>> byName = units.stream()
            .collect(Collectors.groupingBy(declared -> declared.unit().name(), LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<Declared>> unit : byName.entrySet()) {
            if (unit.getValue().size() > 1) {
                throw duplicate(unit.getKey(), unit.getValue());
            }
        }
        return units.stream().map(Declared::unit).toList();
    }

    /** A unit and what declares it: a {@code persistence.xml}, or a source as it names itself. */
    private record Declared(PersistenceUnitDefinition unit, String origin, boolean bySource) {
    }

    /**
     * The units of the files, then those the sources declare. A declaration that only overrides applies to the unit of
     * its name, or declares it when there is none; a unit declared when alone joins only an application with no unit.
     */
    private static List<Declared> declared(ClassLoader loader, List<PersistenceUnitSource> sources) {
        List<Declared> units = files(loader);
        List<PersistenceUnitDeclaration> declarations = sources.stream()
            .flatMap(source -> source.units(loader).stream()).toList();
        declarations.stream().filter(declaration -> declaration.declares() && !declaration.whenAlone())
            .forEach(declaration -> units.add(bySource(declaration, loader)));
        declarations.stream().filter(declaration -> !declaration.declares())
            .forEach(declaration -> override(units, declaration, loader));
        if (units.isEmpty()) {
            declarations.stream().filter(PersistenceUnitDeclaration::whenAlone)
                .map(declaration -> bySource(declaration, loader))
                .filter(declared -> !ManagedClasses.of(declared.unit()).isEmpty())
                .forEach(units::add);
        }
        return units;
    }

    private static Declared bySource(PersistenceUnitDeclaration declaration, ClassLoader loader) {
        return new Declared(SourceUnits.definition(declaration, loader), declaration.origin(), true);
    }

    /** Overrides the data sources and properties of the units of that name, or declares the unit if there is none. */
    private static void override(List<Declared> units, PersistenceUnitDeclaration declaration, ClassLoader loader) {
        boolean found = false;
        for (int i = 0; i < units.size(); i++) {
            Declared declared = units.get(i);
            if (declared.unit().name().equals(declaration.name())) {
                PersistenceUnitDefinition overridden = SourceUnits.overridden(declared.unit(), declaration);
                units.set(i, new Declared(overridden, declared.origin(), declared.bySource()));
                found = true;
            }
        }
        if (!found) {
            units.add(bySource(declaration, loader));
        }
    }

    /**
     * The sources of the module layer of Noether (and its parents), those of Noether's own class loader, then those of
     * the application's, each once. Noether's loader serves a build tool that runs Noether on its own class path, with
     * the sources of its runtime, and hands it a loader that lists the resources of the application alone.
     */
    private static List<PersistenceUnitSource> sources(ClassLoader loader) {
        try {
            Map<String, PersistenceUnitSource> sources = new LinkedHashMap<>();
            ModuleLayer layer = PersistenceUnits.class.getModule().getLayer();
            if (layer != null) {
                ServiceLoader.load(layer, PersistenceUnitSource.class).forEach(source -> once(sources, source));
            }
            ServiceLoader.load(PersistenceUnitSource.class, PersistenceUnits.class.getClassLoader())
                .forEach(source -> once(sources, source));
            ServiceLoader.load(PersistenceUnitSource.class, loader).forEach(source -> once(sources, source));
            return List.copyOf(sources.values());
        } catch (ServiceConfigurationError e) {
            throw new PersistenceException("A persistence unit source cannot be loaded: " + e.getMessage(), e);
        }
    }

    private static void once(Map<String, PersistenceUnitSource> sources, PersistenceUnitSource source) {
        sources.putIfAbsent(source.getClass().getName(), source);
    }

    private static List<Declared> files(ClassLoader loader) {
        try {
            List<Declared> units = new ArrayList<>();
            Set<String> read = new HashSet<>();
            Enumeration<URL> files = loader.getResources(RESOURCE);
            while (files.hasMoreElements()) {
                URL file = files.nextElement();
                if (read.add(file.toExternalForm())) {
                    try (InputStream in = open(file)) {
                        for (PersistenceUnitDefinition unit : PersistenceXmlParser.parse(in, PersistenceXmlParser.rootOf(file), loader)) {
                            units.add(new Declared(unit, file.toExternalForm(), false));
                        }
                    }
                }
            }
            return units;
        } catch (IOException e) {
            throw new PersistenceException("Unable to read " + RESOURCE, e);
        }
    }

    /** BUG-20261113-06: the first unit of a name used to win silently, whatever the others said. */
    private static PersistenceException duplicate(String name, List<Declared> declarations) {
        String origins = declarations.stream().map(Declared::origin).collect(Collectors.joining(", "));
        String message = "Persistence unit " + name + " is declared " + declarations.size() + " times, in " + origins
            + ": a persistence unit name must be unique among the persistence.xml files of the application and the units"
            + " its runtime declares";
        boolean byFile = declarations.stream().anyMatch(declared -> !declared.bySource());
        boolean bySource = declarations.stream().anyMatch(Declared::bySource);
        return new PersistenceException(byFile && bySource ? message + "; a unit source may only override the data sources"
            + " and the properties of a unit that a persistence.xml declares" : message);
    }
}
