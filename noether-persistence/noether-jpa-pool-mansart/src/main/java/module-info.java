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
/**
 * The connection pool of the Noether units configured by {@code jakarta.persistence.jdbc.*} in Java SE (decision
 * D7): a {@code mansart-pool} pool. Nothing is exported: {@code io.vidocq.noether.jpa.core} finds the factory with
 * {@link java.util.ServiceLoader}, so an application only puts this module on its module path (service binding
 * resolves it) or its jar on the class path ({@code META-INF/services}).
 */
module io.vidocq.noether.jpa.pool.mansart {
    requires io.vidocq.noether.jpa.core;
    requires io.vidocq.mansart.pool.core;
    requires java.sql;

    provides io.vidocq.noether.jpa.core.spi.ConnectionPoolFactory
        with io.vidocq.noether.jpa.pool.mansart.MansartConnectionPoolFactory;
}
