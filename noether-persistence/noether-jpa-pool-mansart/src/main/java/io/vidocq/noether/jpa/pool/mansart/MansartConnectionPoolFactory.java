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

import io.vidocq.mansart.pool.PoolConfig;
import io.vidocq.mansart.pool.core.MansartDataSource;
import io.vidocq.noether.jpa.core.spi.ConnectionPool;
import io.vidocq.noether.jpa.core.spi.ConnectionPoolFactory;
import io.vidocq.noether.jpa.core.spi.ConnectionPoolSettings;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Decision D7: the connections of a Noether unit configured by {@code jakarta.persistence.jdbc.*} come from a
 * {@code mansart-pool} pool (zero dependency, virtual-thread-native), which opens them through
 * {@link java.sql.DriverManager}. A setting the unit leaves out keeps the default of {@link PoolConfig}: 10 connections
 * at most, none kept when idle, 5 seconds to wait for one.
 */
public final class MansartConnectionPoolFactory implements ConnectionPoolFactory {

    @Override
    public ConnectionPool open(ConnectionPoolSettings settings) {
        PoolConfig.Builder config = PoolConfig.builder().jdbcUrl(settings.url()).username(settings.user())
            .password(settings.password());
        if (settings.maxSize() != null) {
            config.maxSize(settings.maxSize());
        }
        if (settings.minIdle() != null) {
            config.minIdle(settings.minIdle());
        }
        if (settings.acquireTimeout() != null) {
            config.acquireTimeout(settings.acquireTimeout());
        }
        return new Pool(MansartDataSource.of(config.build()));
    }

    /** A {@code mansart-pool} pool, closed by Noether with the {@code EntityManagerFactory} of its unit. */
    record Pool(MansartDataSource dataSource) implements ConnectionPool {

        @Override
        public Connection getConnection() throws SQLException {
            return dataSource.getConnection();
        }

        @Override
        public void close() {
            dataSource.close();
        }
    }
}
