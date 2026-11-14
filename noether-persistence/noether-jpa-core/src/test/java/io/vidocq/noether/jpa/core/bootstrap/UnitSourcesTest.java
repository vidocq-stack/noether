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
import static org.assertj.core.api.Assertions.entry;

import io.vidocq.noether.jpa.core.model.build.fixtures.Customer;
import io.vidocq.noether.jpa.core.spi.PersistenceUnitDeclaration;
import io.vidocq.noether.jpa.core.spi.PersistenceUnitSource;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitTransactionType;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The units a {@link PersistenceUnitSource} declares, merged with those of the {@code persistence.xml} files: a runtime
 * declares units from its configuration (Vidocq/vidocq#551).
 */
class UnitSourcesTest {

    private static final String KEYS = "the vidocq.persistence.\"ledger\".* keys";

    @TempDir
    Path directory;

    @Test
    void aSourceDeclaresAUnitThatNoFileDeclares() throws Exception {
        URL root = directory.resolve("classes").toUri().toURL();
        PersistenceUnitSource source = loader -> List.of(PersistenceUnitDeclaration.builder("ledger", KEYS)
            .root(root).packages(List.of("org.acme.ledger")).transactionType(PersistenceUnitTransactionType.JTA)
            .qualifiers(List.of("org.acme.Ledger")).scope("jakarta.enterprise.context.RequestScoped")
            .jtaDataSource("ledger-pool").nonJtaDataSource("ledger-ids").property("jakarta.persistence.schema-generation"
                + ".database.action", "create").build());

        try (URLClassLoader loader = loader()) {
            PersistenceUnitDefinition unit = PersistenceUnits.find("ledger", loader, List.of(source)).orElseThrow();

            assertThat(unit.rootUrl()).isEqualTo(root);
            assertThat(unit.managedPackages()).containsExactly("org.acme.ledger");
            assertThat(unit.excludeUnlistedClasses()).isFalse();
            assertThat(unit.transactionType()).isEqualTo(PersistenceUnitTransactionType.JTA);
            assertThat(unit.qualifierAnnotationNames()).containsExactly("org.acme.Ledger");
            assertThat(unit.scopeAnnotationName()).isEqualTo("jakarta.enterprise.context.RequestScoped");
            assertThat(unit.jtaDataSourceName()).isEqualTo("ledger-pool");
            assertThat(unit.nonJtaDataSourceName()).isEqualTo("ledger-ids");
            assertThat(unit.properties())
                .containsExactly(entry("jakarta.persistence.schema-generation.database.action", "create"));
            assertThat(unit.classLoader()).isSameAs(loader);
            assertThat(PersistenceUnits.names(loader, List.of(source))).containsExactly("ledger");
        }
    }

    @Test
    void aUnitWhoseClassesAreListedManagesThemAlone() throws Exception {
        PersistenceUnitSource source = loader -> List.of(PersistenceUnitDeclaration.builder("ledger", KEYS)
            .classes(List.of("org.acme.Entry", "org.acme.Account")).build());

        try (URLClassLoader loader = loader()) {
            PersistenceUnitDefinition unit = PersistenceUnits.find("ledger", loader, List.of(source)).orElseThrow();

            assertThat(unit.managedClassNames()).containsExactly("org.acme.Entry", "org.acme.Account");
            assertThat(unit.excludeUnlistedClasses()).isTrue();
            assertThat(unit.transactionType()).isEqualTo(PersistenceUnitTransactionType.RESOURCE_LOCAL);
        }
    }

    @Test
    void aSourceOverridesTheDataSourcesAndPropertiesOfAUnitThatAFileDeclares() throws Exception {
        Path module = moduleWith("""
            <persistence-unit name="ledger" transaction-type="JTA">
              <jta-data-source>development</jta-data-source>
              <class>org.acme.Entry</class>
              <properties>
                <property name="kept" value="file"/>
                <property name="replaced" value="file"/>
              </properties>
            </persistence-unit>""");
        URL elsewhere = directory.resolve("elsewhere").toUri().toURL();
        PersistenceUnitSource source = loader -> List.of(PersistenceUnitDeclaration.builder("ledger", KEYS)
            .defaultRoot(elsewhere)
            .jtaDataSource("production").property("replaced", "keys").property("added", "keys").build());

        try (URLClassLoader loader = loader(module)) {
            PersistenceUnitDefinition unit = PersistenceUnits.find("ledger", loader, List.of(source)).orElseThrow();

            assertThat(unit.rootUrl()).isEqualTo(module.toUri().toURL());
            assertThat(unit.managedClassNames()).containsExactly("org.acme.Entry");
            assertThat(unit.transactionType()).isEqualTo(PersistenceUnitTransactionType.JTA);
            assertThat(unit.jtaDataSourceName()).isEqualTo("production");
            assertThat(unit.properties()).containsExactly(
                entry("kept", "file"), entry("replaced", "keys"), entry("added", "keys"));
            assertThat(PersistenceUnits.all(loader, List.of(source))).hasSize(1);
        }
    }

    @Test
    void anOverrideOfAUnitThatNoFileDeclaresDeclaresItAtItsDefaultRoot() throws Exception {
        URL application = directory.resolve("application").toUri().toURL();
        PersistenceUnitSource source = loader -> List.of(PersistenceUnitDeclaration.builder("ledger", KEYS)
            .defaultRoot(application).jtaDataSource("production").build());

        try (URLClassLoader loader = loader()) {
            PersistenceUnitDefinition unit = PersistenceUnits.find("ledger", loader, List.of(source)).orElseThrow();

            assertThat(unit.rootUrl()).isEqualTo(application);
            assertThat(unit.jtaDataSourceName()).isEqualTo("production");
        }
    }

    @Test
    void aDefaultTransactionTypeAppliesToTheUnitTheOverrideDeclaresOnly() throws Exception {
        URL application = directory.resolve("application").toUri().toURL();
        PersistenceUnitSource source = loader -> List.of(
            PersistenceUnitDeclaration.builder("ledger", KEYS).defaultRoot(application)
                .defaultTransactionType(PersistenceUnitTransactionType.JTA).build(),
            PersistenceUnitDeclaration.builder("archive", "the vidocq.persistence.archive.* keys").defaultRoot(application)
                .defaultTransactionType(PersistenceUnitTransactionType.JTA).jtaDataSource("notes").build());
        Path module = moduleWith("<persistence-unit name=\"archive\" transaction-type=\"RESOURCE_LOCAL\"/>");

        try (URLClassLoader loader = loader(module)) {
            assertThat(PersistenceUnits.find("ledger", loader, List.of(source)).orElseThrow().transactionType())
                .isEqualTo(PersistenceUnitTransactionType.JTA);
            PersistenceUnitDefinition archive = PersistenceUnits.find("archive", loader, List.of(source)).orElseThrow();
            assertThat(archive.transactionType()).isEqualTo(PersistenceUnitTransactionType.RESOURCE_LOCAL);
            assertThat(archive.jtaDataSourceName()).isEqualTo("notes");
        }
    }

    @Test
    void theDataSourceOfAUnitIsTheOneOfItsTransactionType() throws Exception {
        URL application = directory.resolve("application").toUri().toURL();
        Path module = moduleWith("""
            <persistence-unit name="local" transaction-type="RESOURCE_LOCAL"/>
            <persistence-unit name="managed" transaction-type="JTA"><non-jta-data-source>ids</non-jta-data-source>
            </persistence-unit>""");
        PersistenceUnitSource source = loader -> List.of(
            PersistenceUnitDeclaration.builder("local", KEYS).dataSource("production").build(),
            PersistenceUnitDeclaration.builder("managed", KEYS).dataSource("production").build(),
            PersistenceUnitDeclaration.builder("ledger", KEYS).defaultRoot(application)
                .defaultTransactionType(PersistenceUnitTransactionType.JTA).dataSource("production")
                .nonJtaDataSource("identifiers").build());

        try (URLClassLoader loader = loader(module)) {
            PersistenceUnitDefinition local = PersistenceUnits.find("local", loader, List.of(source)).orElseThrow();
            assertThat(local.nonJtaDataSourceName()).isEqualTo("production");
            assertThat(local.jtaDataSourceName()).isNull();
            PersistenceUnitDefinition managed = PersistenceUnits.find("managed", loader, List.of(source)).orElseThrow();
            assertThat(managed.jtaDataSourceName()).isEqualTo("production");
            assertThat(managed.nonJtaDataSourceName()).isEqualTo("ids");
            PersistenceUnitDefinition ledger = PersistenceUnits.find("ledger", loader, List.of(source)).orElseThrow();
            assertThat(ledger.jtaDataSourceName()).isEqualTo("production");
            assertThat(ledger.nonJtaDataSourceName()).isEqualTo("identifiers");
        }
    }

    @Test
    void aSourceThatDeclaresAUnitOfAFileAgainIsADuplicate() throws Exception {
        Path module = moduleWith("<persistence-unit name=\"ledger\"/>");
        PersistenceUnitSource source = loader -> List.of(PersistenceUnitDeclaration.builder("ledger", KEYS)
            .qualifiers(List.of("org.acme.Ledger")).build());

        try (URLClassLoader loader = loader(module)) {
            assertThatThrownBy(() -> PersistenceUnits.find("ledger", loader, List.of(source)))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("Persistence unit ledger is declared 2 times")
                .hasMessageContaining(module.toUri().toURL() + "META-INF/persistence.xml")
                .hasMessageContaining(KEYS)
                .hasMessageContaining("may only override the data sources and the properties of a unit that a"
                    + " persistence.xml declares");
        }
    }

    @Test
    void twoSourcesThatDeclareOneUnitAreADuplicate() throws Exception {
        PersistenceUnitSource first = loader -> List.of(PersistenceUnitDeclaration.builder("ledger", KEYS)
            .transactionType(PersistenceUnitTransactionType.JTA).build());
        PersistenceUnitSource second = loader -> List.of(PersistenceUnitDeclaration.builder("ledger", "another runtime")
            .transactionType(PersistenceUnitTransactionType.JTA).build());

        try (URLClassLoader loader = loader()) {
            assertThatThrownBy(() -> PersistenceUnits.all(loader, List.of(first, second)))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("Persistence unit ledger is declared 2 times, in " + KEYS + ", another runtime");
        }
    }

    @Test
    void aUnitDeclaredWhenAloneIsDeclaredWhenNoOtherUnitIsAndItsRootHoldsAnEntity() throws Exception {
        URL testClasses = testClasses();
        PersistenceUnitSource source = loader -> List.of(PersistenceUnitDeclaration.builder("default", "the application")
            .defaultRoot(testClasses).whenAlone().build());

        try (URLClassLoader loader = loader()) {
            PersistenceUnitDefinition unit = PersistenceUnits.find("default", loader, List.of(source)).orElseThrow();

            assertThat(unit.rootUrl()).isEqualTo(testClasses);
            assertThat(ManagedClasses.of(unit)).contains(Customer.class.getName());
        }
    }

    @Test
    void aUnitDeclaredWhenAloneIsNotDeclaredBesideAnotherUnit() throws Exception {
        Path module = moduleWith("<persistence-unit name=\"ledger\"/>");
        URL testClasses = testClasses();
        PersistenceUnitSource source = loader -> List.of(PersistenceUnitDeclaration.builder("default", "the application")
            .defaultRoot(testClasses).whenAlone().build());

        try (URLClassLoader loader = loader(module)) {
            assertThat(PersistenceUnits.names(loader, List.of(source))).containsExactly("ledger");
        }
    }

    @Test
    void aUnitDeclaredWhenAloneIsNotDeclaredWhenItsRootHoldsNoEntity() throws Exception {
        URL empty = Files.createDirectories(directory.resolve("empty")).toUri().toURL();
        PersistenceUnitSource source = loader -> List.of(PersistenceUnitDeclaration.builder("default", "the application")
            .defaultRoot(empty).whenAlone().build());

        try (URLClassLoader loader = loader()) {
            assertThat(PersistenceUnits.names(loader, List.of(source))).isEmpty();
        }
    }

    /** A loader that sees the given roots only, so that the test class path adds no persistence.xml. */
    private static URLClassLoader loader(Path... roots) throws IOException {
        URL[] urls = new URL[roots.length];
        for (int i = 0; i < roots.length; i++) {
            urls[i] = roots[i].toUri().toURL();
        }
        return new URLClassLoader(urls, null);
    }

    /** A directory holding a META-INF/persistence.xml with these units. */
    private Path moduleWith(String units) throws IOException {
        Path module = Files.createTempDirectory(directory, "module");
        Path file = Files.createDirectories(module.resolve("META-INF")).resolve("persistence.xml");
        Files.writeString(file, "<persistence xmlns=\"https://jakarta.ee/xml/ns/persistence\" version=\"3.2\">" + units
            + "</persistence>", StandardCharsets.UTF_8);
        return module;
    }

    /** The directory the test classes were compiled to, which holds entities. */
    private static URL testClasses() throws IOException {
        String resource = Customer.class.getName().replace('.', '/') + ".class";
        String classFile = Customer.class.getClassLoader().getResource(resource).toExternalForm();
        return java.net.URI.create(classFile.substring(0, classFile.length() - resource.length())).toURL();
    }
}
