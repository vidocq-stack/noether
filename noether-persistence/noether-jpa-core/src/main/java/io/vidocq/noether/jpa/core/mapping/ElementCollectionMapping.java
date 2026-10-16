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
package io.vidocq.noether.jpa.core.mapping;

import io.vidocq.noether.jpa.core.jdbc.type.ValueBinder;
import io.vidocq.noether.jpa.core.model.ElementCollectionAttribute;
import io.vidocq.noether.jpa.core.model.AssociationAttribute;
import io.vidocq.noether.jpa.core.model.AttributeModel;
import io.vidocq.noether.jpa.core.model.EmbeddedAttribute;
import io.vidocq.noether.jpa.core.spi.ManagedAccess;
import io.vidocq.noether.jpa.dialect.sql.Delete;
import io.vidocq.noether.jpa.dialect.sql.Identifier;
import io.vidocq.noether.jpa.dialect.sql.Insert;
import io.vidocq.noether.jpa.dialect.sql.Select;
import io.vidocq.noether.jpa.dialect.sql.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * An element collection in its collection table (§2.7, §11.1.8): one row per element, the key of the owner then the
 * columns of the element — its value, or the columns of its embeddable. Elements have no identity: a changed collection
 * is written again whole.
 *
 * @param index the key of a map or the position in a list, or {@code null}
 * @param columns the columns of the element, as {@link EntityStatements.Column}s of a one-attribute state (attribute
 *        0: the element)
 */
public record ElementCollectionMapping(int attribute, ElementCollectionAttribute model, Table table, List<Identifier> ownerColumns,
        List<ValueBinder> ownerBinders, IndexMapping index, List<EntityStatements.Column> columns) {

    public ElementCollectionMapping {
        ownerColumns = List.copyOf(ownerColumns);
        ownerBinders = List.copyOf(ownerBinders);
        columns = List.copyOf(columns);
    }

    /** The row of one element: the owner columns, the index columns if any, then the element columns. */
    public Insert insert() {
        List<Identifier> names = new ArrayList<>(ownerColumns);
        names.addAll(indexColumns());
        columns.forEach(c -> names.add(c.name()));
        return new Insert(table, names, null);
    }

    /** The delete of every row of an owner, by its key. */
    public Delete deleteOwner() {
        return new Delete(table, ownerColumns);
    }

    /** The index columns if any, then the element columns, of the rows of an owner, by its key. */
    public Select select() {
        List<Identifier> names = new ArrayList<>(indexColumns());
        columns.forEach(c -> names.add(c.name()));
        return new Select(table, names, ownerColumns);
    }

    /** The index columns, none for an unindexed collection. */
    public List<Identifier> indexColumns() {
        return index == null ? List.of() : index.columns();
    }

    /** The values of the element columns of {@code element}, in {@link #columns()} order. */
    public Object[] values(Object element) {
        return values(element, (_, _) -> {
            throw new IllegalStateException("An element relationship requires target identity access");
        });
    }

    public Object[] values(Object element, BiFunction<Object, Class<?>, Object[]> keys) {
        Object[] values = new Object[columns.size()];
        for (int c = 0; c < values.length; c++) {
            EntityStatements.Column column = columns.get(c);
            Object value = element;
            for (int step = 0; step < column.path().length && value != null; step++) {
                value = column.accesses()[step].get(value, column.path()[step]);
            }
            values[c] = value != null && column.foreignKey() != null
                ? keys.apply(value, column.foreignKey().target())[0] : value;
        }
        return values;
    }

    /** The element whose columns hold {@code values}; {@code nulls} tells which were SQL {@code NULL}. */
    public Object element(Object[] values, boolean[] nulls) {
        return element(values, nulls, (_, _) -> {
            throw new IllegalArgumentException("Collection element relationship hydration requires managed identity resolution");
        });
    }

    public Object element(Object[] values, boolean[] nulls, BiFunction<Class<?>, Object, Object> resolve) {
        values = values.clone();
        for (int c = 0; c < values.length; c++) {
            if (columns.get(c).foreignKey() != null && !nulls[c]) {
                values[c] = resolve.apply(columns.get(c).foreignKey().target(), values[c]);
            }
        }
        Object[] state = new Object[1];
        EntityStatements.hydrate(columns, values, nulls, state, true);
        return state[0];
    }

    public boolean hasReferences() {
        return columns.stream().anyMatch(c -> c.foreignKey() != null);
    }

    public AssociationAttribute association(EntityStatements.Column column) {
        AttributeModel attribute = model.element();
        for (int step : column.path()) {
            attribute = ((EmbeddedAttribute) attribute).embeddable().attributes().get(step);
        }
        return (AssociationAttribute) attribute;
    }

    public void references(Object collection, BiConsumer<AssociationAttribute, Object> action) {
        for (Object element : CollectionMapping.elements(collection)) {
            for (var column : columns) {
                if (column.foreignKey() == null) continue;
                Object value = element;
                for (int i = 0; i < column.path().length && value != null; i++) {
                    value = column.accesses()[i].get(value, column.path()[i]);
                }
                if (value != null) action.accept(association(column), value);
            }
        }
    }

    /** Maps references by generated access, never by embeddable equality or a fictitious element identity. */
    public Object mapReferences(Object collection, BiFunction<AssociationAttribute, Object, Object> action) {
        if (collection instanceof Map<?, ?> map) {
            Map<Object, Object> result = IndexMapping.newMap(model.javaType());
            map.forEach((key, value) -> result.put(key, mapElement(value, action)));
            return result;
        }
        Collection<Object> result = CollectionMapping.newCollection(model.javaType());
        for (Object value : CollectionMapping.elements(collection)) result.add(mapElement(value, action));
        return result;
    }

    private Object mapElement(Object element, BiFunction<AssociationAttribute, Object, Object> action) {
        for (var column : columns) {
            if (column.foreignKey() != null) element = mapPath(element, column, 0, action);
        }
        return element;
    }

    private Object mapPath(Object instance, EntityStatements.Column column, int depth,
            BiFunction<AssociationAttribute, Object, Object> action) {
        if (instance == null) return null;
        ManagedAccess access = column.accesses()[depth];
        int slot = column.path()[depth];
        Object previous = access.get(instance, slot);
        Object value = depth + 1 == column.path().length
            ? previous == null ? null : action.apply(association(column), previous)
            : mapPath(previous, column, depth + 1, action);
        if (value == previous) return instance;
        if (access.type().isRecord()) {
            Object[] state = new Object[access.attributes().size()];
            access.read(instance, state);
            state[slot] = value;
            return access.construct(state);
        }
        access.set(instance, slot, value);
        return instance;
    }
}
