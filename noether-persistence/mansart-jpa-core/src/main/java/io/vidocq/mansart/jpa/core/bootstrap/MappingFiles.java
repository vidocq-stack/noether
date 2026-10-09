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

import io.vidocq.mansart.jpa.core.model.xml.MappingFile;
import jakarta.persistence.PersistenceException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The XML mapping files of a persistence unit (§8.2.1.6.2): the {@code META-INF/orm.xml} of its root and of its
 * {@code jar-file}s, then the {@code mapping-file}s it lists, each looked up in its root then as a resource of its
 * class loader. A listed file that cannot be found is an error.
 */
public final class MappingFiles {

    private static final String DEFAULT = "META-INF/orm.xml";

    private MappingFiles() {
    }

    /** The mapping files of {@code unit}, without duplicates, in the order the unit's metadata reads them. */
    public static List<MappingFile> of(PersistenceUnitDefinition unit, ClassLoader loader) {
        Map<String, MappingFile> files = new LinkedHashMap<>();
        List<URL> roots = new ArrayList<>();
        if (unit.rootUrl() != null) {
            roots.add(unit.rootUrl());
        }
        roots.addAll(unit.jarFileUrls());
        for (URL root : roots) {
            URL orm = in(root, DEFAULT);
            if (orm != null) {
                files.putIfAbsent(orm.toExternalForm(), new MappingFile(DEFAULT, orm));
            }
        }
        for (String name : unit.mappingFileNames()) {
            String path = name.startsWith("/") ? name.substring(1) : name;
            URL url = unit.rootUrl() != null ? in(unit.rootUrl(), path) : null;
            if (url == null) {
                url = loader.getResource(path);
            }
            if (url == null) {
                throw new PersistenceException("The mapping file " + name + " of the persistence unit " + unit.name()
                    + " cannot be found (§8.2.1.6.2)");
            }
            files.putIfAbsent(url.toExternalForm(), new MappingFile(name, url));
        }
        return List.copyOf(files.values());
    }

    /** The entry {@code path} of the directory or jar {@code root}, {@code null} when it has none. */
    private static URL in(URL root, String path) {
        try {
            if ("file".equals(root.getProtocol()) && Files.isDirectory(Path.of(root.toURI()))) {
                Path file = Path.of(root.toURI()).resolve(path);
                return Files.isRegularFile(file) ? file.toUri().toURL() : null;
            }
            String archive = root.toExternalForm();
            if (archive.startsWith("jar:")) {
                int separator = archive.indexOf("!/");
                archive = archive.substring("jar:".length(), separator < 0 ? archive.length() : separator);
            }
            URL entry = URI.create("jar:" + archive + "!/" + path).toURL();
            URLConnection connection = entry.openConnection();
            // the jar may have been rewritten under the same name since it was cached (see PersistenceUnits)
            connection.setUseCaches(false);
            try (InputStream _ = connection.getInputStream()) {
                return entry;
            }
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException | URISyntaxException | IllegalArgumentException e) {
            throw new PersistenceException("Unable to look for " + path + " in " + root, e);
        }
    }
}
