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
 * The transaction bridge of a JTA unit on a Jakarta EE container (§7.5, §9.6): the container's data source enlists
 * the connections and the container completes them; Noether flushes before completion and detaches after it,
 * through the standard {@code TransactionSynchronizationRegistry} (JTA 2.0 §3.4.4). Each unit of work takes a
 * connection from the data source and closes it when the work ends; within a transaction the container hands out the
 * connection it enlisted again. No transaction manager, CDI or container-specific class is involved.
 */
module io.vidocq.noether.jpa.jta {
    requires io.vidocq.noether.jpa.core;
    requires jakarta.persistence;
    requires jakarta.transaction;
    requires java.naming;
    requires java.sql;

    exports io.vidocq.noether.jpa.jta;

    provides io.vidocq.noether.jpa.core.spi.TransactionIntegrationProvider
        with io.vidocq.noether.jpa.jta.JndiTransactionIntegrationProvider;
}
