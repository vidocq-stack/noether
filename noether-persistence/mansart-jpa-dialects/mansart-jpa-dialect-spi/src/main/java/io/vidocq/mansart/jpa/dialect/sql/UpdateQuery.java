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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A bulk update: {@code UPDATE table AS alias SET column = value, … [WHERE where]}. The assigned columns are the
 * table's, unqualified; the values and the condition may name the alias and hold subqueries.
 */
public record UpdateQuery(Table table, String alias, List<Assignment> assignments, Expression where) implements Statement {

    /** {@code column = value}. */
    public record Assignment(Identifier column, Expression value) {
    }

    public UpdateQuery {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(alias, "alias");
        assignments = List.copyOf(assignments);
        if (assignments.isEmpty()) {
            throw new IllegalArgumentException("An update sets at least one column");
        }
    }

    /** The parameters, in their logical order: the values, then the condition. */
    public List<Expression.Parameter> parameters() {
        List<Expression> expressions = new ArrayList<>();
        assignments.forEach(a -> expressions.add(a.value()));
        expressions.add(where);
        return Query.parametersOf(expressions);
    }
}
