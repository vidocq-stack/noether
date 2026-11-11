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
package io.vidocq.noether.jpa.jta;

import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.spi.ClassTransformer;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.PersistenceUnitTransactionType;
import java.net.URL;
import java.util.List;
import java.util.Properties;
import javax.sql.DataSource;

/** What a Jakarta EE container hands the provider for a JTA unit (§9.6): its own JTA data source. */
record ContainerUnitInfo(String name, DataSource jtaDataSource) implements PersistenceUnitInfo {
    @Override public String getPersistenceUnitName() { return name; }
    @Override public String getPersistenceProviderClassName() { return "io.vidocq.noether.jpa.core.NoetherPersistenceProvider"; }
    @Override public String getScopeAnnotationName() { return null; }
    @Override public List<String> getQualifierAnnotationNames() { return List.of(); }
    @Override public PersistenceUnitTransactionType getTransactionType() { return PersistenceUnitTransactionType.JTA; }
    @Override public DataSource getJtaDataSource() { return jtaDataSource; }
    @Override public DataSource getNonJtaDataSource() { return null; }
    @Override public List<String> getMappingFileNames() { return List.of(); }
    @Override public List<URL> getJarFileUrls() { return List.of(); }
    @Override public URL getPersistenceUnitRootUrl() { return Ledger.class.getProtectionDomain().getCodeSource().getLocation(); }
    @Override public List<String> getManagedClassNames() { return List.of(Ledger.class.getName()); }
    @Override public boolean excludeUnlistedClasses() { return true; }
    @Override public SharedCacheMode getSharedCacheMode() { return SharedCacheMode.NONE; }
    @Override public ValidationMode getValidationMode() { return ValidationMode.NONE; }
    @Override public Properties getProperties() {
        Properties properties = new Properties();
        properties.setProperty("jakarta.persistence.schema-generation.database.action", "drop-and-create");
        return properties;
    }
    @Override public String getPersistenceXMLSchemaVersion() { return "3.2"; }
    @Override public ClassLoader getClassLoader() { return Ledger.class.getClassLoader(); }
    @Override public void addTransformer(ClassTransformer transformer) { throw new UnsupportedOperationException(); }
    @Override public ClassLoader getNewTempClassLoader() { return getClassLoader(); }
}
