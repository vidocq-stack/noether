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
package io.vidocq.mansart.jpa.core.mapping;

import io.vidocq.mansart.jpa.core.jdbc.type.ValueBinder;
import io.vidocq.mansart.jpa.core.model.ElementCollectionAttribute;
import io.vidocq.mansart.jpa.dialect.sql.Delete;
import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import io.vidocq.mansart.jpa.dialect.sql.Insert;
import io.vidocq.mansart.jpa.dialect.sql.Select;
import io.vidocq.mansart.jpa.dialect.sql.Table;
import java.util.ArrayList;
import java.util.List;

/**
 * An element collection in its collection table (§2.7, §11.1.8): one row per element, the key of the owner then the
 * columns of the element — its value, or the columns of its embeddable. Elements have no identity: a changed collection
 * is written again whole.
 *
 * @param columns the columns of the element, as {@link EntityStatements.Column}s of a one-attribute state (attribute
 *        0: the element)
 */
public record ElementCollectionMapping(int attribute, ElementCollectionAttribute model, Table table, List<Identifier> ownerColumns,
        List<ValueBinder> ownerBinders, List<EntityStatements.Column> columns) {

    public ElementCollectionMapping {
        ownerColumns = List.copyOf(ownerColumns);
        ownerBinders = List.copyOf(ownerBinders);
        columns = List.copyOf(columns);
    }

    /** The row of one element: the owner columns, then the element columns. */
    public Insert insert() {
        List<Identifier> names = new ArrayList<>(ownerColumns);
        columns.forEach(c -> names.add(c.name()));
        return new Insert(table, names, null);
    }

    /** The delete of every row of an owner, by its key. */
    public Delete deleteOwner() {
        return new Delete(table, ownerColumns);
    }

    /** The element columns of the rows of an owner, by its key. */
    public Select select() {
        return new Select(table, columns.stream().map(EntityStatements.Column::name).toList(), ownerColumns);
    }

    /** The values of the element columns of {@code element}, in {@link #columns()} order. */
    public Object[] values(Object element) {
        Object[] values = new Object[columns.size()];
        for (int c = 0; c < values.length; c++) {
            EntityStatements.Column column = columns.get(c);
            Object value = element;
            for (int step = 0; step < column.path().length && value != null; step++) {
                value = column.accesses()[step].get(value, column.path()[step]);
            }
            values[c] = value;
        }
        return values;
    }

    /** The element whose columns hold {@code values}; {@code nulls} tells which were SQL {@code NULL}. */
    public Object element(Object[] values, boolean[] nulls) {
        Object[] state = new Object[1];
        EntityStatements.hydrate(columns, values, nulls, state);
        return state[0];
    }
}
