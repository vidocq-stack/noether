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

import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Jakarta Persistence 3.2, chapter 8.2: the persistence.xml file. */
class PersistenceXmlParserTest {

    private static final String JAKARTA = "https://jakarta.ee/xml/ns/persistence";
    private static final String JCP = "http://xmlns.jcp.org/xml/ns/persistence";
    private static final String SUN = "http://java.sun.com/xml/ns/persistence";

    private static URL root() throws Exception {
        return URI.create("file:/app/classes/").toURL();
    }

    private static List<PersistenceUnitDefinition> parse(String ns, String version, String units) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><persistence xmlns=\"" + ns + "\" version=\"" + version + "\">"
            + units + "</persistence>";
        return PersistenceXmlParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), root(),
            PersistenceXmlParserTest.class.getClassLoader());
    }

    private static PersistenceUnitDefinition single(String units) throws Exception {
        List<PersistenceUnitDefinition> all = parse(JAKARTA, "3.2", units);
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    @Test
    void aMinimalUnitHasTheJavaSeDefaults() throws Exception { // §8.2.1
        PersistenceUnitDefinition unit = single("<persistence-unit name=\"orders\"/>");
        assertThat(unit.name()).isEqualTo("orders");
        assertThat(unit.transactionType()).isEqualTo(PersistenceUnitTransactionType.RESOURCE_LOCAL); // §8.2.1.2, Java SE
        assertThat(unit.providerClassName()).isNull();
        assertThat(unit.managedClassNames()).isEmpty();
        assertThat(unit.mappingFileNames()).isEmpty();
        assertThat(unit.jarFileUrls()).isEmpty();
        assertThat(unit.excludeUnlistedClasses()).isFalse();
        assertThat(unit.sharedCacheMode()).isEqualTo(SharedCacheMode.UNSPECIFIED); // §8.2.1.7
        assertThat(unit.validationMode()).isEqualTo(ValidationMode.AUTO); // §8.2.1.8
        assertThat(unit.properties()).isEmpty();
        assertThat(unit.rootUrl()).isEqualTo(root());
        assertThat(unit.schemaVersion()).isEqualTo("3.2");
        assertThat(unit.qualifierAnnotationNames()).isEmpty();
        assertThat(unit.scopeAnnotationName()).isNull();
    }

    @Test
    void everyElementIsRead() throws Exception {
        PersistenceUnitDefinition unit = single("""
            <persistence-unit name="orders" transaction-type="JTA">
              <description>The orders</description>
              <provider>io.vidocq.mansart.jpa.core.MansartPersistenceProvider</provider>
              <qualifier>com.acme.Orders</qualifier>
              <qualifier>com.acme.Main</qualifier>
              <scope>jakarta.enterprise.context.ApplicationScoped</scope>
              <jta-data-source>java:app/jdbc/orders</jta-data-source>
              <non-jta-data-source>java:app/jdbc/orders-nonjta</non-jta-data-source>
              <mapping-file>META-INF/orders-orm.xml</mapping-file>
              <jar-file>lib/entities.jar</jar-file>
              <class>com.acme.Order</class>
              <class>com.acme.Line</class>
              <exclude-unlisted-classes>true</exclude-unlisted-classes>
              <shared-cache-mode>ENABLE_SELECTIVE</shared-cache-mode>
              <validation-mode>CALLBACK</validation-mode>
              <properties>
                <property name="jakarta.persistence.jdbc.url" value="jdbc:h2:mem:orders"/>
                <property name="b" value="2"/>
              </properties>
            </persistence-unit>
            """);
        assertThat(unit.transactionType()).isEqualTo(PersistenceUnitTransactionType.JTA);
        assertThat(unit.description()).isEqualTo("The orders");
        assertThat(unit.providerClassName()).isEqualTo("io.vidocq.mansart.jpa.core.MansartPersistenceProvider");
        assertThat(unit.qualifierAnnotationNames()).containsExactly("com.acme.Orders", "com.acme.Main");
        assertThat(unit.scopeAnnotationName()).isEqualTo("jakarta.enterprise.context.ApplicationScoped");
        assertThat(unit.jtaDataSourceName()).isEqualTo("java:app/jdbc/orders");
        assertThat(unit.nonJtaDataSourceName()).isEqualTo("java:app/jdbc/orders-nonjta");
        assertThat(unit.mappingFileNames()).containsExactly("META-INF/orders-orm.xml");
        assertThat(unit.jarFileUrls()).containsExactly(URI.create("file:/app/classes/lib/entities.jar").toURL());
        assertThat(unit.managedClassNames()).containsExactly("com.acme.Order", "com.acme.Line");
        assertThat(unit.excludeUnlistedClasses()).isTrue();
        assertThat(unit.sharedCacheMode()).isEqualTo(SharedCacheMode.ENABLE_SELECTIVE);
        assertThat(unit.validationMode()).isEqualTo(ValidationMode.CALLBACK);
        assertThat(unit.properties()).containsExactly(
            java.util.Map.entry("jakarta.persistence.jdbc.url", "jdbc:h2:mem:orders"), java.util.Map.entry("b", "2"));
    }

    @Test
    void anEmptyExcludeUnlistedClassesElementMeansTrue() throws Exception { // §8.2.1.6.1
        assertThat(single("<persistence-unit name=\"u\"><exclude-unlisted-classes/></persistence-unit>").excludeUnlistedClasses()).isTrue();
        assertThat(single("<persistence-unit name=\"u\"><exclude-unlisted-classes>false</exclude-unlisted-classes></persistence-unit>")
            .excludeUnlistedClasses()).isFalse();
    }

    @Test
    void textIsTrimmed() throws Exception {
        assertThat(single("<persistence-unit name=\"u\"><class>\n  com.acme.A \n</class></persistence-unit>").managedClassNames())
            .containsExactly("com.acme.A");
    }

    @Test
    void aFileMayDeclareSeveralUnits() throws Exception {
        List<PersistenceUnitDefinition> units = parse(JAKARTA, "3.2",
            "<persistence-unit name=\"a\"/><persistence-unit name=\"b\" transaction-type=\"RESOURCE_LOCAL\"/>");
        assertThat(units.stream().map(PersistenceUnitDefinition::name)).containsExactly("a", "b");
    }

    @Test
    void everyPublishedVersionIsAcceptedWithItsNamespace() throws Exception { // the 1.0 to 3.2 schemas
        for (String[] pair : new String[][] {{SUN, "1.0"}, {SUN, "2.0"}, {JCP, "2.1"}, {JCP, "2.2"}, {JAKARTA, "3.0"},
                {JAKARTA, "3.1"}, {JAKARTA, "3.2"}}) {
            assertThat(parse(pair[0], pair[1], "<persistence-unit name=\"u\"/>")).hasSize(1);
            assertThat(parse(pair[0], pair[1], "<persistence-unit name=\"u\"/>").get(0).schemaVersion()).isEqualTo(pair[1]);
        }
    }

    @Test
    void anUnknownVersionOrAMismatchedNamespaceIsRejected() {
        assertThatThrownBy(() -> parse(JAKARTA, "9.9", "")).isInstanceOf(PersistenceException.class);
        assertThatThrownBy(() -> parse(JCP, "3.2", "")).isInstanceOf(PersistenceException.class);
        assertThatThrownBy(() -> parse("urn:acme", "3.2", "")).isInstanceOf(PersistenceException.class);
    }

    @Test
    void aUnitWithoutNameIsRejected() {
        assertThatThrownBy(() -> single("<persistence-unit/>")).isInstanceOf(PersistenceException.class)
            .hasMessageContaining("name");
    }

    @Test
    void anUnknownEnumValueIsRejected() {
        assertThatThrownBy(() -> single("<persistence-unit name=\"u\" transaction-type=\"XA\"/>")).isInstanceOf(PersistenceException.class);
        assertThatThrownBy(() -> single("<persistence-unit name=\"u\"><shared-cache-mode>SOME</shared-cache-mode></persistence-unit>"))
            .isInstanceOf(PersistenceException.class);
    }

    @Test
    void malformedXmlAndDoctypesAreRejected() {
        assertThatThrownBy(() -> PersistenceXmlParser.parse(new ByteArrayInputStream("<persistence".getBytes(StandardCharsets.UTF_8)),
            root(), getClass().getClassLoader())).isInstanceOf(PersistenceException.class);
        String withEntity = "<?xml version=\"1.0\"?><!DOCTYPE persistence [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
            + "<persistence xmlns=\"" + JAKARTA + "\" version=\"3.2\"><persistence-unit name=\"&x;\"/></persistence>";
        assertThatThrownBy(() -> PersistenceXmlParser.parse(new ByteArrayInputStream(withEntity.getBytes(StandardCharsets.UTF_8)),
            root(), getClass().getClassLoader())).isInstanceOf(PersistenceException.class);
    }

    @Test
    void theRootOfAJarIsTheJarItself() throws Exception { // §8.2: the persistence unit root
        assertThat(PersistenceXmlParser.rootOf(URI.create("jar:file:/lib/app.jar!/META-INF/persistence.xml").toURL()))
            .isEqualTo(URI.create("file:/lib/app.jar").toURL());
        assertThat(PersistenceXmlParser.rootOf(URI.create("file:/app/classes/META-INF/persistence.xml").toURL()))
            .isEqualTo(URI.create("file:/app/classes/").toURL());
    }
}
