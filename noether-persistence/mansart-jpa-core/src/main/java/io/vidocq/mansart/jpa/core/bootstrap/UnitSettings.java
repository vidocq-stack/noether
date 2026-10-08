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

import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The effective configuration of a persistence unit: its definition, overridden by the properties passed to the
 * provider (§8.2.1.9, §9.2). Properties named by the specification are read here, typed.
 */
public final class UnitSettings {

    public static final String PROVIDER = "jakarta.persistence.provider";
    public static final String TRANSACTION_TYPE = "jakarta.persistence.transactionType";
    public static final String VALIDATION_MODE = "jakarta.persistence.validation.mode";
    public static final String SHARED_CACHE_MODE = "jakarta.persistence.sharedCache.mode";
    public static final String JDBC_DRIVER = "jakarta.persistence.jdbc.driver";
    public static final String JDBC_URL = "jakarta.persistence.jdbc.url";
    public static final String JDBC_USER = "jakarta.persistence.jdbc.user";
    public static final String JDBC_PASSWORD = "jakarta.persistence.jdbc.password";
    public static final String DATA_SOURCE = "jakarta.persistence.dataSource";
    public static final String LOCK_TIMEOUT = "jakarta.persistence.lock.timeout";
    public static final String QUERY_TIMEOUT = "jakarta.persistence.query.timeout";

    private final PersistenceUnitDefinition definition;
    private final Map<String, Object> properties;

    private UnitSettings(PersistenceUnitDefinition definition, Map<String, Object> properties) {
        this.definition = definition;
        this.properties = Collections.unmodifiableMap(properties);
    }

    /**
     * @param defaults properties below the overrides but above the definition, e.g. a container's data sources
     * @param overrides the map given to the provider; {@code null} keys are ignored
     */
    public static UnitSettings of(PersistenceUnitDefinition definition, Map<String, Object> defaults, Map<?, ?> overrides) {
        Map<String, Object> merged = new LinkedHashMap<>(definition.properties());
        merged.putAll(defaults);
        if (overrides != null) {
            overrides.forEach((key, value) -> {
                if (key != null) {
                    merged.put(String.valueOf(key), value);
                }
            });
        }
        return new UnitSettings(definition, merged);
    }

    public PersistenceUnitDefinition definition() {
        return definition;
    }

    public String unitName() {
        return definition.name();
    }

    /** Every effective property, read-only. */
    public Map<String, Object> properties() {
        return properties;
    }

    public Object property(String name) {
        return properties.get(name);
    }

    public String string(String name) {
        Object value = properties.get(name);
        return value == null ? null : value.toString();
    }

    /** The provider the unit asks for, if any: the overriding property, then {@code <provider>}. */
    public String providerClassName() {
        Object provider = properties.get(PROVIDER);
        if (provider instanceof Class<?> type) {
            return type.getName();
        }
        return provider != null ? provider.toString() : definition.providerClassName();
    }

    public PersistenceUnitTransactionType transactionType() {
        return enumOf(PersistenceUnitTransactionType.class, properties.get(TRANSACTION_TYPE), definition.transactionType(), TRANSACTION_TYPE);
    }

    public ValidationMode validationMode() {
        return enumOf(ValidationMode.class, properties.get(VALIDATION_MODE), definition.validationMode(), VALIDATION_MODE);
    }

    public SharedCacheMode sharedCacheMode() {
        return enumOf(SharedCacheMode.class, properties.get(SHARED_CACHE_MODE), definition.sharedCacheMode(), SHARED_CACHE_MODE);
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, Object value, E fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        try {
            return Enum.valueOf(type, value.toString().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PersistenceException("Invalid value '" + value + "' for " + name, e);
        }
    }
}
