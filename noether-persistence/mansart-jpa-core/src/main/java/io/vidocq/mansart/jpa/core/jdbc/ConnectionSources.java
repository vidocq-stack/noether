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
import io.vidocq.mansart.pool.PoolConfig;
import io.vidocq.mansart.pool.core.MansartDataSource;
import jakarta.persistence.PersistenceException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Properties;
import javax.sql.DataSource;

/**
 * Builds the {@link ConnectionSource} of a resource-local unit, in this order (§8.2.1.5, §8.2.1.9): a
 * {@link DataSource} object given as {@code jakarta.persistence.nonJtaDataSource} or {@code jakarta.persistence.dataSource},
 * then the {@code jakarta.persistence.jdbc.*} properties. A data source <em>name</em> needs JNDI, which Java SE
 * does not have: it is refused with a message saying what to do instead.
 */
public final class ConnectionSources {

    /** {@code false} turns off the pool of a {@code jakarta.persistence.jdbc.*} unit (decision D7): one connection per transaction. */
    public static final String POOL = "io.vidocq.mansart.jpa.pool";
    /** The maximum number of connections of the pool (default 10). */
    public static final String POOL_MAX_SIZE = "io.vidocq.mansart.jpa.pool.max-size";
    /** The connections the pool keeps open when idle (default 0). */
    public static final String POOL_MIN_IDLE = "io.vidocq.mansart.jpa.pool.min-idle";
    /** How long a transaction waits for a connection, as an ISO-8601 duration (default {@code PT5S}). */
    public static final String POOL_ACQUIRE_TIMEOUT = "io.vidocq.mansart.jpa.pool.acquire-timeout";

    private static final System.Logger LOGGER = System.getLogger(ConnectionSources.class.getName());

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
            String user = settings.string(UnitSettings.JDBC_USER);
            String password = settings.string(UnitSettings.JDBC_PASSWORD);
            String driver = settings.string(UnitSettings.JDBC_DRIVER);
            if (!"false".equalsIgnoreCase(String.valueOf(settings.string(POOL)).strip()) && pooledDriverFound(url, driver, loader)) {
                return pooled(settings, url, user, password);
            }
            return driverManager(url, user, password, driver, loader);
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

    /**
     * Decision D7: the connections of a {@code jakarta.persistence.jdbc.*} unit come from a {@code mansart-pool} pool,
     * closed with the factory. The pool opens them through {@code DriverManager}.
     */
    private static ConnectionSource pooled(UnitSettings settings, String url, String user, String password) {
        PoolConfig config;
        try {
            PoolConfig.Builder builder = PoolConfig.builder().jdbcUrl(url).username(user).password(password);
            integer(settings, POOL_MAX_SIZE).ifPresent(builder::maxSize);
            integer(settings, POOL_MIN_IDLE).ifPresent(builder::minIdle);
            String timeout = settings.string(POOL_ACQUIRE_TIMEOUT);
            if (timeout != null && !timeout.isBlank()) {
                builder.acquireTimeout(duration(timeout.strip()));
            }
            config = builder.build();
        } catch (IllegalArgumentException e) {
            throw new PersistenceException("Invalid connection pool settings for persistence unit " + settings.unitName() + " ("
                + POOL_MAX_SIZE + ", " + POOL_MIN_IDLE + ", " + POOL_ACQUIRE_TIMEOUT + "): " + e.getMessage(), e);
        }
        MansartDataSource pool = MansartDataSource.of(config);
        return new ConnectionSource() {
            @Override
            public Connection acquire() throws SQLException {
                return pool.getConnection();
            }

            @Override
            public void close() {
                pool.close();
            }
        };
    }

    /**
     * Whether {@code DriverManager}, through which the pool opens connections, serves {@code url}. A driver named by
     * {@code jakarta.persistence.jdbc.driver} that only the application's class loader sees is not: such a unit keeps
     * one connection per transaction, opened by the named driver directly.
     */
    private static boolean pooledDriverFound(String url, String driverClassName, ClassLoader loader) {
        try {
            DriverManager.getDriver(url);
            return true;
        } catch (SQLException notFound) {
            if (driverClassName != null && !driverClassName.isBlank()) {
                instantiate(driverClassName.strip(), loader); // fails now if the driver cannot even be loaded
                LOGGER.log(System.Logger.Level.WARNING, "The JDBC driver {0} is not visible to DriverManager: the "
                    + "connections of the unit are not pooled (pass a pooled javax.sql.DataSource instead)", driverClassName);
            }
            return false;
        }
    }

    private static Optional<Integer> integer(UnitSettings settings, String property) {
        String value = settings.string(property);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(value.strip()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(property + " must be an integer, not '" + value + "'", e);
        }
    }

    private static Duration duration(String value) {
        try {
            return Duration.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(POOL_ACQUIRE_TIMEOUT + " must be an ISO-8601 duration such as PT5S, not '"
                + value + "'", e);
        }
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
