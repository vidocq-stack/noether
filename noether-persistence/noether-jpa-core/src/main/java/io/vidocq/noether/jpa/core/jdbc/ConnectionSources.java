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
package io.vidocq.noether.jpa.core.jdbc;

import io.vidocq.noether.jpa.core.bootstrap.Definitions;
import io.vidocq.noether.jpa.core.bootstrap.UnitSettings;
import io.vidocq.noether.jpa.core.spi.ConnectionPool;
import io.vidocq.noether.jpa.core.spi.ConnectionPoolFactory;
import io.vidocq.noether.jpa.core.spi.ConnectionPoolSettings;
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
import java.util.OptionalInt;
import java.util.Properties;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Builds the {@link ConnectionSource} of a resource-local unit, in this order (§8.2.1.5, §8.2.1.9): a
 * {@link DataSource} object given as {@code jakarta.persistence.nonJtaDataSource} or {@code jakarta.persistence.dataSource},
 * then the {@code jakarta.persistence.jdbc.*} properties. A data source <em>name</em> needs JNDI, which Java SE
 * does not have: it is refused with a message saying what to do instead.
 *
 * <p>Decision D7: the connections of a {@code jakarta.persistence.jdbc.*} unit come from a pool when the application
 * has a {@link ConnectionPoolFactory}, found with {@link java.util.ServiceLoader} ({@code noether-jpa-pool-mansart}
 * provides one); the core itself has no pool. Without one, each transaction opens its own connection, and one log
 * line says how to get a pool.
 *
 * <p>A unit with a {@code TABLE} identifier generator needs at least two connections at once: the generator allocates
 * its identifiers on a connection of its own while the persisting transaction keeps its connection (BUG-20261018-04).
 * A Noether pool sized below two is refused when the factory is created. Noether does not know the size of a
 * {@code DataSource} that the application or the container supplies: it must allow two connections as well.
 */
public final class ConnectionSources {

    /**
     * {@code false} turns off the pool of a {@code jakarta.persistence.jdbc.*} unit (decision D7), even when a
     * {@link ConnectionPoolFactory} is found: one connection per transaction.
     */
    public static final String POOL = "io.vidocq.noether.jpa.pool";
    /** The maximum number of connections of the pool (default 10); at least 2 for a unit with a TABLE generator. */
    public static final String POOL_MAX_SIZE = "io.vidocq.noether.jpa.pool.max-size";
    /** The connections the pool keeps open when idle (default 0). */
    public static final String POOL_MIN_IDLE = "io.vidocq.noether.jpa.pool.min-idle";
    /** How long a transaction waits for a connection, as an ISO-8601 duration (default {@code PT5S}). */
    public static final String POOL_ACQUIRE_TIMEOUT = "io.vidocq.noether.jpa.pool.acquire-timeout";

    private static final System.Logger LOGGER = System.getLogger(ConnectionSources.class.getName());

    private ConnectionSources() {
    }

    public static ConnectionSource of(UnitSettings settings, ClassLoader loader) {
        return of(settings, loader, () -> ConnectionPools.find(loader));
    }

    /** {@link #of(UnitSettings, ClassLoader)}, asking {@code pools} for the pool factory only when the unit could use one. */
    static ConnectionSource of(UnitSettings settings, ClassLoader loader, Supplier<Optional<ConnectionPoolFactory>> pools) {
        Object dataSource = settings.transactionType() == jakarta.persistence.PersistenceUnitTransactionType.JTA
            ? settings.property(Definitions.JTA_DATA_SOURCE) : settings.property(Definitions.NON_JTA_DATA_SOURCE);
        if (dataSource == null) {
            dataSource = settings.property(UnitSettings.DATA_SOURCE);
        }
        if (dataSource instanceof DataSource source) {
            return source::getConnection;
        }
        String url = settings.string(UnitSettings.JDBC_URL);
        if (url != null && !url.isBlank()) {
            return jdbc(settings, url, loader, pools);
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
     * Where the table generators of a unit allocate their identifiers, on a connection of their own that they commit
     * (BUG-20261018-04): the unit's {@code connections} when it is resource-local. A JTA unit allocates through its
     * {@code non-jta-data-source}, the {@link DataSource} given as {@code jakarta.persistence.nonJtaDataSource}
     * (§8.2.1.5): the container enlists every connection of the JTA data source in the transaction and refuses its
     * commit (BUG-20261111-04). Empty for a JTA unit without one.
     */
    public static Optional<ConnectionSource> allocations(UnitSettings settings, ConnectionSource connections) {
        if (settings.transactionType() != jakarta.persistence.PersistenceUnitTransactionType.JTA) {
            return Optional.of(connections);
        }
        return settings.property(Definitions.NON_JTA_DATA_SOURCE) instanceof DataSource source
            ? Optional.of(source::getConnection) : Optional.empty();
    }

    /**
     * Decision D7: the connections of a {@code jakarta.persistence.jdbc.*} unit come from the pool of the
     * {@link ConnectionPoolFactory} that {@code pools} finds, unless the unit turns pooling off or {@code DriverManager}
     * does not serve its URL; otherwise each transaction opens its own connection.
     */
    private static ConnectionSource jdbc(UnitSettings settings, String url, ClassLoader loader,
            Supplier<Optional<ConnectionPoolFactory>> pools) {
        String user = settings.string(UnitSettings.JDBC_USER);
        String password = settings.string(UnitSettings.JDBC_PASSWORD);
        String driver = settings.string(UnitSettings.JDBC_DRIVER);
        if (!"false".equalsIgnoreCase(String.valueOf(settings.string(POOL)).strip())) {
            Optional<ConnectionPoolFactory> factory = pools.get();
            if (factory.isEmpty()) {
                LOGGER.log(System.Logger.Level.INFO, "Persistence unit {0} has no connection pool: each transaction opens "
                    + "its own connection. Add noether-jpa-pool-mansart to pool them, or pass a pooled javax.sql.DataSource "
                    + "as jakarta.persistence.nonJtaDataSource ({1}=false silences this message)", settings.unitName(), POOL);
            } else if (pooledDriverFound(url, driver, loader)) {
                return pooled(factory.get(), settings, url, user, password);
            }
        }
        return driverManager(url, user, password, driver, loader);
    }

    /** The pool that {@code factory} opens for the unit, closed with the {@code EntityManagerFactory}. */
    private static ConnectionSource pooled(ConnectionPoolFactory factory, UnitSettings settings, String url, String user,
            String password) {
        ConnectionPoolSettings poolSettings;
        ConnectionPool pool;
        try {
            poolSettings = new ConnectionPoolSettings(url, user, password, integer(settings, POOL_MAX_SIZE),
                integer(settings, POOL_MIN_IDLE), acquireTimeout(settings));
            pool = factory.open(poolSettings);
        } catch (IllegalArgumentException e) {
            throw new PersistenceException("Invalid connection pool settings for persistence unit " + settings.unitName() + " ("
                + POOL_MAX_SIZE + ", " + POOL_MIN_IDLE + ", " + POOL_ACQUIRE_TIMEOUT + "): " + e.getMessage(), e);
        }
        return new ConnectionSource() {
            @Override
            public Connection acquire() throws SQLException {
                return pool.getConnection();
            }

            @Override
            public OptionalInt maxSize() {
                return poolSettings.maxSize() == null ? OptionalInt.empty() : OptionalInt.of(poolSettings.maxSize());
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

    /** The integer value of {@code property}, or {@code null} when the unit does not set it. */
    private static Integer integer(UnitSettings settings, String property) {
        String value = settings.string(property);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(property + " must be an integer, not '" + value + "'", e);
        }
    }

    /** The value of {@link #POOL_ACQUIRE_TIMEOUT}, or {@code null} when the unit does not set it. */
    private static Duration acquireTimeout(UnitSettings settings) {
        String value = settings.string(POOL_ACQUIRE_TIMEOUT);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.parse(value.strip());
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
