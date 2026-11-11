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
package io.vidocq.noether.jpa.core;

import io.vidocq.noether.jpa.core.bootstrap.BeanValidation;
import io.vidocq.noether.jpa.core.bootstrap.Definitions;
import io.vidocq.noether.jpa.core.bootstrap.ManagedClasses;
import io.vidocq.noether.jpa.core.bootstrap.MappingFiles;
import io.vidocq.noether.jpa.core.bootstrap.PersistenceUnitDefinition;
import io.vidocq.noether.jpa.core.bootstrap.PersistenceUnits;
import io.vidocq.noether.jpa.core.bootstrap.UnitSettings;
import io.vidocq.noether.jpa.core.generation.IdGenerators;
import io.vidocq.noether.jpa.core.jdbc.ConnectionSources;
import io.vidocq.noether.jpa.core.mapping.MappedUnit;
import io.vidocq.noether.jpa.core.model.source.ClassFileSource;
import io.vidocq.noether.jpa.core.model.source.ClassInfos;
import io.vidocq.noether.jpa.core.model.xml.MappingFile;
import io.vidocq.noether.jpa.core.model.xml.OrmOverlay;
import io.vidocq.noether.jpa.core.session.EntityManagerFactoryImpl;
import io.vidocq.noether.jpa.core.spi.ManagedAccessProvider;
import io.vidocq.noether.jpa.core.spi.TransactionIntegration;
import io.vidocq.noether.jpa.core.spi.TransactionIntegrationProvider;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.persistence.spi.LoadState;
import jakarta.persistence.spi.PersistenceProvider;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.ProviderUtil;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The Noether {@link PersistenceProvider} (Jakarta Persistence 3.2, chapter 9). Published through the service
 * loader, so that {@code Persistence.createEntityManagerFactory} finds it on the class path and on the module path.
 */
public final class NoetherPersistenceProvider implements PersistenceProvider {

    private static final String NAME = NoetherPersistenceProvider.class.getName();

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
        ClassLoader loader = info.getClassLoader() != null ? info.getClassLoader() : classLoader();
        generateSchema(UnitSettings.of(Definitions.from(info), Definitions.dataSourcesOf(info), map), loader);
    }

    @Override
    public boolean generateSchema(String persistenceUnitName, Map<?, ?> map) {
        Optional<PersistenceUnitDefinition> definition = PersistenceUnits.find(persistenceUnitName, classLoader());
        if (definition.isEmpty() || !isOurs(UnitSettings.of(definition.get(), Map.of(), map))) {
            return false;
        }
        generateSchema(UnitSettings.of(definition.get(), Map.of(), map), classLoader());
        return true;
    }

    private static void generateSchema(UnitSettings settings, ClassLoader loader) {
        MappedUnit mapping = mapping(settings, loader);
        var connections = ConnectionSources.of(settings, loader);
        try {
            new io.vidocq.noether.jpa.core.bootstrap.SchemaGeneration(settings, connections, mapping, () -> {}).generate();
        } finally {
            connections.close();
        }
    }

    /**
     * {@code Persistence.getPersistenceUtil()} asks every provider, without a unit (§7.12): an object that is not known
     * as an entity of an open unit has an unknown load state. An entity Noether loads is loaded but for its LAZY
     * collections, which are {@code NOT_LOADED} until their first access; nothing is read to answer.
     */
    @Override
    public ProviderUtil getProviderUtil() {
        return new ProviderUtil() {
            @Override
            public LoadState isLoadedWithoutReference(Object entity, String attributeName) {
                return EntityManagerFactoryImpl.loadState(entity, attributeName);
            }

            @Override
            public LoadState isLoadedWithReference(Object entity, String attributeName) {
                return EntityManagerFactoryImpl.loadState(entity, attributeName);
            }

            @Override
            public LoadState isLoaded(Object entity) {
                return LoadState.UNKNOWN;
            }
        };
    }

    /**
     * §9.6: a container that bootstraps a JTA unit hands over its data sources, not a Noether bridge. An explicit bridge
     * wins; otherwise the first {@link TransactionIntegrationProvider} of the unit's class loader that applies here.
     */
    private static UnitSettings withTransactionIntegration(UnitSettings settings, ClassLoader loader) {
        if (settings.transactionType() != PersistenceUnitTransactionType.JTA
                || settings.property(TransactionIntegration.PROPERTY) instanceof TransactionIntegration) {
            return settings;
        }
        for (TransactionIntegrationProvider provider : ServiceLoader.load(TransactionIntegrationProvider.class, loader)) {
            Optional<TransactionIntegration> integration = provider.integration();
            if (integration.isPresent()) {
                return UnitSettings.of(settings.definition(), settings.properties(),
                    Map.of(TransactionIntegration.PROPERTY, integration.get()));
            }
        }
        return settings;
    }

    private static boolean isOurs(UnitSettings settings) {
        String provider = settings.providerClassName();
        return provider == null || provider.isBlank() || NAME.equals(provider.strip());
    }

    private static EntityManagerFactory build(UnitSettings settings, ClassLoader loader) {
        settings = withTransactionIntegration(settings, loader);
        if (settings.transactionType() == PersistenceUnitTransactionType.JTA
                && !(settings.property(TransactionIntegration.PROPERTY) instanceof TransactionIntegration)) {
            throw new PersistenceException("Persistence unit " + settings.unitName() + " is JTA and no transaction bridge "
                + "was found: on a Jakarta EE server add noether-jpa-jta (it uses the server's "
                + "java:comp/TransactionSynchronizationRegistry), on a CDI container noether-jpa-cdi, or pass a "
                + "TransactionIntegration as " + TransactionIntegration.PROPERTY);
        }
        MappedUnit mapping = mapping(settings, loader);
        BeanValidation.Validator validator = BeanValidation.open(settings, loader, mapping);
        var connections = ConnectionSources.of(settings, loader);
        var allocations = ConnectionSources.allocations(settings, connections);
        String unitName = settings.unitName();
        var factory = new EntityManagerFactoryImpl(settings, connections, allocations.orElse(() -> {
            throw new SQLException("Persistence unit " + unitName + " has no non-JTA data source to allocate through");
        }), mapping, validator);
        try {
            IdGenerators.checkAllocations(unitName, mapping.model().entities(), allocations);
            IdGenerators.checkConnections(unitName, mapping.model().entities(), connections);
            ((io.vidocq.noether.jpa.core.bootstrap.SchemaGeneration) factory.getSchemaManager()).generate();
            return factory;
        } catch (RuntimeException | Error failure) {
            try { factory.close(); } catch (RuntimeException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    private static ClassLoader classLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : NoetherPersistenceProvider.class.getClassLoader();
    }

    private static MappedUnit mapping(UnitSettings settings, ClassLoader loader) {
        List<MappingFile> files = MappingFiles.of(settings.definition(), loader);
        ClassInfos source = files.isEmpty() ? new ClassFileSource(loader) : OrmOverlay.of(files, loader);
        return MappedUnit.of(ManagedClasses.of(settings.definition()), loader, source,
            ServiceLoader.load(ManagedAccessProvider.class, loader));
    }
}
