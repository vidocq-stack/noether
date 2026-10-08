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
package io.vidocq.mansart.jpa.core.jdbc;

import io.vidocq.mansart.jpa.core.bootstrap.Definitions;
import io.vidocq.mansart.jpa.core.bootstrap.UnitSettings;
import jakarta.persistence.PersistenceException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import javax.sql.DataSource;

/**
 * Builds the {@link ConnectionSource} of a resource-local unit, in this order (§8.2.1.5, §8.2.1.9): a
 * {@link DataSource} object given as {@code jakarta.persistence.nonJtaDataSource} or {@code jakarta.persistence.dataSource},
 * then the {@code jakarta.persistence.jdbc.*} properties. A data source <em>name</em> needs JNDI, which Java SE
 * does not have: it is refused with a message saying what to do instead.
 */
public final class ConnectionSources {

    private ConnectionSources() {
    }

    public static ConnectionSource of(UnitSettings settings, ClassLoader loader) {
        Object dataSource = settings.property(Definitions.NON_JTA_DATA_SOURCE);
        if (dataSource == null) {
            dataSource = settings.property(UnitSettings.DATA_SOURCE);
        }
        if (dataSource instanceof DataSource source) {
            return source::getConnection;
        }
        String url = settings.string(UnitSettings.JDBC_URL);
        if (url != null && !url.isBlank()) {
            return driverManager(url, settings.string(UnitSettings.JDBC_USER), settings.string(UnitSettings.JDBC_PASSWORD),
                settings.string(UnitSettings.JDBC_DRIVER), loader);
        }
        String name = dataSource != null ? dataSource.toString() : settings.definition().nonJtaDataSourceName();
        return () -> {
            throw new SQLException(name == null
                ? "Persistence unit " + settings.unitName() + " has no connection: set jakarta.persistence.jdbc.url, "
                    + "or pass a javax.sql.DataSource as jakarta.persistence.nonJtaDataSource"
                : "Persistence unit " + settings.unitName() + " names the data source '" + name + "', which needs JNDI; "
                    + "in Java SE pass the javax.sql.DataSource object as jakarta.persistence.nonJtaDataSource");
        };
    }

    private static ConnectionSource driverManager(String url, String user, String password, String driverClassName,
            ClassLoader loader) {
        Properties credentials = new Properties();
        if (user != null) {
            credentials.setProperty("user", user);
        }
        if (password != null) {
            credentials.setProperty("password", password);
        }
        if (driverClassName == null || driverClassName.isBlank()) {
            return () -> DriverManager.getConnection(url, credentials);
        }
        Driver driver = instantiate(driverClassName.strip(), loader);
        return () -> {
            Connection connection = driver.connect(url, credentials);
            if (connection == null) {
                throw new SQLException("The driver " + driverClassName + " does not accept the URL " + url);
            }
            return connection;
        };
    }

    /**
     * The driver named by {@code jakarta.persistence.jdbc.driver}, used directly: {@code DriverManager} would refuse
     * a driver that the caller's class loader cannot see, which is the case of a driver in an application's own loader.
     */
    private static Driver instantiate(String className, ClassLoader loader) {
        try {
            Class<?> type = Class.forName(className, true, loader);
            return (Driver) MethodHandles.publicLookup().findConstructor(type, MethodType.methodType(void.class)).invoke();
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            throw new PersistenceException("Unable to load the JDBC driver " + className, e);
        }
    }
}
