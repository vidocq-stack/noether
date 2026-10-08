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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.jpa.core.model.build.fixtures.Audited;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Customer;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Geo;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Level;
import io.vidocq.mansart.jpa.core.model.build.fixtures.MoneyConverter;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedClassesTest {

    @TempDir
    Path temp;

    private static PersistenceUnitDefinition unit(List<String> classes, boolean excludeUnlisted, URL root, List<URL> jarFiles) {
        return new PersistenceUnitDefinition("unit", null, null, PersistenceUnitTransactionType.RESOURCE_LOCAL, null, null,
            List.of(), jarFiles, classes, excludeUnlisted, SharedCacheMode.UNSPECIFIED, ValidationMode.AUTO, Map.of(), root,
            "3.2", List.of(), null, ManagedClassesTest.class.getClassLoader());
    }

    /** The directory the test classes were compiled to. */
    private static URL testClassesRoot() throws Exception {
        String resource = Customer.class.getName().replace('.', '/') + ".class";
        String url = Customer.class.getClassLoader().getResource(resource).toExternalForm();
        return new java.net.URI(url.substring(0, url.length() - resource.length())).toURL();
    }

    private Path jarOf(String name, Class<?>... classes) throws IOException {
        Path jar = temp.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream entries = new JarOutputStream(out)) {
            for (Class<?> type : classes) {
                String entry = type.getName().replace('.', '/') + ".class";
                entries.putNextEntry(new JarEntry(entry));
                try (InputStream bytes = type.getClassLoader().getResourceAsStream(entry)) {
                    bytes.transferTo(entries);
                }
                entries.closeEntry();
            }
            entries.putNextEntry(new JarEntry("META-INF/versions/21/" + Customer.class.getName().replace('.', '/') + ".class"));
            entries.closeEntry();
        }
        return jar;
    }

    @Test
    void onlyTheListedClassesWhenUnlistedClassesAreExcluded() throws Exception {
        PersistenceUnitDefinition unit = unit(List.of(Customer.class.getName(), Level.class.getName()), true, testClassesRoot(), List.of());
        assertThat(ManagedClasses.of(unit)).containsExactly(Customer.class.getName(), Level.class.getName());
    }

    @Test
    void theAnnotatedClassesOfADirectoryRootAreAddedAfterTheListedOnes() throws Exception {
        PersistenceUnitDefinition unit = unit(List.of(Level.class.getName()), false, testClassesRoot(), List.of());
        List<String> classes = ManagedClasses.of(unit);
        assertThat(classes.getFirst()).isEqualTo(Level.class.getName());
        assertThat(classes).contains(Customer.class.getName(), Audited.class.getName(), Geo.class.getName(),
            MoneyConverter.class.getName()).doesNotContain(ManagedClassesTest.class.getName()).doesNotHaveDuplicates();
    }

    @Test
    void theAnnotatedClassesOfAJarRootAndOfJarFilesAreFound() throws Exception {
        Path root = jarOf("root.jar", Customer.class, Level.class);
        Path other = jarOf("other.jar", MoneyConverter.class, ManagedClassesTest.class);
        PersistenceUnitDefinition unit = unit(List.of(), false, root.toUri().toURL(), List.of(other.toUri().toURL()));
        assertThat(ManagedClasses.of(unit)).containsExactly(Customer.class.getName(), MoneyConverter.class.getName());
    }

    @Test
    void aJarUrlRootAndUnreadableEntriesAreHandled() throws Exception {
        Path root = jarOf("root.jar", Customer.class);
        try (java.nio.file.FileSystem zip = java.nio.file.FileSystems.newFileSystem(root)) {
            Files.createDirectories(zip.getPath("com/acme"));
            Files.write(zip.getPath("com/acme/Broken.class"), new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0});
        }
        URL jarUrl = java.net.URI.create("jar:" + root.toUri() + "!/").toURL();
        PersistenceUnitDefinition unit = unit(List.of(), false, jarUrl, List.of());
        assertThat(ManagedClasses.of(unit)).containsExactly(Customer.class.getName());
    }

    @Test
    void aUnitDefinedInCodeHasOnlyItsListedClasses() {
        PersistenceUnitDefinition unit = unit(List.of(Customer.class.getName()), false, null, List.of());
        assertThat(ManagedClasses.of(unit)).containsExactly(Customer.class.getName());
    }

    @Test
    void anUnreadableJarFileIsAPersistenceException() throws Exception {
        Path missing = temp.resolve("missing.jar");
        PersistenceUnitDefinition unit = unit(List.of(), false, null, List.of(missing.toUri().toURL()));
        assertThatThrownBy(() -> ManagedClasses.of(unit)).isInstanceOf(PersistenceException.class).hasMessageContaining("missing.jar");
    }
}
