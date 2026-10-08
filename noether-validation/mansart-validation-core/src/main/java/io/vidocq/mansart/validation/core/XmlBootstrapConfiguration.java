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
import jakarta.validation.executable.ExecutableType;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Immutable {@link BootstrapConfiguration}: the content of a {@code validation.xml}, or its defaults. */
record XmlBootstrapConfiguration(
        String defaultProviderClassName,
        String constraintValidatorFactoryClassName,
        String messageInterpolatorClassName,
        String traversableResolverClassName,
        String parameterNameProviderClassName,
        String clockProviderClassName,
        Set<String> valueExtractorClassNames,
        Set<String> constraintMappingResourcePaths,
        boolean executableValidationEnabled,
        Set<ExecutableType> defaultValidatedExecutableTypes,
        Map<String, String> properties) implements BootstrapConfiguration {

    /** Spec: when absent, constructors and non-getter methods are validated. */
    static final Set<ExecutableType> DEFAULT_EXECUTABLE_TYPES =
        Set.copyOf(EnumSet.of(ExecutableType.CONSTRUCTORS, ExecutableType.NON_GETTER_METHODS));

    XmlBootstrapConfiguration {
        valueExtractorClassNames = Set.copyOf(valueExtractorClassNames);
        constraintMappingResourcePaths = Set.copyOf(constraintMappingResourcePaths);
        defaultValidatedExecutableTypes = Set.copyOf(defaultValidatedExecutableTypes);
        properties = Map.copyOf(properties);
    }

    @Override
    public String getDefaultProviderClassName() {
        return defaultProviderClassName;
    }

    @Override
    public String getConstraintValidatorFactoryClassName() {
        return constraintValidatorFactoryClassName;
    }

    @Override
    public String getMessageInterpolatorClassName() {
        return messageInterpolatorClassName;
    }

    @Override
    public String getTraversableResolverClassName() {
        return traversableResolverClassName;
    }

    @Override
    public String getParameterNameProviderClassName() {
        return parameterNameProviderClassName;
    }

    @Override
    public String getClockProviderClassName() {
        return clockProviderClassName;
    }

    @Override
    public Set<String> getValueExtractorClassNames() {
        return valueExtractorClassNames;
    }

    @Override
    public Set<String> getConstraintMappingResourcePaths() {
        return constraintMappingResourcePaths;
    }

    @Override
    public boolean isExecutableValidationEnabled() {
        return executableValidationEnabled;
    }

    @Override
    public Set<ExecutableType> getDefaultValidatedExecutableTypes() {
        // The spec returns a mutable-free view; callers compare with EnumSet.of(...) through Set equality.
        return defaultValidatedExecutableTypes;
    }

    @Override
    public Map<String, String> getProperties() {
        return properties;
    }
}
