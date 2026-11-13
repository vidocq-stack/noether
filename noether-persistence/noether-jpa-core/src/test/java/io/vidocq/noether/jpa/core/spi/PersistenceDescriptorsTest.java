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
import jakarta.persistence.PersistenceUnitTransactionType;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
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

    /**
     * §8.2.1.2, §8.2.1.5: a container hands a JTA unit its JTA data source and its transaction bridge before the
     * factory exists, so it asks the descriptor for the effective transaction type and the {@code jta-data-source}.
     */
    @Test
    void theTransactionsOfAUnitAreItsEffectiveTypeAndItsDataSources() throws IOException {
        String descriptor = "<persistence xmlns=\"https://jakarta.ee/xml/ns/persistence\" version=\"3.2\">"
            + "<persistence-unit name=\"named\" transaction-type=\"JTA\"><jta-data-source>ledger</jta-data-source>"
            + "<non-jta-data-source>allocations</non-jta-data-source></persistence-unit>"
            + "<persistence-unit name=\"unnamed\" transaction-type=\"JTA\"/>"
            + "<persistence-unit name=\"local\"/>"
            + "<persistence-unit name=\"overridden\" transaction-type=\"RESOURCE_LOCAL\"><properties>"
            + "<property name=\"jakarta.persistence.transactionType\" value=\"JTA\"/></properties></persistence-unit>"
            + "</persistence>";
        try (var loader = new URLClassLoader(new URL[] {root("transactions", descriptor)}, null)) {
            assertThat(PersistenceDescriptors.transactions("named", loader))
                .contains(new PersistenceDescriptors.UnitTransactions(PersistenceUnitTransactionType.JTA, "ledger", "allocations"));
            assertThat(PersistenceDescriptors.transactions("unnamed", loader))
                .contains(new PersistenceDescriptors.UnitTransactions(PersistenceUnitTransactionType.JTA, null, null));
            assertThat(PersistenceDescriptors.transactions("local", loader)).hasValueSatisfying(local -> {
                assertThat(local.type()).isEqualTo(PersistenceUnitTransactionType.RESOURCE_LOCAL);
                assertThat(local.jta()).isFalse();
            });
            assertThat(PersistenceDescriptors.transactions("overridden", loader))
                .hasValueSatisfying(overridden -> assertThat(overridden.jta()).isTrue());
            assertThat(PersistenceDescriptors.transactions("missing", loader)).isEmpty();
        }
    }

    /**
     * §8.2.1.5, §8.2.1.6 and Jakarta EE 11 Platform §13.12: a container makes every unit of the deployment injectable
     * with {@code @Inject}, under the qualifiers and the scope the descriptor names, so it asks for all of them.
     */
    @Test
    void everyUnitIsInjectableUnderTheQualifiersAndTheScopeItsDescriptorNames() throws IOException {
        String descriptor = "<persistence xmlns=\"https://jakarta.ee/xml/ns/persistence\" version=\"3.2\">"
            + "<persistence-unit name=\"ledger\" transaction-type=\"JTA\">"
            + "<qualifier>com.acme.Ledger</qualifier><qualifier>com.acme.Audited</qualifier>"
            + "<scope>jakarta.enterprise.context.RequestScoped</scope></persistence-unit>"
            + "<persistence-unit name=\"plain\" transaction-type=\"JTA\"/>"
            + "<persistence-unit name=\"local\" transaction-type=\"RESOURCE_LOCAL\"><qualifier>com.acme.Local</qualifier>"
            + "</persistence-unit>"
            + "</persistence>";
        try (var loader = new URLClassLoader(new URL[] {root("injections", descriptor), root("more", units("more"))}, null)) {
            assertThat(PersistenceDescriptors.injections(loader)).containsExactly(
                new PersistenceDescriptors.UnitInjection("ledger", List.of("com.acme.Ledger", "com.acme.Audited"),
                    "jakarta.enterprise.context.RequestScoped", true),
                new PersistenceDescriptors.UnitInjection("plain", List.of(), null, true),
                new PersistenceDescriptors.UnitInjection("local", List.of("com.acme.Local"), null, false),
                new PersistenceDescriptors.UnitInjection("more", List.of(), null, false));
        }
    }

    @Test
    void aDescriptorSeenThroughTwoClassLoadersGivesItsUnitsOnce() throws IOException {
        URL root = root("twice", units("twice"));
        try (var parent = new URLClassLoader(new URL[] {root}, null);
             var child = new URLClassLoader(new URL[] {root}, parent)) {
            assertThat(PersistenceDescriptors.injections(child)).extracting(PersistenceDescriptors.UnitInjection::name)
                .containsExactly("twice");
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
