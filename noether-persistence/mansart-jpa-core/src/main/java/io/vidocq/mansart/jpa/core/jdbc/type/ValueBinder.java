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
package io.vidocq.mansart.jpa.core.jdbc.type;

import io.vidocq.mansart.jpa.dialect.Dialect;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Writes the value of a basic attribute to a statement parameter, and reads it back from a result column. */
public interface ValueBinder {
    /** Whether domain literals must pass through an attribute converter before reaching SQL. */
    default boolean converted() { return false; }

    /** Binds {@code value}, {@code null} included, to the parameter {@code index}, as {@code dialect} stores it. */
    void bind(Dialect dialect, PreparedStatement statement, int index, Object value) throws SQLException;

    /** The attribute value of the column {@code column}; SQL {@code NULL} gives the default value of a primitive. */
    Object read(ResultSet results, int column) throws SQLException;
}
