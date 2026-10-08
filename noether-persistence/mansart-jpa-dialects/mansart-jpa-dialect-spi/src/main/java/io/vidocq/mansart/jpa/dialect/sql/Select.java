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
package io.vidocq.mansart.jpa.dialect.sql;

import java.util.List;

/**
 * {@code SELECT columns FROM table WHERE k = ? AND … [lock]}: one parameter per condition. Conditions are required.
 *
 * @param lock the row lock the select takes (§3.5.6), {@link Lock#NONE} for a plain read
 * @param noWait whether a lock that is not available fails at once rather than waits
 */
public record Select(Table table, List<Identifier> columns, List<Identifier> conditions, Lock lock, boolean noWait)
        implements Statement {

    /** The row locks of the pessimistic lock modes. */
    public enum Lock {
        NONE, SHARED, EXCLUSIVE
    }

    public Select {
        columns = Statement.copy(columns, "columns", true);
        conditions = Statement.copy(conditions, "conditions", true);
        lock = lock == null ? Lock.NONE : lock;
    }

    /** A plain read. */
    public Select(Table table, List<Identifier> columns, List<Identifier> conditions) {
        this(table, columns, conditions, Lock.NONE, false);
    }

    /** The same select, taking {@code lock}. */
    public Select locked(Lock lock, boolean noWait) {
        return new Select(table, columns, conditions, lock, noWait);
    }
}
