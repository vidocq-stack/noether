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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import jakarta.persistence.PersistenceException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Jakarta Persistence 3.2, §8.2: finding persistence units in the jars and directories of a class loader. */
class PersistenceUnitsTest {

    @TempDir
    Path directory;

    private static void writeJar(Path jar, String unitName, int padding) throws IOException {
        writeJar(jar, padding, unitName);
    }

    private static void writeJar(Path jar, int padding, String... unitNames) throws IOException {
        StringBuilder units = new StringBuilder();
        for (String unitName : unitNames) {
            units.append("<persistence-unit name=\"").append(unitName).append("\"/>");
        }
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry("META-INF/persistence.xml"));
            out.write(("<persistence xmlns=\"https://jakarta.ee/xml/ns/persistence\" version=\"3.2\">" + units
                + "</persistence>").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("padding.txt"));
            out.write(new byte[padding]);
            out.closeEntry();
        }
    }

    @Test
    void theUnitsOfTwoJarsAreFoundEachWithItsOwnRoot() throws Exception { // one persistence.xml per module
        Path ledger = directory.resolve("ledger.jar");
        Path archive = directory.resolve("archive.jar");
        writeJar(ledger, "ledger", 10);
        writeJar(archive, "archive", 10);
        URL ledgerRoot = ledger.toUri().toURL();
        URL archiveRoot = archive.toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {ledgerRoot, archiveRoot}, null)) {
            assertThat(PersistenceUnits.all(loader)).extracting(PersistenceUnitDefinition::name, PersistenceUnitDefinition::rootUrl)
                .containsExactly(tuple("ledger", ledgerRoot), tuple("archive", archiveRoot));
            assertThat(PersistenceUnits.find("archive", loader)).hasValueSatisfying(unit -> assertThat(unit.rootUrl())
                .isEqualTo(archiveRoot));
        }
    }

    @Test
    void twoUnitsOfOneNameInTwoJarsAreRefused() throws Exception { // BUG-20261113-06
        Path first = directory.resolve("first.jar");
        Path second = directory.resolve("second.jar");
        writeJar(first, "ledger", 10);
        writeJar(second, "ledger", 10);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {first.toUri().toURL(), second.toUri().toURL()}, null)) {
            assertThatThrownBy(() -> PersistenceUnits.find("ledger", loader)).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("ledger").hasMessageContaining("first.jar").hasMessageContaining("second.jar");
            assertThatThrownBy(() -> PersistenceUnits.all(loader)).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("ledger").hasMessageContaining("first.jar").hasMessageContaining("second.jar");
        }
    }

    @Test
    void twoUnitsOfOneNameInOneFileAreRefused() throws Exception {
        Path jar = directory.resolve("twice.jar");
        writeJar(jar, 10, "ledger", "ledger");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            assertThatThrownBy(() -> PersistenceUnits.all(loader)).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("ledger").hasMessageContaining("twice.jar");
            assertThatThrownBy(() -> PersistenceUnits.find("ledger", loader)).isInstanceOf(PersistenceException.class);
        }
    }

    @Test
    void aDuplicateNameLeavesTheOtherUnitsToBeFoundByName() throws Exception {
        Path first = directory.resolve("first.jar");
        Path second = directory.resolve("second.jar");
        writeJar(first, 10, "ledger", "archive");
        writeJar(second, "ledger", 10);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {first.toUri().toURL(), second.toUri().toURL()}, null)) {
            assertThat(PersistenceUnits.find("archive", loader)).isPresent();
        }
    }

    @Test
    void aFileThatTheLoaderListsTwiceIsNotADuplicate() throws Exception {
        Path jar = directory.resolve("shared.jar");
        writeJar(jar, "ledger", 10);
        URL url = jar.toUri().toURL();
        try (URLClassLoader parent = new URLClassLoader(new URL[] {url}, null);
             URLClassLoader loader = new URLClassLoader(new URL[] {url}, parent)) {
            assertThat(PersistenceUnits.find("ledger", loader)).isPresent();
            assertThat(PersistenceUnits.names(loader)).containsExactly("ledger");
        }
    }

    @Test
    void aUnitIsFoundInAJar() throws Exception {
        Path jar = directory.resolve("units.jar");
        writeJar(jar, "in-jar", 10);
        URL url = jar.toUri().toURL();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {url}, null)) {
            assertThat(PersistenceUnits.find("in-jar", loader)).hasValueSatisfying(unit -> assertThat(unit.rootUrl()).isEqualTo(url));
            assertThat(PersistenceUnits.find("absent", loader)).isEmpty();
        }
    }

    /**
     * A jar rewritten under the same name, as the TCK does for every test, must be read afresh: the JDK's jar URL
     * cache would keep the previous one open and read garbage from the new one ("invalid LOC header").
     */
    @Test
    void aJarRewrittenUnderTheSameNameIsReadAfresh() throws Exception {
        Path jar = directory.resolve("rewritten.jar");
        writeJar(jar, "first", 10);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            assertThat(PersistenceUnits.find("first", loader)).isPresent();
        }
        writeJar(jar, "second", 5000);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            assertThat(PersistenceUnits.find("second", loader)).isPresent();
            assertThat(PersistenceUnits.find("first", loader)).isEmpty();
        }
    }
}
