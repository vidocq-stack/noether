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

import io.vidocq.noether.jpa.core.NoetherPersistenceProvider;
import io.vidocq.noether.jpa.core.bootstrap.Definitions;
import io.vidocq.noether.jpa.core.bootstrap.PersistenceUnitDefinition;
import io.vidocq.noether.jpa.core.bootstrap.PersistenceUnits;
import io.vidocq.noether.jpa.core.bootstrap.UnitSettings;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.spi.PersistenceProvider;
import jakarta.persistence.spi.PersistenceProviderResolverHolder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The factory a container creates for a persistence unit of its deployment (Jakarta Persistence 3.2, §9.1).
 *
 * <p>A unit whose {@code <provider>} names another provider goes to that provider's
 * {@code createContainerEntityManagerFactory} (§9.6), with the unit as its {@code persistence.xml} declares it and the
 * data sources the container passes in {@code properties} (BUG-20261119-90). The provider is one of those the
 * {@code PersistenceProviderResolver} lists: the deployment declares it in
 * {@code META-INF/services/jakarta.persistence.spi.PersistenceProvider}, or in a {@code provides} clause on the module
 * path. Any other unit, Noether's, starts through {@link Persistence#createEntityManagerFactory(String, Map)}.
 */
public final class ContainerFactories {

    private static final String NOETHER = NoetherPersistenceProvider.class.getName();

    private ContainerFactories() {
    }

    /**
     * Creates the factory of the unit {@code unitName} the {@code META-INF/persistence.xml} files of {@code loader}
     * declare.
     *
     * @param properties what the container passes the provider: the data sources as {@code DataSource} objects under
     *        {@code jakarta.persistence.jtaDataSource} and {@code jakarta.persistence.nonJtaDataSource}, Noether's
     *        transaction bridge under {@link TransactionIntegration#PROPERTY}; another provider gets the data sources
     *        in its {@code PersistenceUnitInfo} and every property but the bridge
     * @throws PersistenceException if the unit names a provider the resolver does not list, or the provider fails
     */
    public static EntityManagerFactory create(String unitName, ClassLoader loader, Map<String, ?> properties) {
        Optional<PersistenceUnitDefinition> declared = PersistenceUnits.find(unitName, loader);
        if (declared.isEmpty()) {
            return Persistence.createEntityManagerFactory(unitName, properties);
        }
        UnitSettings settings = UnitSettings.of(declared.get(), Map.of(), properties);
        String provider = settings.providerClassName();
        if (provider == null || provider.isBlank() || NOETHER.equals(provider.strip())) {
            return Persistence.createEntityManagerFactory(unitName, properties);
        }
        Map<String, Object> passed = new LinkedHashMap<>(properties);
        passed.remove(TransactionIntegration.PROPERTY);
        var unit = new DeclaredUnit(declared.get(), settings.transactionType(), loader,
            dataSource(properties, Definitions.JTA_DATA_SOURCE), dataSource(properties, Definitions.NON_JTA_DATA_SOURCE));
        return listed(provider.strip(), unitName).createContainerEntityManagerFactory(unit, passed);
    }

    private static PersistenceProvider listed(String provider, String unitName) {
        for (PersistenceProvider candidate : PersistenceProviderResolverHolder.getPersistenceProviderResolver()
                .getPersistenceProviders()) {
            if (candidate.getClass().getName().equals(provider)) {
                return candidate;
            }
        }
        throw new PersistenceException("Persistence unit " + unitName + " names the provider " + provider
            + ", which no META-INF/services/jakarta.persistence.spi.PersistenceProvider of the deployment declares"
            + " (on the module path: no module provides it)");
    }

    private static DataSource dataSource(Map<String, ?> properties, String key) {
        return properties.get(key) instanceof DataSource source ? source : null;
    }
}
