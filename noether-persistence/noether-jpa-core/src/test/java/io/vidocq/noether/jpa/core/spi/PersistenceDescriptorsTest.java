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
package io.vidocq.noether.jpa.core.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.PersistenceException;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Jakarta Persistence 3.2, §8.2: a container that injects a persistence unit without a name takes the only unit of
 * the deployment, read by the provider's own {@code persistence.xml} parser.
 */
class PersistenceDescriptorsTest {

    @TempDir
    Path directory;

    private URL root(String name, String descriptor) throws IOException {
        Path root = directory.resolve(name);
        Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(root.resolve("META-INF/persistence.xml"), descriptor);
        return root.toUri().toURL();
    }

    private static String units(String... names) {
        var xml = new StringBuilder("<persistence xmlns=\"https://jakarta.ee/xml/ns/persistence\" version=\"3.2\">");
        for (String name : names) {
            xml.append("<persistence-unit name=\"").append(name).append("\"/>");
        }
        return xml.append("</persistence>").toString();
    }

    @Test
    void theOnlyUnitOfTheDeploymentIsTheDefault() throws IOException {
        try (var loader = new URLClassLoader(new URL[] {root("one", units("only"))}, null)) {
            assertThat(PersistenceDescriptors.defaultUnitName(loader)).isEqualTo("only");
        }
    }

    @Test
    void aDescriptorSeenThroughTwoClassLoadersCountsOnce() throws IOException {
        URL root = root("shared", units("shared"));
        try (var parent = new URLClassLoader(new URL[] {root}, null);
             var child = new URLClassLoader(new URL[] {root}, parent)) {
            assertThat(Collections.list(child.getResources("META-INF/persistence.xml"))).hasSize(2);
            assertThat(PersistenceDescriptors.defaultUnitName(child)).isEqualTo("shared");
        }
    }

    @Test
    void noUnitOrSeveralUnitsHaveNoDefault() throws IOException {
        try (var empty = new URLClassLoader(new URL[] {directory.toUri().toURL()}, null)) {
            assertThatThrownBy(() -> PersistenceDescriptors.defaultUnitName(empty))
                .isInstanceOf(PersistenceException.class).hasMessageContaining("found []");
        }
        try (var two = new URLClassLoader(new URL[] {root("first", units("a")), root("second", units("b"))}, null)) {
            assertThatThrownBy(() -> PersistenceDescriptors.defaultUnitName(two))
                .isInstanceOf(PersistenceException.class).hasMessageContaining("found [a, b]");
        }
        try (var both = new URLClassLoader(new URL[] {root("both", units("c", "d"))}, null)) {
            assertThatThrownBy(() -> PersistenceDescriptors.defaultUnitName(both))
                .isInstanceOf(PersistenceException.class).hasMessageContaining("found [c, d]");
        }
    }

    @Test
    void aDescriptorWithADtdIsRefused() throws IOException {
        String descriptor = "<!DOCTYPE persistence [<!ENTITY name \"dtd\">]>" + units("&name;");
        try (var loader = new URLClassLoader(new URL[] {root("dtd", descriptor)}, null)) {
            assertThatThrownBy(() -> PersistenceDescriptors.defaultUnitName(loader)).isInstanceOf(PersistenceException.class);
        }
    }
}
