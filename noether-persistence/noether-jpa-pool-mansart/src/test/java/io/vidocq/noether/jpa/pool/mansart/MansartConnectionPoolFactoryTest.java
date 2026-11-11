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
package io.vidocq.noether.jpa.pool.mansart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import io.vidocq.mansart.pool.PoolConfig;
import io.vidocq.noether.jpa.core.spi.ConnectionPool;
import io.vidocq.noether.jpa.core.spi.ConnectionPoolFactory;
import io.vidocq.noether.jpa.core.spi.ConnectionPoolSettings;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Decision D7: a unit configured by jakarta.persistence.jdbc.* gets a mansart-pool pool in Java SE. */
class MansartConnectionPoolFactoryTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private final ConnectionPoolFactory factory = new MansartConnectionPoolFactory();

    private static ConnectionPoolSettings settings(Integer maxSize, Integer minIdle, Duration acquireTimeout) {
        return new ConnectionPoolSettings("jdbc:h2:mem:pooled-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1", "sa", "",
            maxSize, minIdle, acquireTimeout);
    }

    private static Object session(Connection connection) throws SQLException {
        try (Statement query = connection.createStatement(); ResultSet session = query.executeQuery("select session_id()")) {
            session.next();
            return session.getObject(1);
        }
    }

    @Test
    void connectionsAreReused() throws SQLException {
        try (ConnectionPool pool = factory.open(settings(null, null, null))) {
            Object first;
            try (Connection connection = pool.getConnection()) {
                first = session(connection);
            }
            try (Connection connection = pool.getConnection()) {
                assertThat(session(connection)).isEqualTo(first);
            }
        }
    }

    @Test
    void thePoolIsSizedByTheSettings() throws SQLException {
        try (ConnectionPool pool = factory.open(settings(1, null, Duration.ofMillis(200)));
             Connection held = pool.getConnection()) {
            assertThat(held).isNotNull();
            assertThatThrownBy(pool::getConnection).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void theSettingsTheUnitLeavesOutKeepThePoolDefaults() {
        try (ConnectionPool pool = factory.open(settings(null, null, null))) {
            PoolConfig config = ((MansartConnectionPoolFactory.Pool) pool).dataSource().config();
            assertThat(config.maxSize()).isEqualTo(10);
            assertThat(config.minIdle()).isZero();
            assertThat(config.acquireTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(config.username()).isEqualTo("sa");
        }
        try (ConnectionPool pool = factory.open(settings(4, 2, Duration.ofSeconds(1)))) {
            PoolConfig config = ((MansartConnectionPoolFactory.Pool) pool).dataSource().config();
            assertThat(config.maxSize()).isEqualTo(4);
            assertThat(config.minIdle()).isEqualTo(2);
            assertThat(config.acquireTimeout()).isEqualTo(Duration.ofSeconds(1));
        }
    }

    @Test
    void aClosedPoolGivesNoMoreConnections() throws SQLException {
        ConnectionPool closed;
        try (ConnectionPool pool = factory.open(settings(null, null, null)); Connection connection = pool.getConnection()) {
            closed = pool;
            assertThat(connection.isValid(1)).isTrue();
        }
        assertThatThrownBy(closed::getConnection).isInstanceOf(SQLException.class);
    }

    @Test
    void aSettingOutOfRangeIsAnIllegalArgument() { // Noether turns it into a PersistenceException naming the property
        assertThatThrownBy(() -> factory.open(settings(0, null, null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFactoryIsAServiceOnTheModulePathAndOnTheClassPath() throws IOException {
        Module module = MansartConnectionPoolFactory.class.getModule();
        assertThat(module.getDescriptor().provides()).extracting(ModuleDescriptor.Provides::service, ModuleDescriptor.Provides::providers)
            .contains(tuple(ConnectionPoolFactory.class.getName(), List.of(MansartConnectionPoolFactory.class.getName())));
        try (InputStream entry = module.getResourceAsStream("META-INF/services/" + ConnectionPoolFactory.class.getName())) {
            assertThat(entry).isNotNull();
            assertThat(new String(entry.readAllBytes(), StandardCharsets.UTF_8).strip())
                .isEqualTo(MansartConnectionPoolFactory.class.getName());
        }
    }
}
