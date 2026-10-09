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

/** A stored-procedure invocation in registration order (§3.11.12). */
public record ProcedureCall(List<Identifier> procedureName, List<Parameter> parameters) {

    public ProcedureCall {
        procedureName = List.copyOf(procedureName);
        if (procedureName.isEmpty()) throw new IllegalArgumentException("A procedure needs a name");
        procedureName.forEach(ProcedureCall::validate);
        parameters = List.copyOf(parameters);
    }

    public ProcedureCall(String procedureName, List<Parameter> parameters) {
        this(Identifier.qualified(procedureName, false), parameters);
    }

    private static void validate(Identifier identifier) {
        if (!identifier.quoted() && !identifier.name().matches("[A-Za-z_][A-Za-z0-9_$]*")) {
            throw new IllegalArgumentException("Invalid routine identifier: " + identifier.name());
        }
    }

    /** Parameter direction and JDBC type; the driver binds values, the dialect shapes the invocation. */
    public record Parameter(Mode mode, int jdbcType, Identifier name) {
        public Parameter {
            if (name != null) validate(name);
        }

        public Parameter(Mode mode, int jdbcType) {
            this(mode, jdbcType, null);
        }
    }

    public enum Mode {
        IN, OUT, INOUT, REF_CURSOR
    }
}
