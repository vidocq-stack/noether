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

import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A persistence unit as declared, whatever declared it: a {@code persistence.xml} (§8.2), a
 * {@code PersistenceConfiguration} (§9.2) or a container's {@code PersistenceUnitInfo} (§9.6). Immutable; the
 * properties keep their declaration order.
 *
 * @param jtaDataSourceName the {@code jta-data-source} element, a name to look up, or {@code null}
 * @param nonJtaDataSourceName the {@code non-jta-data-source} element, a name to look up, or {@code null}
 * @param rootUrl the root of the persistence unit (the jar or directory holding {@code META-INF/persistence.xml}),
 *        or {@code null} for a unit defined in code
 * @param schemaVersion the {@code persistence.xml} version, or {@code null} for a unit defined in code
 */
public record PersistenceUnitDefinition(
        String name,
        String description,
        String providerClassName,
        PersistenceUnitTransactionType transactionType,
        String jtaDataSourceName,
        String nonJtaDataSourceName,
        List<String> mappingFileNames,
        List<URL> jarFileUrls,
        List<String> managedClassNames,
        boolean excludeUnlistedClasses,
        SharedCacheMode sharedCacheMode,
        ValidationMode validationMode,
        Map<String, Object> properties,
        URL rootUrl,
        String schemaVersion,
        List<String> qualifierAnnotationNames,
        String scopeAnnotationName,
        ClassLoader classLoader) {

    public PersistenceUnitDefinition {
        mappingFileNames = List.copyOf(mappingFileNames);
        jarFileUrls = List.copyOf(jarFileUrls);
        managedClassNames = List.copyOf(managedClassNames);
        qualifierAnnotationNames = List.copyOf(qualifierAnnotationNames);
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }
}
