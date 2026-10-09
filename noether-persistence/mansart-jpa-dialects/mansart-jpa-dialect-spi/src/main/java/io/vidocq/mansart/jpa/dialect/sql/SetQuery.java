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

/** Selects combined by a set operator, with ordering and paging applied to the combined result. */
public record SetQuery(List<Query> operands, List<Operation> operations, List<Order> orderBy, Integer offset, Integer limit)
        implements SelectStatement {

    public enum Operator {
        UNION, INTERSECT, EXCEPT
    }

    public record Operation(Operator operator, boolean all) {
        public Operation {
            Objects.requireNonNull(operator, "operator");
        }
    }

    public record Order(int position, boolean descending, Boolean nullsFirst) {
        public Order {
            if (position < 1) {
                throw new IllegalArgumentException("A set query order position starts at 1");
            }
        }
    }

    public SetQuery {
        operands = List.copyOf(operands);
        operations = List.copyOf(operations);
        orderBy = List.copyOf(orderBy);
        if (operands.size() < 2 || operations.size() != operands.size() - 1) {
            throw new IllegalArgumentException("A set query needs one operation between each pair of selects");
        }
    }

    @Override
    public Table table() {
        return operands.getFirst().table();
    }

    /** Parameters are read in the order their operand queries render. */
    @Override
    public List<Expression.Parameter> parameters() {
        List<Expression.Parameter> parameters = new ArrayList<>();
        operands.forEach(query -> parameters.addAll(query.parameters()));
        return List.copyOf(parameters);
    }
}
