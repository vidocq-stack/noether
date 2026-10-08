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
 * A column as mapped (§11.1.9).
 *
 * @param table the table, or {@code null} for the primary table of the entity
 * @param columnDefinition the SQL fragment of {@code columnDefinition}, or {@code null}
 */
public record ColumnModel(String name, String table, boolean nullable, boolean insertable, boolean updatable, boolean unique,
        int length, int precision, int scale, String columnDefinition) {

    /** The defaults of an unannotated attribute named {@code name}. */
    public static ColumnModel defaultFor(String name) {
        return new ColumnModel(name, null, true, true, true, false, 255, 0, 0, null);
    }

    public ColumnModel withName(String newName) {
        return new ColumnModel(newName, table, nullable, insertable, updatable, unique, length, precision, scale, columnDefinition);
    }
}
