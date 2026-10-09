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
import io.vidocq.mansart.jpa.core.model.CollectionIndex;
import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.LinkedHashMap;

/**
 * The index of a map or an ordered list as mapped: the columns that hold it — a key, the foreign key of a key entity, a
 * position — in the table of the rows of the collection, or none for a key that is an attribute of the elements.
 *
 * @param keyAttribute for {@link CollectionIndex.ByAttribute}, the index of the key attribute in the target, or -1 for
 *        its identifier
 * @param keyEntity for {@link CollectionIndex.ByEntity}, the entity of the keys
 */
public record IndexMapping(CollectionIndex index, int keyAttribute, Class<?> keyEntity, List<Identifier> columns,
        List<ValueBinder> binders, boolean insertable, boolean updatable, List<EntityStatements.Column> keyColumns) {

    public IndexMapping {
        columns = List.copyOf(columns);
        binders = List.copyOf(binders);
        keyColumns = List.copyOf(keyColumns);
    }

    public IndexMapping(CollectionIndex index, int keyAttribute, Class<?> keyEntity, List<Identifier> columns,
            List<ValueBinder> binders, boolean insertable, boolean updatable) {
        this(index, keyAttribute, keyEntity, columns, binders, insertable, updatable, List.of());
    }

    /** Whether the index has columns of its own; a key that is an attribute of the elements has none. */
    public boolean stored() {
        return !columns.isEmpty();
    }

    /** Whether it keeps positions, of a list, rather than the keys of a map. */
    public boolean positional() {
        return index instanceof CollectionIndex.ByPosition;
    }

    /**
     * The values of the index columns for {@code key} — a map key, or a position — in {@link #columns()} order; the key
     * of a key entity through {@code statements}. {@code null} for an entity key without identifier.
     */
    public Object[] values(Object key, EntityStatements statements) {
        if (index instanceof CollectionIndex.ByEntity) {
            return key == null ? new Object[columns.size()] : statements.targetKey(key, keyEntity);
        }
        if (index instanceof CollectionIndex.ByEmbedded) {
            Object[] values = new Object[keyColumns.size()];
            for (int c = 0; c < values.length; c++) {
                EntityStatements.Column column = keyColumns.get(c);
                Object value = key;
                for (int step = 0; step < column.path().length && value != null; step++) {
                    value = column.accesses()[step].get(value, column.path()[step]);
                }
                values[c] = value;
            }
            return values;
        }
        return new Object[] {key};
    }

    /** Reconstructs an embeddable key through the same generated access/hydration path as collection values. */
    public Object embeddedKey(Object[] values) {
        Object[] state = new Object[1];
        boolean[] nulls = new boolean[values.length];
        for (int i = 0; i < values.length; i++) nulls[i] = values[i] == null;
        EntityStatements.hydrate(keyColumns, values, nulls, state);
        return state[0];
    }

    /** A new, empty map for an attribute declared {@code type}: insertion-ordered, sorted for a {@code SortedMap}. */
    public static Map<Object, Object> newMap(Class<?> type) {
        return type.isAssignableFrom(LinkedHashMap.class) ? new LinkedHashMap<>() : new TreeMap<>();
    }
}
