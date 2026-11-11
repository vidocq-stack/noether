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
package io.vidocq.noether.jpa.core.spi;

/**
 * Opens the connection pool of a persistence unit configured by the {@code jakarta.persistence.jdbc.*} properties
 * (§8.2.1.9) in Java SE, where the specification defines no pool (decision D7). Found with
 * {@link java.util.ServiceLoader}: in the module layer of Noether first, then in the unit's class loader; the first one
 * found serves every unit. {@code noether-jpa-pool-mansart} provides one: on the module path its jar is enough, on the
 * class path its {@code META-INF/services} entry. Without one, a unit opens one connection per transaction.
 *
 * <p>Noether asks for a pool only when {@link java.sql.DriverManager} serves the unit's URL, and never for a unit
 * given a {@link javax.sql.DataSource} or configured with {@code io.vidocq.noether.jpa.pool=false}.
 */
public interface ConnectionPoolFactory {

    /**
     * A pool of connections to {@link ConnectionPoolSettings#url()}, opened through {@link java.sql.DriverManager}.
     * Noether closes it when it closes the unit's {@code EntityManagerFactory}.
     *
     * @throws IllegalArgumentException if a setting is out of the range the pool accepts; Noether reports it as a
     *     {@code PersistenceException} that names the pool properties of the unit
     */
    ConnectionPool open(ConnectionPoolSettings settings);
}
