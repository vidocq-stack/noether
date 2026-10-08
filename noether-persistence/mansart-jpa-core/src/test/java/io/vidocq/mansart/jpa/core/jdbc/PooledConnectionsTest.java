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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.jpa.core.bootstrap.Definitions;
import io.vidocq.mansart.jpa.core.bootstrap.UnitSettings;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Decision D7: a unit configured by jakarta.persistence.jdbc.* gets a mansart-pool pool in Java SE. */
class PooledConnectionsTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private static ConnectionSource source(Map<String, String> properties) {
        PersistenceConfiguration configuration = new PersistenceConfiguration("pooled")
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:pooled-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        properties.forEach(configuration::property);
        ClassLoader loader = PooledConnectionsTest.class.getClassLoader();
        return ConnectionSources.of(UnitSettings.of(Definitions.from(configuration, loader), Map.of(), Map.of()), loader);
    }

    private static Object session(Connection connection) throws SQLException {
        try (Statement query = connection.createStatement(); ResultSet session = query.executeQuery("select session_id()")) {
            session.next();
            return session.getObject(1);
        }
    }

    @Test
    void connectionsAreReusedByDefault() throws SQLException {
        ConnectionSource source = source(Map.of());
        Object first;
        try (Connection connection = source.acquire()) {
            first = session(connection);
        }
        try (Connection connection = source.acquire()) {
            assertThat(session(connection)).isEqualTo(first);
        } finally {
            source.close();
        }
    }

    @Test
    void poolingCanBeTurnedOff() throws SQLException {
        ConnectionSource source = source(Map.of(ConnectionSources.POOL, "false"));
        Object first;
        try (Connection connection = source.acquire()) {
            first = session(connection);
        }
        try (Connection connection = source.acquire()) {
            assertThat(session(connection)).isNotEqualTo(first);
        }
    }

    @Test
    void thePoolIsSizedByTheUnit() throws SQLException {
        ConnectionSource source = source(Map.of(ConnectionSources.POOL_MAX_SIZE, "1", ConnectionSources.POOL_ACQUIRE_TIMEOUT, "PT0.2S"));
        try (Connection held = source.acquire()) {
            assertThat(held).isNotNull();
            assertThatThrownBy(source::acquire).isInstanceOf(SQLException.class);
        } finally {
            source.close();
        }
    }

    @Test
    void aClosedPoolGivesNoMoreConnections() throws SQLException {
        ConnectionSource source = source(Map.of());
        source.acquire().close();
        source.close();
        assertThatThrownBy(source::acquire).isInstanceOf(SQLException.class);
    }

    @Test
    void anInvalidPoolSettingFailsTheCreationOfTheFactory() {
        assertThatThrownBy(() -> source(Map.of(ConnectionSources.POOL_MAX_SIZE, "0"))).isInstanceOf(PersistenceException.class)
            .hasMessageContaining(ConnectionSources.POOL_MAX_SIZE);
        assertThatThrownBy(() -> source(Map.of(ConnectionSources.POOL_ACQUIRE_TIMEOUT, "soon"))).isInstanceOf(PersistenceException.class)
            .hasMessageContaining(ConnectionSources.POOL_ACQUIRE_TIMEOUT);
    }

    @Test
    void closingTheFactoryClosesItsPool() throws SQLException {
        EntityManagerFactory emf = new PersistenceConfiguration("closing").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:closing;DB_CLOSE_DELAY=-1").createEntityManagerFactory();
        ConnectionSource source = emf.unwrap(io.vidocq.mansart.jpa.core.session.EntityManagerFactoryImpl.class).connectionSource();
        emf.close();
        assertThatThrownBy(source::acquire).isInstanceOf(SQLException.class);
    }
}
