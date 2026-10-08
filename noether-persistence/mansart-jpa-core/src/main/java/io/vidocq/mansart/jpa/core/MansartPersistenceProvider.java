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
package io.vidocq.mansart.jpa.core;

import io.vidocq.mansart.jpa.core.bootstrap.BeanValidation;
import io.vidocq.mansart.jpa.core.bootstrap.Definitions;
import io.vidocq.mansart.jpa.core.bootstrap.ManagedClasses;
import io.vidocq.mansart.jpa.core.bootstrap.PersistenceUnitDefinition;
import io.vidocq.mansart.jpa.core.bootstrap.PersistenceUnits;
import io.vidocq.mansart.jpa.core.bootstrap.UnitSettings;
import io.vidocq.mansart.jpa.core.jdbc.ConnectionSources;
import io.vidocq.mansart.jpa.core.mapping.MappedUnit;
import io.vidocq.mansart.jpa.core.session.EntityManagerFactoryImpl;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.spi.LoadState;
import jakarta.persistence.spi.PersistenceProvider;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.ProviderUtil;
import java.util.Map;
import java.util.Optional;

/**
 * The Mansart {@link PersistenceProvider} (Jakarta Persistence 3.2, chapter 9). Published through the service
 * loader, so that {@code Persistence.createEntityManagerFactory} finds it on the class path and on the module path.
 */
public final class MansartPersistenceProvider implements PersistenceProvider {

    private static final String NAME = MansartPersistenceProvider.class.getName();

    /** §9.2: {@code null} when the unit does not exist or asks for another provider. */
    @Override
    public EntityManagerFactory createEntityManagerFactory(String emName, Map<?, ?> map) {
        ClassLoader loader = classLoader();
        Optional<PersistenceUnitDefinition> definition = PersistenceUnits.find(emName, loader);
        if (definition.isEmpty()) {
            return null;
        }
        UnitSettings settings = UnitSettings.of(definition.get(), Map.of(), map);
        return isOurs(settings) ? build(settings, loader) : null;
    }

    @Override
    public EntityManagerFactory createEntityManagerFactory(PersistenceConfiguration configuration) {
        ClassLoader loader = classLoader();
        UnitSettings settings = UnitSettings.of(Definitions.from(configuration, loader), Map.of(), Map.of());
        return isOurs(settings) ? build(settings, loader) : null;
    }

    /** §9.6: the container chose this provider; its data source objects come with the unit information. */
    @Override
    public EntityManagerFactory createContainerEntityManagerFactory(PersistenceUnitInfo info, Map<?, ?> map) {
        PersistenceUnitDefinition definition = Definitions.from(info);
        UnitSettings settings = UnitSettings.of(definition, Definitions.dataSourcesOf(info), map);
        ClassLoader loader = info.getClassLoader() != null ? info.getClassLoader() : classLoader();
        return build(settings, loader);
    }

    @Override
    public void generateSchema(PersistenceUnitInfo info, Map<?, ?> map) {
        throw new PersistenceException("Schema generation comes with milestone P9 of mansart-persistence/ROADMAP.md");
    }

    @Override
    public boolean generateSchema(String persistenceUnitName, Map<?, ?> map) {
        Optional<PersistenceUnitDefinition> definition = PersistenceUnits.find(persistenceUnitName, classLoader());
        if (definition.isEmpty() || !isOurs(UnitSettings.of(definition.get(), Map.of(), map))) {
            return false;
        }
        throw new PersistenceException("Schema generation comes with milestone P9 of mansart-persistence/ROADMAP.md");
    }

    /** Without the entity model (P2), nothing can be told about the load state of an object. */
    @Override
    public ProviderUtil getProviderUtil() {
        return new ProviderUtil() {
            @Override
            public LoadState isLoadedWithoutReference(Object entity, String attributeName) {
                return LoadState.UNKNOWN;
            }

            @Override
            public LoadState isLoadedWithReference(Object entity, String attributeName) {
                return LoadState.UNKNOWN;
            }

            @Override
            public LoadState isLoaded(Object entity) {
                return LoadState.UNKNOWN;
            }
        };
    }

    private static boolean isOurs(UnitSettings settings) {
        String provider = settings.providerClassName();
        return provider == null || provider.isBlank() || NAME.equals(provider.strip());
    }

    private static EntityManagerFactory build(UnitSettings settings, ClassLoader loader) {
        if (settings.transactionType() == PersistenceUnitTransactionType.JTA) {
            throw new PersistenceException("Persistence unit " + settings.unitName() + " is JTA: JTA entity managers come with "
                + "the container integration (mansart-jpa-cdi, milestone P9); use transaction-type RESOURCE_LOCAL in Java SE");
        }
        if (settings.validationMode() == ValidationMode.CALLBACK && !BeanValidation.isAvailable(loader)) {
            throw new PersistenceException("Persistence unit " + settings.unitName()
                + " asks for validation mode CALLBACK, but no Bean Validation provider is available (§3.7.1)");
        }
        MappedUnit mapping = MappedUnit.of(ManagedClasses.of(settings.definition()), loader);
        return new EntityManagerFactoryImpl(settings, ConnectionSources.of(settings, loader), mapping);
    }

    private static ClassLoader classLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : MansartPersistenceProvider.class.getClassLoader();
    }
}
