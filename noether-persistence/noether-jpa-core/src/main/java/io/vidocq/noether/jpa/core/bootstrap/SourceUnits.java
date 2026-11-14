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
package io.vidocq.noether.jpa.core.bootstrap;

import io.vidocq.noether.jpa.core.spi.PersistenceUnitDeclaration;
import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The definitions of the units a {@code PersistenceUnitSource} declares or overrides. */
final class SourceUnits {

    private SourceUnits() {
    }

    /**
     * The unit a declaration declares, with the defaults of a {@code persistence.xml} for what it leaves out: a
     * {@code RESOURCE_LOCAL} unit, an unspecified shared cache, validation {@code AUTO}.
     */
    static PersistenceUnitDefinition definition(PersistenceUnitDeclaration declaration, ClassLoader loader) {
        URL root = declaration.root().or(declaration::defaultRoot).orElse(null);
        PersistenceUnitTransactionType type = declaration.transactionType().or(declaration::defaultTransactionType)
            .orElse(PersistenceUnitTransactionType.RESOURCE_LOCAL);
        return new PersistenceUnitDefinition(declaration.name(), null, null, type,
            jta(declaration, type).orElse(null), nonJta(declaration, type).orElse(null), List.of(), List.of(),
            declaration.classes(), !declaration.classes().isEmpty(), SharedCacheMode.UNSPECIFIED, ValidationMode.AUTO,
            declaration.properties(), root, null, declaration.qualifiers(), declaration.scope().orElse(null), loader,
            declaration.packages());
    }

    /** A unit whose data sources and properties a declaration overrides; its properties win over the unit's. */
    static PersistenceUnitDefinition overridden(PersistenceUnitDefinition unit, PersistenceUnitDeclaration declaration) {
        Map<String, Object> properties = new LinkedHashMap<>(unit.properties());
        properties.putAll(declaration.properties());
        return new PersistenceUnitDefinition(unit.name(), unit.description(), unit.providerClassName(),
            unit.transactionType(), jta(declaration, unit.transactionType()).orElse(unit.jtaDataSourceName()),
            nonJta(declaration, unit.transactionType()).orElse(unit.nonJtaDataSourceName()), unit.mappingFileNames(),
            unit.jarFileUrls(), unit.managedClassNames(), unit.excludeUnlistedClasses(), unit.sharedCacheMode(),
            unit.validationMode(), properties, unit.rootUrl(), unit.schemaVersion(), unit.qualifierAnnotationNames(),
            unit.scopeAnnotationName(), unit.classLoader(), unit.managedPackages());
    }

    /** The JTA data source a declaration sets: its own, else its data source when the unit is a JTA one. */
    private static Optional<String> jta(PersistenceUnitDeclaration declaration, PersistenceUnitTransactionType type) {
        return declaration.jtaDataSource()
            .or(() -> type == PersistenceUnitTransactionType.JTA ? declaration.dataSource() : Optional.empty());
    }

    /** The non-JTA data source a declaration sets: its own, else its data source when the unit is resource-local. */
    private static Optional<String> nonJta(PersistenceUnitDeclaration declaration, PersistenceUnitTransactionType type) {
        return declaration.nonJtaDataSource()
            .or(() -> type == PersistenceUnitTransactionType.JTA ? Optional.empty() : declaration.dataSource());
    }
}
