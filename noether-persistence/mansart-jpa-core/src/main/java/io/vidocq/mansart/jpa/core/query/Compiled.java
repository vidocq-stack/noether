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
package io.vidocq.mansart.jpa.core.query;

import io.vidocq.mansart.jpa.core.jdbc.type.ValueBinder;
import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import io.vidocq.mansart.jpa.core.query.jpql.Ast;
import io.vidocq.mansart.jpa.dialect.sql.Statement;
import java.util.List;

/**
 * A statement translated to SQL — a select, a bulk update or delete — what each of its parameters binds, and how each
 * select item is read back (none for a bulk statement).
 *
 * @param resultType the Java type of a single select item, {@code null} when it cannot be told; {@code Object[]} for
 *        several
 * @param slots what the parameters of {@code sql} bind, in their order
 */
record Compiled(Statement sql, List<Item> items, Class<?> resultType, List<Slot> slots, List<Mutation> mutations) {

    Compiled(Statement sql, List<Item> items, Class<?> resultType, List<Slot> slots) {
        this(sql, items, resultType, slots, List.of());
    }

    /** A table mutation whose parameters come from a captured key/value row (§4.10, joined inheritance). */
    record Mutation(Statement sql, List<Integer> values, List<ValueBinder> binders) {}

    /**
     * What a SQL parameter binds: the query parameter, the element of a collection-valued one ({@code element} ≥ 0,
     * §4.6.9), the key part of an entity one ({@code entity} not null), through {@code binder} — {@code null} to let
     * the driver choose; {@code type} the Java type the parameter is compared with, if known.
     */
    record Slot(Ast.Parameter parameter, int element, MappedEntity entity, int part, ValueBinder binder, Class<?> type,
            Object constant) {

        /** A literal the SQL cannot write (an enum constant, a date), bound through the binder of what it is compared with. */
        static Slot constant(Object value, ValueBinder binder, Class<?> type) {
            return new Slot(null, -1, null, -1, binder, type, value);
        }

        /** Whether it binds a literal of the query rather than a parameter. */
        boolean literal() {
            return parameter == null;
        }
    }

    /** How a select item is read from the row. */
    sealed interface Item permits EntityItem, ValueItem, ConstructorItem {
        /** The columns it takes in the row. */
        int width();
    }

    /** An entity, read by its key columns, then found in the persistence context or loaded. */
    record EntityItem(MappedEntity type, List<ValueBinder> keyBinders) implements Item {
        @Override
        public int width() {
            return keyBinders.size();
        }
    }

    /**
     * A value, read through the binder of the attribute it comes from, or as the driver gives it then converted to
     * {@code type} (aggregates, §4.8.5); {@code type} {@code null}: as the driver gives it.
     */
    record ValueItem(ValueBinder binder, Class<?> type) implements Item {
        @Override
        public int width() {
            return 1;
        }
    }

    /** {@code NEW type(arguments)} (§4.8.2): an instance of {@code type} built from the values of its arguments. */
    record ConstructorItem(Class<?> type, List<Item> arguments) implements Item {
        @Override
        public int width() {
            return arguments.stream().mapToInt(Item::width).sum();
        }
    }
}
