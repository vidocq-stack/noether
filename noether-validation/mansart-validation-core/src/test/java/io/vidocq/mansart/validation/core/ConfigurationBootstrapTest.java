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

import jakarta.validation.Configuration;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.TraversableResolver;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.executable.ExecutableType;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Jakarta Validation 3.1, chapter 5.4 (Configuration) and chapter 8 (validation.xml): bootstrapping. */
class ConfigurationBootstrapTest {

    private final ClassLoader original = Thread.currentThread().getContextClassLoader();

    @AfterEach
    void restoreContextClassLoader() {
        Thread.currentThread().setContextClassLoader(original);
    }

    private static Configuration<?> configuration() {
        return Validation.byProvider(MansartValidationProvider.class).configure();
    }

    /** Makes META-INF/validation.xml resolvable through the context class loader. */
    private static void withValidationXml(String body) {
        String xml = "<validation-config xmlns=\"https://jakarta.ee/xml/ns/validation/configuration\" version=\"3.0\">"
            + body + "</validation-config>";
        Thread.currentThread().setContextClassLoader(new ClassLoader(Thread.currentThread().getContextClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return "META-INF/validation.xml".equals(name)
                    ? new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    : super.getResourceAsStream(name);
            }
        });
    }

    public static class XmlMessageInterpolator implements MessageInterpolator {
        @Override
        public String interpolate(String messageTemplate, Context context) {
            return messageTemplate;
        }

        @Override
        public String interpolate(String messageTemplate, Context context, java.util.Locale locale) {
            return messageTemplate;
        }
    }

    public static class NoDefaultConstructorInterpolator extends XmlMessageInterpolator {
        public NoDefaultConstructorInterpolator(String ignored) {
        }
    }

    @Test
    void theSpecificConfigurationIsTheMansartSubInterface() {
        assertThat(Validation.byProvider(MansartValidationProvider.class).configure())
            .isInstanceOf(MansartConfiguration.class);
    }

    @Test
    void theDefaultsAreAvailable() {
        Configuration<?> c = configuration();
        assertThat(c.getDefaultMessageInterpolator()).isNotNull();
        assertThat(c.getDefaultTraversableResolver()).isNotNull();
        assertThat(c.getDefaultConstraintValidatorFactory()).isNotNull();
        assertThat(c.getDefaultParameterNameProvider()).isNotNull();
        assertThat(c.getDefaultClockProvider()).isNotNull();
    }

    @Test
    void withoutValidationXmlTheBootstrapConfigurationHoldsTheDefaults() {
        var b = configuration().getBootstrapConfiguration();
        assertThat(b).isNotNull();
        assertThat(b.getConstraintMappingResourcePaths()).isEmpty();
        assertThat(b.isExecutableValidationEnabled()).isTrue();
        assertThat(b.getDefaultValidatedExecutableTypes())
            .isEqualTo(Set.of(ExecutableType.CONSTRUCTORS, ExecutableType.NON_GETTER_METHODS));
    }

    @Test
    void validationXmlIsReadThroughTheContextClassLoader() {
        withValidationXml("<default-provider>" + MansartValidationProvider.class.getName() + "</default-provider>"
            + "<property name=\"com.acme.Foo\">Bar</property>");
        var b = configuration().getBootstrapConfiguration();
        assertThat(b.getDefaultProviderClassName()).isEqualTo(MansartValidationProvider.class.getName());
        assertThat(b.getProperties()).isEqualTo(Map.of("com.acme.Foo", "Bar"));
    }

    @Test
    void componentsClassNamesInValidationXmlAreInstantiated() {
        withValidationXml("<message-interpolator>" + XmlMessageInterpolator.class.getName() + "</message-interpolator>");
        try (ValidatorFactory factory = configuration().buildValidatorFactory()) {
            assertThat(factory.getMessageInterpolator()).isInstanceOf(XmlMessageInterpolator.class);
        }
    }

    @Test
    void aClassWithoutNoArgConstructorInValidationXmlFailsTheBootstrap() {
        withValidationXml("<message-interpolator>" + NoDefaultConstructorInterpolator.class.getName() + "</message-interpolator>");
        assertThatThrownBy(() -> configuration().buildValidatorFactory()).isInstanceOf(ValidationException.class);
    }

    @Test
    void anUnknownClassInValidationXmlFailsTheBootstrap() {
        withValidationXml("<clock-provider>com.acme.DoesNotExist</clock-provider>");
        assertThatThrownBy(() -> configuration().buildValidatorFactory()).isInstanceOf(ValidationException.class);
    }

    @Test
    void anUnknownProviderInValidationXmlFailsTheBootstrap() {
        withValidationXml("<default-provider>com.acme.DoesNotExist</default-provider>");
        assertThatThrownBy(() -> configuration().buildValidatorFactory()).isInstanceOf(ValidationException.class);
    }

    @Test
    void anUnsupportedValidationXmlVersionFailsTheBootstrap() {
        Thread.currentThread().setContextClassLoader(new ClassLoader(original) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return "META-INF/validation.xml".equals(name)
                    ? new ByteArrayInputStream("<validation-config xmlns=\"https://jakarta.ee/xml/ns/validation/configuration\" version=\"9.9\"/>"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    : super.getResourceAsStream(name);
            }
        });
        assertThatThrownBy(() -> configuration().buildValidatorFactory()).isInstanceOf(ValidationException.class);
    }

    @Test
    void ignoreXmlConfigurationSkipsTheComponentsOfValidationXml() {
        withValidationXml("<message-interpolator>com.acme.DoesNotExist</message-interpolator>");
        try (ValidatorFactory factory = configuration().ignoreXmlConfiguration().buildValidatorFactory()) {
            assertThat(factory.getMessageInterpolator()).isNotNull();
        }
    }

    @Test
    void theProgrammaticSettingsWinOverValidationXml() {
        withValidationXml("<message-interpolator>" + XmlMessageInterpolator.class.getName() + "</message-interpolator>");
        MessageInterpolator custom = new XmlMessageInterpolator();
        try (ValidatorFactory factory = configuration().messageInterpolator(custom).buildValidatorFactory()) {
            assertThat(factory.getMessageInterpolator()).isSameAs(custom);
        }
    }

    @Test
    void theProgrammaticComponentsReachTheFactory() {
        MessageInterpolator interpolator = new XmlMessageInterpolator();
        TraversableResolver resolver = new DefaultTraversableResolver();
        ConstraintValidatorFactory validators = new DefaultConstraintValidatorFactory();
        ParameterNameProvider names = new DefaultParameterNameProvider();
        jakarta.validation.ClockProvider clock = () -> Clock.systemUTC();
        try (ValidatorFactory factory = configuration().messageInterpolator(interpolator).traversableResolver(resolver)
                .constraintValidatorFactory(validators).parameterNameProvider(names).clockProvider(clock).buildValidatorFactory()) {
            assertThat(factory.getMessageInterpolator()).isSameAs(interpolator);
            assertThat(factory.getTraversableResolver()).isSameAs(resolver);
            assertThat(factory.getConstraintValidatorFactory()).isSameAs(validators);
            assertThat(factory.getParameterNameProvider()).isSameAs(names);
            assertThat(factory.getClockProvider()).isSameAs(clock);
        }
    }

    @Test
    void severalFactoriesAreBuiltFromOneConfiguration() {
        Configuration<?> c = configuration();
        try (ValidatorFactory first = c.buildValidatorFactory(); ValidatorFactory second = c.buildValidatorFactory()) {
            assertThat(first).isNotSameAs(second);
        }
    }

    @Test
    void aMappingStreamCanBeReadAgainByLaterFactories() throws Exception {
        MansartConfigurationImpl c = (MansartConfigurationImpl) configuration();
        c.addMapping(new ByteArrayInputStream("one".getBytes()) {
            @Override
            public boolean markSupported() {
                return false; // a non-resettable stream, as in the TCK
            }
        });
        for (int i = 0; i < 2; i++) {
            Set<InputStream> streams = c.getMappingStreams();
            assertThat(streams).hasSize(1);
            assertThat(new String(streams.iterator().next().readAllBytes())).isEqualTo("one");
        }
    }

    @Test
    void propertiesAreKept() {
        MansartConfigurationImpl c = (MansartConfigurationImpl) configuration();
        c.addProperty("a", "1").addProperty("b", "2");
        assertThat(c.getProperties()).isEqualTo(Map.of("a", "1", "b", "2"));
    }

    @Test
    void theGenericConfigurationIsAlsoAvailable() {
        assertThat(Validation.byDefaultProvider().configure()).isNotNull();
    }
}
