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
package io.vidocq.mansart.jpa.core.session;

import java.sql.Connection;

/** What an entity manager does at the boundaries of its resource-local transaction. */
interface TransactionListener {

    /** Before the commit, on the connection of the transaction: the flush (§3.2.4). */
    void beforeCommit(Connection connection);

    /** After a rollback, or a commit that failed: the instances of the persistence context become detached (§3.3.2). */
    void afterRollback();

    /** After a commit: the locks of the transaction are released (§3.5). */
    void afterCommit();
}
