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

import java.time.Duration;

/**
 * What a {@link ConnectionPoolFactory} gets from a persistence unit: its {@code jakarta.persistence.jdbc.url},
 * {@code .user} and {@code .password}, and the {@code io.vidocq.noether.jpa.pool.*} settings. A setting the unit does
 * not give is {@code null}: the pool then uses its own default.
 *
 * @param url the JDBC URL of the database
 * @param user the database user, or {@code null}
 * @param password the password of the user, or {@code null}
 * @param maxSize {@code io.vidocq.noether.jpa.pool.max-size}, the maximum number of connections, or {@code null}
 * @param minIdle {@code io.vidocq.noether.jpa.pool.min-idle}, the connections kept open when idle, or {@code null}
 * @param acquireTimeout {@code io.vidocq.noether.jpa.pool.acquire-timeout}, how long a transaction waits for a
 *     connection, or {@code null}
 */
public record ConnectionPoolSettings(String url, String user, String password, Integer maxSize, Integer minIdle,
        Duration acquireTimeout) {

    /** The settings without the password. */
    @Override
    public String toString() {
        return "ConnectionPoolSettings[url=" + url + ", user=" + user + ", maxSize=" + maxSize + ", minIdle=" + minIdle
            + ", acquireTimeout=" + acquireTimeout + "]";
    }
}
