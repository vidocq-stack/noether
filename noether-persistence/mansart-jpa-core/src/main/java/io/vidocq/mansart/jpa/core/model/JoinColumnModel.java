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
package io.vidocq.mansart.jpa.core.model;

/**
 * A join column as written (§11.1.25), before its defaults are applied: they depend on the target entity, mapped
 * with the rest of the unit.
 *
 * @param name the foreign key column, or {@code null} for the default name
 * @param referencedColumnName the column of the target it references, or {@code null} for its primary key column
 * @param table the table holding the column, or {@code null} for the default one
 */
public record JoinColumnModel(String name, String referencedColumnName, String table, boolean nullable, boolean insertable,
        boolean updatable) {

    /** A join column whose every element takes its default. */
    public static JoinColumnModel defaults() {
        return new JoinColumnModel(null, null, null, true, true, true);
    }
}
