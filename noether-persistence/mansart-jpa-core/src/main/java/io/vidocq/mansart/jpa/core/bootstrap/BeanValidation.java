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
import jakarta.persistence.ValidationMode;
import java.util.ServiceLoader;

/**
 * Whether Jakarta Validation is there, without linking a single class of it: the persistence provider must work
 * with no Bean Validation on the class path (§3.7, and the TCK checks it).
 *
 * <p>The API-specific bridge is invoked only after its API and either a supplied factory or provider are found.
 */
public final class BeanValidation {

    private BeanValidation() {
    }

    /** A persistence-unit-scoped validator that does not expose optional API types to the core. */
    public interface Validator extends AutoCloseable {
        void validate(String event, Object entity);

        @Override
        void close();
    }

    private static final Validator NOOP = new Validator() {
        @Override
        public void validate(String event, Object entity) {
        }

        @Override
        public void close() {
        }
    };

    /**
     * Opens the optional provider for the unit. AUTO degrades to no validation when either the API or provider is
     * absent; CALLBACK requires both. The API-linked bridge is never loaded on the absent path.
     */
    public static Validator open(UnitSettings settings, ClassLoader loader, io.vidocq.mansart.jpa.core.mapping.MappedUnit mapping) {
        ValidationMode mode = settings.validationMode();
        if (mode == ValidationMode.NONE) {
            return NOOP;
        }
        try {
            if (!isAvailable(loader) || settings.property("jakarta.persistence.validation.factory") == null
                    && !hasProvider(loader)) {
                if (mode == ValidationMode.CALLBACK) {
                    throw unavailable(settings);
                }
                return NOOP;
            }
            return ValidationProviderBridge.open(settings, loader, mapping);
        } catch (LinkageError | java.util.ServiceConfigurationError | RuntimeException failure) {
            if (failure instanceof PersistenceException persistenceException) {
                throw persistenceException;
            }
            throw new PersistenceException("Unable to initialize Bean Validation for persistence unit "
                + settings.unitName(), failure);
        }
    }

    private static PersistenceException unavailable(UnitSettings settings) {
        return new PersistenceException("Persistence unit " + settings.unitName()
            + " asks for validation mode CALLBACK, but no Bean Validation provider is available (§3.7.1)");
    }

    public static boolean isAvailable(ClassLoader loader) {
        try {
            Class.forName("jakarta.validation.Validation", false, loader);
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static boolean hasProvider(ClassLoader loader) {
        try {
            Class providerType = Class.forName("jakarta.validation.spi.ValidationProvider", false, loader);
            return ServiceLoader.load(providerType, loader).iterator().hasNext();
        } catch (ClassNotFoundException brokenApi) {
            throw new PersistenceException("Installed Bean Validation API is missing its provider SPI", brokenApi);
        }
    }
}
