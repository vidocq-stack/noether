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
package io.vidocq.mansart.validation.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.BootstrapConfiguration;
import jakarta.validation.ValidationException;
import jakarta.validation.executable.ExecutableType;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Jakarta Validation 3.1, chapter 8 (XML deployment descriptor): {@code META-INF/validation.xml}. */
class ValidationXmlParserTest {

    private static final String NS_3 = "https://jakarta.ee/xml/ns/validation/configuration";
    private static final String NS_2 = "http://xmlns.jcp.org/xml/ns/validation/configuration";
    private static final String NS_1 = "http://jboss.org/xml/ns/javax/validation/configuration";

    private static BootstrapConfiguration parse(String ns, String version, String body) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<validation-config xmlns=\"" + ns + "\" version=\"" + version + "\">" + body + "</validation-config>";
        return ValidationXmlParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static BootstrapConfiguration parse3(String body) {
        return parse(NS_3, "3.0", body);
    }

    @Test
    void anEmptyConfigurationHasTheSpecDefaults() {
        BootstrapConfiguration c = parse3("");
        assertThat(c.getDefaultProviderClassName()).isNull();
        assertThat(c.getMessageInterpolatorClassName()).isNull();
        assertThat(c.getTraversableResolverClassName()).isNull();
        assertThat(c.getConstraintValidatorFactoryClassName()).isNull();
        assertThat(c.getParameterNameProviderClassName()).isNull();
        assertThat(c.getClockProviderClassName()).isNull();
        assertThat(c.getValueExtractorClassNames()).isEmpty();
        assertThat(c.getConstraintMappingResourcePaths()).isEmpty();
        assertThat(c.getProperties()).isEmpty();
        assertThat(c.isExecutableValidationEnabled()).isTrue();
        assertThat(c.getDefaultValidatedExecutableTypes())
            .isEqualTo(EnumSet.of(ExecutableType.CONSTRUCTORS, ExecutableType.NON_GETTER_METHODS));
    }

    @Test
    void theNoConfigurationDefaultsMatchAnEmptyConfiguration() {
        BootstrapConfiguration none = ValidationXmlParser.absent();
        BootstrapConfiguration empty = parse3("");
        assertThat(none.getDefaultValidatedExecutableTypes()).isEqualTo(empty.getDefaultValidatedExecutableTypes());
        assertThat(none.isExecutableValidationEnabled()).isEqualTo(empty.isExecutableValidationEnabled());
        assertThat(none.getConstraintMappingResourcePaths()).isEmpty();
        assertThat(none.getProperties()).isEmpty();
    }

    @Test
    void allElementsAreRead() {
        BootstrapConfiguration c = parse3("""
            <default-provider>com.acme.ValidationProvider</default-provider>
            <message-interpolator>com.acme.MessageInterpolator</message-interpolator>
            <traversable-resolver>com.acme.TraversableResolver</traversable-resolver>
            <constraint-validator-factory>com.acme.ConstraintValidatorFactory</constraint-validator-factory>
            <parameter-name-provider>com.acme.ParameterNameProvider</parameter-name-provider>
            <clock-provider>com.acme.ClockProvider</clock-provider>
            <value-extractor>com.acme.ExtractorA</value-extractor>
            <value-extractor>com.acme.ExtractorB</value-extractor>
            <constraint-mapping>mapping1</constraint-mapping>
            <constraint-mapping>mapping2</constraint-mapping>
            <executable-validation enabled="true">
              <default-validated-executable-types>
                <executable-type>CONSTRUCTORS</executable-type>
                <executable-type>NON_GETTER_METHODS</executable-type>
              </default-validated-executable-types>
            </executable-validation>
            <property name="com.acme.Foo">Bar</property>
            <property name="com.acme.Baz">Qux</property>
            """);
        assertThat(c.getDefaultProviderClassName()).isEqualTo("com.acme.ValidationProvider");
        assertThat(c.getMessageInterpolatorClassName()).isEqualTo("com.acme.MessageInterpolator");
        assertThat(c.getTraversableResolverClassName()).isEqualTo("com.acme.TraversableResolver");
        assertThat(c.getConstraintValidatorFactoryClassName()).isEqualTo("com.acme.ConstraintValidatorFactory");
        assertThat(c.getParameterNameProviderClassName()).isEqualTo("com.acme.ParameterNameProvider");
        assertThat(c.getClockProviderClassName()).isEqualTo("com.acme.ClockProvider");
        assertThat(c.getValueExtractorClassNames()).containsExactlyInAnyOrder("com.acme.ExtractorA", "com.acme.ExtractorB");
        assertThat(c.getConstraintMappingResourcePaths()).containsExactlyInAnyOrder("mapping1", "mapping2");
        assertThat(c.getProperties()).isEqualTo(Map.of("com.acme.Foo", "Bar", "com.acme.Baz", "Qux"));
    }

    @Test
    void textIsTrimmed() {
        assertThat(parse3("<default-provider>\n   com.acme.P  \n</default-provider>").getDefaultProviderClassName())
            .isEqualTo("com.acme.P");
    }

    @Test
    void executableValidationCanBeDisabled() {
        assertThat(parse3("<executable-validation enabled=\"false\"/>").isExecutableValidationEnabled()).isFalse();
    }

    @Test
    void allExecutableTypesWhenALLIsContained() {
        Set<ExecutableType> all = EnumSet.of(ExecutableType.CONSTRUCTORS, ExecutableType.GETTER_METHODS, ExecutableType.NON_GETTER_METHODS);
        assertThat(types("<executable-type>ALL</executable-type>")).isEqualTo(all);
        assertThat(types("<executable-type>ALL</executable-type><executable-type>NONE</executable-type>")).isEqualTo(all);
        assertThat(types("<executable-type>NONE</executable-type><executable-type>ALL</executable-type>")).isEqualTo(all);
    }

    @Test
    void noExecutableTypeWhenOnlyNONE() {
        assertThat(types("<executable-type>NONE</executable-type>")).isEmpty();
    }

    @Test
    void noneIsStrippedWhenOthersAreContained() {
        assertThat(types("<executable-type>NONE</executable-type><executable-type>CONSTRUCTORS</executable-type>"
            + "<executable-type>GETTER_METHODS</executable-type><executable-type>NON_GETTER_METHODS</executable-type>"))
            .isEqualTo(EnumSet.of(ExecutableType.CONSTRUCTORS, ExecutableType.GETTER_METHODS, ExecutableType.NON_GETTER_METHODS));
    }

    @Test
    void anEmptyListOfExecutableTypesIsRejected() {
        assertThatThrownBy(() -> parse3("<executable-validation><default-validated-executable-types/></executable-validation>"))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void anUnknownExecutableTypeIsRejected() {
        assertThatThrownBy(() -> types("<executable-type>BOGUS</executable-type>")).isInstanceOf(ValidationException.class);
    }

    @Test
    void everyPublishedVersionIsAcceptedWithItsOwnNamespace() {
        // 1.0 has no version attribute; 1.0 and 1.1 share a namespace, 2.0 and 3.0 each have their own.
        String noVersion = "<?xml version=\"1.0\"?><validation-config xmlns=\"" + NS_1
            + "\"><default-provider>p</default-provider></validation-config>";
        assertThat(ValidationXmlParser.parse(new ByteArrayInputStream(noVersion.getBytes(StandardCharsets.UTF_8)))
            .getDefaultProviderClassName()).isEqualTo("p");
        assertThat(parse(NS_1, "1.0", "<default-provider>p</default-provider>").getDefaultProviderClassName()).isEqualTo("p");
        assertThat(parse(NS_1, "1.1", "<default-provider>p</default-provider>").getDefaultProviderClassName()).isEqualTo("p");
        assertThat(parse(NS_2, "2.0", "<default-provider>p</default-provider>").getDefaultProviderClassName()).isEqualTo("p");
        assertThat(parse(NS_3, "3.0", "<default-provider>p</default-provider>").getDefaultProviderClassName()).isEqualTo("p");
    }

    @Test
    void aVersionWithTheNamespaceOfAnotherVersionIsRejected() {
        assertThatThrownBy(() -> parse(NS_2, "1.1", "")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> parse(NS_1, "2.0", "")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> parse(NS_2, "3.0", "")).isInstanceOf(ValidationException.class);
    }

    @Test
    void anUnknownVersionIsRejected() {
        assertThatThrownBy(() -> parse(NS_3, "9.9", "")).isInstanceOf(ValidationException.class);
    }

    @Test
    void anUnknownNamespaceIsRejected() {
        assertThatThrownBy(() -> parse("urn:acme", "3.0", "")).isInstanceOf(ValidationException.class);
    }

    @Test
    void malformedXmlIsRejected() {
        assertThatThrownBy(() -> ValidationXmlParser.parse(new ByteArrayInputStream("<validation-config".getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void aDoctypeIsRejected() {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE validation-config [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
            + "<validation-config xmlns=\"" + NS_3 + "\" version=\"3.0\"><default-provider>&x;</default-provider></validation-config>";
        assertThatThrownBy(() -> ValidationXmlParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(ValidationException.class);
    }

    private static Set<ExecutableType> types(String executableTypes) {
        return parse3("<executable-validation><default-validated-executable-types>" + executableTypes
            + "</default-validated-executable-types></executable-validation>").getDefaultValidatedExecutableTypes();
    }
}
