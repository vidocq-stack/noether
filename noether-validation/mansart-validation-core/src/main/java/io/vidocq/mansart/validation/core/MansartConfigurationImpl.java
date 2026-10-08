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

import jakarta.validation.BootstrapConfiguration;
import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.TraversableResolver;
import jakarta.validation.ValidationException;
import jakarta.validation.ValidationProviderResolver;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.spi.BootstrapState;
import jakarta.validation.spi.ConfigurationState;
import jakarta.validation.spi.ValidationProvider;
import jakarta.validation.valueextraction.ValueExtractor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The {@link MansartConfiguration}, which is also the {@link ConfigurationState} handed to the
 * provider. Settings are resolved when the factory is built: the programmatic value wins, then
 * {@code validation.xml} (unless ignored), then the default.
 */
final class MansartConfigurationImpl implements MansartConfiguration, ConfigurationState {

    private static final String VALIDATION_XML = "META-INF/validation.xml";

    private final BootstrapState bootstrapState;

    private final MessageInterpolator defaultMessageInterpolator = new DefaultMessageInterpolator();
    private final TraversableResolver defaultTraversableResolver = new DefaultTraversableResolver();
    private final ConstraintValidatorFactory defaultConstraintValidatorFactory = new DefaultConstraintValidatorFactory();
    private final ParameterNameProvider defaultParameterNameProvider = new DefaultParameterNameProvider();
    private final ClockProvider defaultClockProvider = new DefaultClockProvider();

    private boolean ignoreXmlConfiguration;
    private MessageInterpolator messageInterpolator;
    private TraversableResolver traversableResolver;
    private ConstraintValidatorFactory constraintValidatorFactory;
    private ParameterNameProvider parameterNameProvider;
    private ClockProvider clockProvider;
    private final Set<ValueExtractor<?>> valueExtractors = new LinkedHashSet<>();
    private final List<byte[]> mappings = new ArrayList<>();
    private final Map<String, String> properties = new HashMap<>();
    private XmlBootstrapConfiguration xml;

    MansartConfigurationImpl(BootstrapState bootstrapState) {
        this.bootstrapState = bootstrapState;
    }

    // ---- Configuration -------------------------------------------------------------------------

    @Override
    public MansartConfiguration ignoreXmlConfiguration() {
        this.ignoreXmlConfiguration = true;
        return this;
    }

    @Override
    public MansartConfiguration messageInterpolator(MessageInterpolator interpolator) {
        this.messageInterpolator = interpolator;
        return this;
    }

    @Override
    public MansartConfiguration traversableResolver(TraversableResolver resolver) {
        this.traversableResolver = resolver;
        return this;
    }

    @Override
    public MansartConfiguration constraintValidatorFactory(ConstraintValidatorFactory factory) {
        this.constraintValidatorFactory = factory;
        return this;
    }

    @Override
    public MansartConfiguration parameterNameProvider(ParameterNameProvider provider) {
        this.parameterNameProvider = provider;
        return this;
    }

    @Override
    public MansartConfiguration clockProvider(ClockProvider provider) {
        this.clockProvider = provider;
        return this;
    }

    @Override
    public MansartConfiguration addValueExtractor(ValueExtractor<?> extractor) {
        valueExtractors.add(extractor);
        return this;
    }

    /** The stream is read now, so that it need not be resettable and later factories can read it again. */
    @Override
    public MansartConfiguration addMapping(InputStream stream) {
        if (stream == null) {
            throw new IllegalArgumentException("The mapping stream must not be null");
        }
        try {
            mappings.add(stream.readAllBytes());
        } catch (IOException e) {
            throw new ValidationException("Unable to read the constraint mapping stream", e);
        }
        return this;
    }

    @Override
    public MansartConfiguration addProperty(String name, String value) {
        properties.put(name, value);
        return this;
    }

    @Override
    public MessageInterpolator getDefaultMessageInterpolator() {
        return defaultMessageInterpolator;
    }

    @Override
    public TraversableResolver getDefaultTraversableResolver() {
        return defaultTraversableResolver;
    }

    @Override
    public ConstraintValidatorFactory getDefaultConstraintValidatorFactory() {
        return defaultConstraintValidatorFactory;
    }

    @Override
    public ParameterNameProvider getDefaultParameterNameProvider() {
        return defaultParameterNameProvider;
    }

    @Override
    public ClockProvider getDefaultClockProvider() {
        return defaultClockProvider;
    }

    @Override
    public BootstrapConfiguration getBootstrapConfiguration() {
        return validationXml();
    }

    @Override
    public ValidatorFactory buildValidatorFactory() {
        String requested = ignoreXmlConfiguration ? null : validationXml().getDefaultProviderClassName();
        if (requested != null && !requested.equals(MansartValidationProvider.class.getName())) {
            return providerNamed(requested).buildValidatorFactory(this);
        }
        return new MansartValidationProvider().buildValidatorFactory(this);
    }

    // ---- ConfigurationState --------------------------------------------------------------------

    @Override
    public boolean isIgnoreXmlConfiguration() {
        return ignoreXmlConfiguration;
    }

    @Override
    public MessageInterpolator getMessageInterpolator() {
        return pick(messageInterpolator, xmlSetting().getMessageInterpolatorClassName(), MessageInterpolator.class,
            () -> defaultMessageInterpolator);
    }

    @Override
    public TraversableResolver getTraversableResolver() {
        return pick(traversableResolver, xmlSetting().getTraversableResolverClassName(), TraversableResolver.class,
            () -> defaultTraversableResolver);
    }

    @Override
    public ConstraintValidatorFactory getConstraintValidatorFactory() {
        return pick(constraintValidatorFactory, xmlSetting().getConstraintValidatorFactoryClassName(),
            ConstraintValidatorFactory.class, () -> defaultConstraintValidatorFactory);
    }

    @Override
    public ParameterNameProvider getParameterNameProvider() {
        return pick(parameterNameProvider, xmlSetting().getParameterNameProviderClassName(), ParameterNameProvider.class,
            () -> defaultParameterNameProvider);
    }

    @Override
    public ClockProvider getClockProvider() {
        return pick(clockProvider, xmlSetting().getClockProviderClassName(), ClockProvider.class,
            () -> defaultClockProvider);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Set<ValueExtractor<?>> getValueExtractors() {
        Set<ValueExtractor<?>> all = new LinkedHashSet<>();
        for (String name : xmlSetting().getValueExtractorClassNames()) {
            all.add((ValueExtractor<?>) Instantiator.create(name, ValueExtractor.class));
        }
        all.addAll(valueExtractors);
        return all;
    }

    /** Fresh streams on every call: the TCK builds several factories from one configuration. */
    @Override
    public Set<InputStream> getMappingStreams() {
        Set<InputStream> streams = new LinkedHashSet<>();
        for (String path : xmlSetting().getConstraintMappingResourcePaths()) {
            streams.add(openResource(path));
        }
        for (byte[] mapping : mappings) {
            streams.add(new ByteArrayInputStream(mapping));
        }
        return streams;
    }

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> all = new HashMap<>(xmlSetting().getProperties());
        all.putAll(properties);
        return all;
    }

    // ---- internals -----------------------------------------------------------------------------

    /** The parsed validation.xml (or the defaults), read once, lazily, so that ignoring it never fails. */
    private XmlBootstrapConfiguration validationXml() {
        if (xml == null) {
            try (InputStream in = openOptionalResource(VALIDATION_XML)) {
                xml = in == null ? ValidationXmlParser.absent() : ValidationXmlParser.parse(in);
            } catch (IOException e) {
                throw new ValidationException("Unable to read " + VALIDATION_XML, e);
            }
        }
        return xml;
    }

    /** The validation.xml settings that apply to the build: none when XML configuration is ignored. */
    private XmlBootstrapConfiguration xmlSetting() {
        return ignoreXmlConfiguration ? ValidationXmlParser.absent() : validationXml();
    }

    private static <T> T pick(T programmatic, String xmlClassName, Class<T> type, Supplier<T> fallback) {
        if (programmatic != null) {
            return programmatic;
        }
        return xmlClassName != null ? Instantiator.create(xmlClassName, type) : fallback.get();
    }

    private ValidationProvider<?> providerNamed(String className) {
        ValidationProviderResolver resolver = bootstrapState != null && bootstrapState.getValidationProviderResolver() != null
            ? bootstrapState.getValidationProviderResolver()
            : bootstrapState != null ? bootstrapState.getDefaultValidationProviderResolver() : null;
        if (resolver != null) {
            for (ValidationProvider<?> provider : resolver.getValidationProviders()) {
                if (provider.getClass().getName().equals(className)) {
                    return provider;
                }
            }
        }
        throw new ValidationException("Unable to find the Jakarta Validation provider " + className
            + " declared in " + VALIDATION_XML);
    }

    private static InputStream openOptionalResource(String path) {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        InputStream in = context != null ? context.getResourceAsStream(path) : null;
        return in != null ? in : MansartConfigurationImpl.class.getClassLoader().getResourceAsStream(path);
    }

    private static InputStream openResource(String path) {
        InputStream in = openOptionalResource(path.startsWith("/") ? path.substring(1) : path);
        if (in == null) {
            throw new ValidationException("Unable to open the constraint mapping resource " + path);
        }
        return in;
    }
}
