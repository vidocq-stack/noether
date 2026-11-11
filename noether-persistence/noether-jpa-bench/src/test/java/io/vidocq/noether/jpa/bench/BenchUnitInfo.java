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
 * https://joinup.ec.europa.eu/collection/eupl/eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.bench;

import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.spi.ClassTransformer;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.PersistenceUnitTransactionType;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;

/**
 * The benchmark unit described through the container contract (§9.6), for providers that predate the
 * Persistence 3.2 {@code PersistenceConfiguration} bootstrap. Same unit as the configuration path: one managed
 * class, resource-local, no shared cache, no validation, no class transformer.
 */
final class BenchUnitInfo implements PersistenceUnitInfo {
    private final String name;
    private final String provider;
    private final Class<?> entity;
    private final Properties properties = new Properties();

    BenchUnitInfo(String name, String provider, Class<?> entity, Map<String, Object> properties) {
        this.name = name;
        this.provider = provider;
        this.entity = entity;
        this.properties.putAll(properties);
    }

    @Override public String getPersistenceUnitName() { return name; }
    @Override public String getPersistenceProviderClassName() { return provider; }
    @Override public String getScopeAnnotationName() { return null; }
    @Override public List<String> getQualifierAnnotationNames() { return List.of(); }
    @Override public PersistenceUnitTransactionType getTransactionType() { return PersistenceUnitTransactionType.RESOURCE_LOCAL; }
    @Override public DataSource getJtaDataSource() { return null; }
    @Override public DataSource getNonJtaDataSource() { return null; }
    @Override public List<String> getMappingFileNames() { return List.of(); }
    @Override public List<URL> getJarFileUrls() { return List.of(); }
    @Override public URL getPersistenceUnitRootUrl() { return entity.getProtectionDomain().getCodeSource().getLocation(); }
    @Override public List<String> getManagedClassNames() { return List.of(entity.getName()); }
    @Override public boolean excludeUnlistedClasses() { return true; }
    @Override public SharedCacheMode getSharedCacheMode() { return SharedCacheMode.NONE; }
    @Override public ValidationMode getValidationMode() { return ValidationMode.NONE; }
    @Override public Properties getProperties() { return properties; }
    @Override public String getPersistenceXMLSchemaVersion() { return "3.0"; }
    @Override public ClassLoader getClassLoader() { return entity.getClassLoader(); }
    @Override public void addTransformer(ClassTransformer transformer) {
        throw new UnsupportedOperationException("The benchmark runs without class transformation");
    }
    @Override public ClassLoader getNewTempClassLoader() { return entity.getClassLoader(); }
}
