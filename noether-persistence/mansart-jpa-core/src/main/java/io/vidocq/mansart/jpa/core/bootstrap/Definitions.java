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

import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.persistence.spi.PersistenceUnitInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistence unit definitions built from code: a {@code PersistenceConfiguration} (§9.2) or a container's {@code PersistenceUnitInfo} (§9.6). */
public final class Definitions {

    /** Where a container's data source objects are handed to the settings, below the caller's own map. */
    public static final String JTA_DATA_SOURCE = "jakarta.persistence.jtaDataSource";
    public static final String NON_JTA_DATA_SOURCE = "jakarta.persistence.nonJtaDataSource";

    private Definitions() {
    }

    public static PersistenceUnitDefinition from(PersistenceConfiguration configuration, ClassLoader loader) {
        PersistenceUnitTransactionType type = configuration.transactionType() == null
            ? PersistenceUnitTransactionType.RESOURCE_LOCAL : configuration.transactionType();
        return new PersistenceUnitDefinition(configuration.name(), null, configuration.provider(), type,
            configuration.jtaDataSource(), configuration.nonJtaDataSource(), configuration.mappingFiles(), List.of(),
            configuration.managedClasses().stream().map(Class::getName).toList(), true, configuration.sharedCacheMode(),
            configuration.validationMode(), configuration.properties(), null, null, List.of(), null, loader);
    }

    @SuppressWarnings("deprecation")
    public static PersistenceUnitDefinition from(PersistenceUnitInfo info) {
        PersistenceUnitTransactionType type = info.getTransactionType() == jakarta.persistence.spi.PersistenceUnitTransactionType.JTA
            ? PersistenceUnitTransactionType.JTA : PersistenceUnitTransactionType.RESOURCE_LOCAL;
        Map<String, Object> properties = new LinkedHashMap<>();
        if (info.getProperties() != null) {
            info.getProperties().forEach((key, value) -> properties.put(String.valueOf(key), value));
        }
        return new PersistenceUnitDefinition(info.getPersistenceUnitName(), null, info.getPersistenceProviderClassName(), type,
            null, null, info.getMappingFileNames(), info.getJarFileUrls(), info.getManagedClassNames(),
            info.excludeUnlistedClasses(), info.getSharedCacheMode(), info.getValidationMode(), properties,
            info.getPersistenceUnitRootUrl(), info.getPersistenceXMLSchemaVersion(),
            info.getQualifierAnnotationNames() == null ? List.of() : info.getQualifierAnnotationNames(),
            info.getScopeAnnotationName(), info.getClassLoader());
    }

    /** The data sources a container passes as objects, as properties the caller's map may still override. */
    public static Map<String, Object> dataSourcesOf(PersistenceUnitInfo info) {
        Map<String, Object> sources = new LinkedHashMap<>();
        if (info.getJtaDataSource() != null) {
            sources.put(JTA_DATA_SOURCE, info.getJtaDataSource());
        }
        if (info.getNonJtaDataSource() != null) {
            sources.put(NON_JTA_DATA_SOURCE, info.getNonJtaDataSource());
        }
        return sources;
    }
}
