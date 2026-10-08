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
package io.vidocq.mansart.jpa.core.flush;

import io.vidocq.mansart.jpa.dialect.Dialect;
import io.vidocq.mansart.jpa.dialect.DialectFactory;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Finds the dialect of a database among the {@link DialectFactory} implementations {@link ServiceLoader} sees
 * ({@code mansart-jpa-dialect-*} modules or jars): by name when the unit names it, else from the JDBC metadata.
 */
public final class Dialects {

    /** The unit property naming the dialect, which skips the detection. */
    public static final String PROPERTY = "io.vidocq.mansart.jpa.dialect";

    private Dialects() {
    }

    /**
     * The dialect of the database behind {@code connection}.
     *
     * @param name the dialect the unit names, or {@code null} to detect it
     * @param loader the class loader of the unit, which sees the dialects on the class path
     */
    public static Dialect resolve(Connection connection, String name, ClassLoader loader) {
        List<DialectFactory> factories = factories(loader);
        try {
            DatabaseMetaData metadata = connection.getMetaData();
            String product = metadata.getDatabaseProductName();
            for (DialectFactory factory : factories) {
                if (name != null ? factory.name().equalsIgnoreCase(name.strip()) : factory.supports(product)) {
                    return factory.create(metadata.getDatabaseMajorVersion(), metadata.getDatabaseMinorVersion());
                }
            }
            throw new PersistenceException((name != null ? "No Mansart JPA dialect is named " + name
                : "No Mansart JPA dialect serves " + product) + ": add the dialect module of the database "
                + "(mansart-jpa-dialect-h2, mansart-jpa-dialect-postgresql, …); dialects found: "
                + factories.stream().map(DialectFactory::name).toList());
        } catch (SQLException e) {
            throw new PersistenceException("Unable to read the JDBC metadata of the database: " + e.getMessage(), e);
        }
    }

    private static List<DialectFactory> factories(ClassLoader loader) {
        List<DialectFactory> factories = new ArrayList<>();
        try {
            // the modules of the boot layer first, then what the unit's class loader sees (the class path)
            ServiceLoader.load(DialectFactory.class).forEach(factories::add);
            for (DialectFactory factory : ServiceLoader.load(DialectFactory.class, loader)) {
                if (factories.stream().noneMatch(f -> f.getClass() == factory.getClass())) {
                    factories.add(factory);
                }
            }
        } catch (ServiceConfigurationError e) {
            throw new PersistenceException("A Mansart JPA dialect cannot be loaded: " + e.getMessage(), e);
        }
        return factories;
    }
}
