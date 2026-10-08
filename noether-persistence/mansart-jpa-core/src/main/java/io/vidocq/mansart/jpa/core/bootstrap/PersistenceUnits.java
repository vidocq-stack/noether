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
package io.vidocq.mansart.jpa.core.bootstrap;

import jakarta.persistence.PersistenceException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Enumeration;
import java.util.Optional;

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

    /** The first unit named {@code name}, in class path order; empty if there is none. */
    public static Optional<PersistenceUnitDefinition> find(String name, ClassLoader loader) {
        try {
            Enumeration<URL> files = loader.getResources(RESOURCE);
            while (files.hasMoreElements()) {
                URL file = files.nextElement();
                try (InputStream in = open(file)) {
                    for (PersistenceUnitDefinition unit : PersistenceXmlParser.parse(in, PersistenceXmlParser.rootOf(file), loader)) {
                        if (unit.name().equals(name)) {
                            return Optional.of(unit);
                        }
                    }
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            throw new PersistenceException("Unable to read " + RESOURCE, e);
        }
    }
}
