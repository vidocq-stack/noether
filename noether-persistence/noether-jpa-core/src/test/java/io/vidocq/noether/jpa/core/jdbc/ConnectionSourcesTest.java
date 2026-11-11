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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.noether.jpa.core.bootstrap.Definitions;
import io.vidocq.noether.jpa.core.bootstrap.UnitSettings;
import io.vidocq.noether.jpa.core.session.CountingDataSource;
import io.vidocq.noether.jpa.core.spi.ConnectionPool;
import io.vidocq.noether.jpa.core.spi.ConnectionPoolFactory;
import io.vidocq.noether.jpa.core.spi.ConnectionPoolSettings;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The connections of a unit configured by {@code jakarta.persistence.jdbc.*} (§8.2.1.9): pooled when a
 * {@link ConnectionPoolFactory} is found (decision D7, {@code noether-jpa-pool-mansart}), one per transaction otherwise.
 */
class ConnectionSourcesTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final Supplier<Optional<ConnectionPoolFactory>> NOT_LOOKED_UP = () -> {
        throw new AssertionError("the connection pool factory was looked up");
    };

    private static UnitSettings unit(Map<String, String> properties) {
        PersistenceConfiguration configuration = new PersistenceConfiguration("connections")
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:connections-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        properties.forEach(configuration::property);
        return UnitSettings.of(Definitions.from(configuration, loader()), Map.of(), Map.of());
    }

    private static ClassLoader loader() {
        return ConnectionSourcesTest.class.getClassLoader();
    }

    private static Object session(Connection connection) throws SQLException {
        try (Statement query = connection.createStatement(); ResultSet session = query.executeQuery("select session_id()")) {
            session.next();
            return session.getObject(1);
        }
    }

    @Test
    void noConnectionPoolFactoryIsVisibleToTheCoreAlone() {
        assertThat(ConnectionPools.find(loader())).isEmpty();
    }

    @Test
    void withoutAConnectionPoolFactoryEachAcquisitionOpensItsOwnConnection() throws SQLException {
        ConnectionSource source = ConnectionSources.of(unit(Map.of()), loader());
        Object first;
        try (Connection connection = source.acquire()) {
            first = session(connection);
        }
        try (Connection connection = source.acquire()) {
            assertThat(session(connection)).isNotEqualTo(first);
        }
    }

    @Test
    void theConnectionPoolFactoryReceivesTheConnectionAndPoolSettingsOfTheUnit() {
        RecordingPools pools = new RecordingPools();
        UnitSettings unit = unit(Map.of(PersistenceConfiguration.JDBC_USER, "sa", PersistenceConfiguration.JDBC_PASSWORD, "secret",
            ConnectionSources.POOL_MAX_SIZE, " 4 ", ConnectionSources.POOL_MIN_IDLE, "1", ConnectionSources.POOL_ACQUIRE_TIMEOUT, "PT0.2S"));
        ConnectionSources.of(unit, loader(), pools::found);
        ConnectionPoolSettings settings = pools.settings;
        assertThat(settings.url()).isEqualTo(unit.string(UnitSettings.JDBC_URL));
        assertThat(settings.user()).isEqualTo("sa");
        assertThat(settings.password()).isEqualTo("secret");
        assertThat(settings.maxSize()).isEqualTo(4);
        assertThat(settings.minIdle()).isEqualTo(1);
        assertThat(settings.acquireTimeout()).isEqualTo(Duration.ofMillis(200));
        assertThat(settings.toString()).doesNotContain("secret");
    }

    @Test
    void anUnsetPoolSettingIsLeftToThePool() {
        RecordingPools pools = new RecordingPools();
        ConnectionSources.of(unit(Map.of()), loader(), pools::found);
        assertThat(pools.settings.maxSize()).isNull();
        assertThat(pools.settings.minIdle()).isNull();
        assertThat(pools.settings.acquireTimeout()).isNull();
    }

    @Test
    void aPoolReportsTheMaxSizeTheUnitSets() { // BUG-20261018-04: checked against the TABLE generators of the unit
        ConnectionSource source = ConnectionSources.of(unit(Map.of(ConnectionSources.POOL_MAX_SIZE, " 3 ")), loader(),
            new RecordingPools()::found);
        assertThat(source.maxSize()).hasValue(3);
    }

    @Test
    void theMaxSizeIsUnknownWithoutAPoolOrWithThePoolDefault() {
        Map<String, String> oneConnection = Map.of(ConnectionSources.POOL_MAX_SIZE, "1");
        assertThat(ConnectionSources.of(unit(Map.of()), loader(), new RecordingPools()::found).maxSize())
            .as("the pool's own default").isEmpty();
        assertThat(ConnectionSources.of(unit(oneConnection), loader()).maxSize())
            .as("no connection pool factory: one connection per transaction").isEmpty();
        assertThat(ConnectionSources.of(unit(Map.of(ConnectionSources.POOL_MAX_SIZE, "1", ConnectionSources.POOL, "false")),
            loader(), NOT_LOOKED_UP).maxSize()).as("pooling turned off").isEmpty();
        DataSource dataSource = new CountingDataSource("jdbc:h2:mem:connections-unsized;DB_CLOSE_DELAY=-1");
        UnitSettings application = UnitSettings.of(Definitions.from(new PersistenceConfiguration("unsized")
            .property(ConnectionSources.POOL_MAX_SIZE, "1"), loader()), Map.of(), Map.of(Definitions.NON_JTA_DATA_SOURCE, dataSource));
        assertThat(ConnectionSources.of(application, loader(), NOT_LOOKED_UP).maxSize()).as("a DataSource of the application")
            .isEmpty();
    }

    @Test
    void theConnectionsComeFromThePoolWhichClosesWithTheSource() throws SQLException {
        RecordingPools pools = new RecordingPools();
        ConnectionSource source = ConnectionSources.of(unit(Map.of()), loader(), pools::found);
        try (Connection connection = source.acquire()) {
            assertThat(connection.isValid(1)).isTrue();
        }
        assertThat(pools.acquired).hasValue(1);
        assertThat(pools.closed).isFalse();
        source.close();
        assertThat(pools.closed).isTrue();
    }

    @Test
    void poolingTurnedOffLooksUpNoConnectionPoolFactory() throws SQLException {
        ConnectionSource source = ConnectionSources.of(unit(Map.of(ConnectionSources.POOL, " FALSE ")), loader(), NOT_LOOKED_UP);
        try (Connection connection = source.acquire()) {
            assertThat(connection.isValid(1)).isTrue();
        }
    }

    @Test
    void aDataSourceObjectIsUsedAsIsWithoutLookingUpAConnectionPoolFactory() throws SQLException {
        DataSource dataSource = new CountingDataSource("jdbc:h2:mem:connections-data-source;DB_CLOSE_DELAY=-1");
        PersistenceConfiguration configuration = new PersistenceConfiguration("data-source")
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:unused");
        UnitSettings unit = UnitSettings.of(Definitions.from(configuration, loader()), Map.of(),
            Map.of(Definitions.NON_JTA_DATA_SOURCE, dataSource));
        try (Connection connection = ConnectionSources.of(unit, loader(), NOT_LOOKED_UP).acquire()) {
            assertThat(connection.isValid(1)).isTrue();
        }
    }

    @Test
    void anInvalidPoolSettingFailsTheCreationOfTheFactory() {
        RecordingPools pools = new RecordingPools();
        ClassLoader loader = loader();
        UnitSettings maxSize = unit(Map.of(ConnectionSources.POOL_MAX_SIZE, "0"));
        UnitSettings minIdle = unit(Map.of(ConnectionSources.POOL_MIN_IDLE, "some"));
        UnitSettings acquireTimeout = unit(Map.of(ConnectionSources.POOL_ACQUIRE_TIMEOUT, "soon"));
        assertThatThrownBy(() -> ConnectionSources.of(maxSize, loader, pools::found))
            .isInstanceOf(PersistenceException.class).hasMessageContaining(ConnectionSources.POOL_MAX_SIZE)
            .hasMessageContaining("maxSize must be at least 1");
        assertThatThrownBy(() -> ConnectionSources.of(minIdle, loader, pools::found))
            .isInstanceOf(PersistenceException.class).hasMessageContaining(ConnectionSources.POOL_MIN_IDLE + " must be an integer");
        assertThatThrownBy(() -> ConnectionSources.of(acquireTimeout, loader, pools::found))
            .isInstanceOf(PersistenceException.class).hasMessageContaining(ConnectionSources.POOL_ACQUIRE_TIMEOUT + " must be an ISO-8601 duration");
    }

    /**
     * BUG-20261111-04: a table generator allocates through the unit's own connections when it is resource-local, through
     * the non-JTA data source of a JTA unit, and has nowhere to allocate in a JTA unit without one.
     */
    @Test
    void tableGeneratorsAllocateThroughTheConnectionsOfAResourceLocalUnitOrTheNonJtaDataSourceOfAJtaUnit() throws SQLException {
        ConnectionSource own = () -> {
            throw new AssertionError("not acquired");
        };
        assertThat(ConnectionSources.allocations(unit(Map.of()), own)).containsSame(own);

        DataSource nonJta = new CountingDataSource("jdbc:h2:mem:allocations;DB_CLOSE_DELAY=-1");
        PersistenceConfiguration configuration = new PersistenceConfiguration("allocations")
            .property(UnitSettings.TRANSACTION_TYPE, "JTA");
        UnitSettings jta = UnitSettings.of(Definitions.from(configuration, loader()), Map.of(), Map.of());
        UnitSettings jtaWithNonJta = UnitSettings.of(Definitions.from(configuration, loader()), Map.of(),
            Map.of(Definitions.NON_JTA_DATA_SOURCE, nonJta));

        assertThat(ConnectionSources.allocations(jta, own)).isEmpty();
        ConnectionSource allocations = ConnectionSources.allocations(jtaWithNonJta, own).orElseThrow();
        try (Connection connection = allocations.acquire()) { // from the data source: own refuses
            assertThat(connection.isValid(1)).isTrue();
        }
    }

    /** A hand-written connection pool factory: it records what it is given, its pools open plain H2 connections. */
    private static final class RecordingPools implements ConnectionPoolFactory {
        private final AtomicInteger acquired = new AtomicInteger();
        private ConnectionPoolSettings settings;
        private boolean closed;

        Optional<ConnectionPoolFactory> found() {
            return Optional.of(this);
        }

        @Override
        public ConnectionPool open(ConnectionPoolSettings poolSettings) {
            if (poolSettings.maxSize() != null && poolSettings.maxSize() < 1) {
                throw new IllegalArgumentException("maxSize must be at least 1");
            }
            settings = poolSettings;
            return new ConnectionPool() {
                @Override
                public Connection getConnection() throws SQLException {
                    acquired.incrementAndGet();
                    return DriverManager.getConnection(poolSettings.url());
                }

                @Override
                public void close() {
                    closed = true;
                }
            };
        }
    }
}
