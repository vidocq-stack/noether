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

import io.vidocq.noether.jpa.core.bootstrap.PersistenceUnitDefinition;
import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.spi.ClassTransformer;
import jakarta.persistence.spi.PersistenceUnitInfo;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import javax.sql.DataSource;

/**
 * A unit of the deployment as a container hands it to another provider (§9.6): what its {@code persistence.xml}
 * declares, with the data sources the container resolved.
 */
final class DeclaredUnit implements PersistenceUnitInfo {

    private final PersistenceUnitDefinition unit;
    private final PersistenceUnitTransactionType transactionType;
    private final ClassLoader loader;
    private final DataSource jtaDataSource;
    private final DataSource nonJtaDataSource;

    DeclaredUnit(PersistenceUnitDefinition unit, PersistenceUnitTransactionType transactionType, ClassLoader loader,
                 DataSource jtaDataSource, DataSource nonJtaDataSource) {
        this.unit = unit;
        this.transactionType = transactionType;
        this.loader = unit.classLoader() != null ? unit.classLoader() : loader;
        this.jtaDataSource = jtaDataSource;
        this.nonJtaDataSource = nonJtaDataSource;
    }

    @Override public String getPersistenceUnitName() { return unit.name(); }
    @Override public String getPersistenceProviderClassName() { return unit.providerClassName(); }
    @Override public String getScopeAnnotationName() { return unit.scopeAnnotationName(); }
    @Override public List<String> getQualifierAnnotationNames() { return unit.qualifierAnnotationNames(); }

    @Override
    @SuppressWarnings({"deprecation", "removal"})
    public jakarta.persistence.spi.PersistenceUnitTransactionType getTransactionType() {
        return transactionType == PersistenceUnitTransactionType.JTA
            ? jakarta.persistence.spi.PersistenceUnitTransactionType.JTA
            : jakarta.persistence.spi.PersistenceUnitTransactionType.RESOURCE_LOCAL;
    }

    @Override public DataSource getJtaDataSource() { return jtaDataSource; }
    @Override public DataSource getNonJtaDataSource() { return nonJtaDataSource; }
    @Override public List<String> getMappingFileNames() { return unit.mappingFileNames(); }
    @Override public List<URL> getJarFileUrls() { return unit.jarFileUrls(); }
    @Override public URL getPersistenceUnitRootUrl() { return unit.rootUrl(); }
    @Override public List<String> getManagedClassNames() { return unit.managedClassNames(); }
    @Override public boolean excludeUnlistedClasses() { return unit.excludeUnlistedClasses(); }

    @Override
    public SharedCacheMode getSharedCacheMode() {
        return unit.sharedCacheMode() == null ? SharedCacheMode.UNSPECIFIED : unit.sharedCacheMode();
    }

    @Override
    public ValidationMode getValidationMode() {
        return unit.validationMode() == null ? ValidationMode.AUTO : unit.validationMode();
    }

    @Override
    public Properties getProperties() {
        Properties properties = new Properties();
        unit.properties().forEach((key, value) -> {
            if (value != null) {
                properties.put(key, value);
            }
        });
        return properties;
    }

    @Override public String getPersistenceXMLSchemaVersion() { return unit.schemaVersion(); }
    @Override public ClassLoader getClassLoader() { return loader; }

    /**
     * The container defines no class of the deployment: its class loader is the deployment's, which loaded the entity
     * classes before the provider asks. A transformer is accepted and never called.
     */
    @Override
    public void addTransformer(ClassTransformer transformer) {
        Objects.requireNonNull(transformer, "transformer");
    }

    /**
     * A new loader over the root of the unit and its jar files, below the parent of the deployment's loader: the
     * classes of the unit it loads are other classes than the application's.
     */
    @Override
    public ClassLoader getNewTempClassLoader() {
        List<URL> urls = new ArrayList<>(unit.jarFileUrls());
        if (unit.rootUrl() != null) {
            urls.addFirst(unit.rootUrl());
        }
        return new URLClassLoader("noether-temporary-" + unit.name(), urls.toArray(URL[]::new), loader.getParent());
    }
}
