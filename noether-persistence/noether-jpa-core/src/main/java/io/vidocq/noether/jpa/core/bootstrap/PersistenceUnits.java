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
import java.util.Set;
import java.util.stream.Collectors;

/** Finds the persistence units declared by the {@code META-INF/persistence.xml} files of a class loader (§8.2, §9.2). */
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
        List<Declared> named = declared(loader).stream().filter(declared -> declared.unit().name().equals(name)).toList();
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
        return all(loader).stream().map(PersistenceUnitDefinition::name).toList();
    }

    /**
     * The units of every {@code persistence.xml} of {@code loader}, in class path order. A file that the loader lists
     * twice, through a parent loader for instance, is read once.
     *
     * @throws PersistenceException if two units have the same name, in one file or in several
     */
    public static List<PersistenceUnitDefinition> all(ClassLoader loader) {
        List<Declared> units = declared(loader);
        Map<String, List<Declared>> byName = units.stream()
            .collect(Collectors.groupingBy(declared -> declared.unit().name(), LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<String, List<Declared>> unit : byName.entrySet()) {
            if (unit.getValue().size() > 1) {
                throw duplicate(unit.getKey(), unit.getValue());
            }
        }
        return units.stream().map(Declared::unit).toList();
    }

    /** A unit and the {@code persistence.xml} that declares it. */
    private record Declared(PersistenceUnitDefinition unit, URL file) {
    }

    private static List<Declared> declared(ClassLoader loader) {
        try {
            List<Declared> units = new ArrayList<>();
            Set<String> read = new HashSet<>();
            Enumeration<URL> files = loader.getResources(RESOURCE);
            while (files.hasMoreElements()) {
                URL file = files.nextElement();
                if (read.add(file.toExternalForm())) {
                    try (InputStream in = open(file)) {
                        for (PersistenceUnitDefinition unit : PersistenceXmlParser.parse(in, PersistenceXmlParser.rootOf(file), loader)) {
                            units.add(new Declared(unit, file));
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
        String files = declarations.stream().map(declared -> declared.file().toExternalForm()).collect(Collectors.joining(", "));
        return new PersistenceException("Persistence unit " + name + " is declared " + declarations.size() + " times, in "
            + files + ": a persistence unit name must be unique among the persistence.xml files of the application");
    }
}
