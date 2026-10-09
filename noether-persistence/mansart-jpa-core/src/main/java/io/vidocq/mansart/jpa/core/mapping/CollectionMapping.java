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
import io.vidocq.mansart.jpa.core.model.AssociationAttribute;
import io.vidocq.mansart.jpa.dialect.sql.Delete;
import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import io.vidocq.mansart.jpa.dialect.sql.Insert;
import io.vidocq.mansart.jpa.dialect.sql.Select;
import io.vidocq.mansart.jpa.dialect.sql.Table;
import jakarta.persistence.PersistenceException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.Vector;

/**
 * How a collection-valued relationship (§2.10, {@code @OneToMany} and {@code @ManyToMany}) reaches the database: the
 * rows of a join table its owner writes, or, on the inverse side, whatever the owning side of the target writes.
 */
public sealed interface CollectionMapping {

    /** The index of the attribute in the attributes of its entity. */
    int attribute();

    AssociationAttribute association();

    /**
     * The owning side of a many-to-many, or of a unidirectional one-to-many (§2.10.5.1): one row per element, the key
     * of the owner then the key of the element, each in the order of {@link EntityStatements#keyValues(Object)}.
     */
    record JoinTable(int attribute, AssociationAttribute association, Table table, List<Identifier> ownerColumns,
            List<ValueBinder> ownerBinders, List<Identifier> targetColumns, List<ValueBinder> targetBinders)
            implements CollectionMapping {

        public JoinTable {
            ownerColumns = List.copyOf(ownerColumns);
            ownerBinders = List.copyOf(ownerBinders);
            targetColumns = List.copyOf(targetColumns);
            targetBinders = List.copyOf(targetBinders);
        }

        /** The row of one element: the owner columns, then the element columns. */
        public Insert insert() {
            List<Identifier> columns = new ArrayList<>(ownerColumns);
            columns.addAll(targetColumns);
            return new Insert(table, columns, null);
        }

        /** The delete of the row of one element: the owner columns, then the element columns. */
        public Delete deleteRow() {
            List<Identifier> conditions = new ArrayList<>(ownerColumns);
            conditions.addAll(targetColumns);
            return new Delete(table, conditions);
        }

        /** The delete of every row of an owner, by its key. */
        public Delete deleteOwner() {
            return new Delete(table, ownerColumns);
        }

        /** The keys of the elements of an owner, by the key of the owner. */
        public Select targets() {
            return new Select(table, targetColumns, ownerColumns);
        }

        /** The keys of the owners of an element, by the key of the element: how the inverse side reads the table. */
        public Select owners() {
            return new Select(table, ownerColumns, targetColumns);
        }
    }

    /**
     * The inverse side, mapped by the attribute {@code mappedBy} of the target (§2.10): read through the foreign key
     * or the join table of that attribute, never written.
     */
    record MappedBy(int attribute, AssociationAttribute association) implements CollectionMapping {
    }

    /** A shape this milestone does not map yet: the collection is neither read nor written. */
    record Unsupported(int attribute, AssociationAttribute association, String feature) implements CollectionMapping {
    }

    /** The elements a collection value holds, in its order: the values of a map; none for {@code null}. */
    static List<Object> elements(Object value) {
        return switch (value) {
            case null -> List.of();
            case Collection<?> collection -> new ArrayList<>(collection);
            case Map<?, ?> map -> new ArrayList<>(map.values());
            default -> throw new IllegalStateException("Not a collection: " + value.getClass().getName());
        };
    }

    /**
     * A new, empty collection for an attribute declared {@code type}: a list for {@code Collection} and {@code List},
     * an insertion-ordered set for {@code Set}, the declared class when it is one of the JDK's.
     */
    static Collection<Object> newCollection(Class<?> type) {
        if (type.isAssignableFrom(ArrayList.class)) {
            return new ArrayList<>();
        }
        if (type.isAssignableFrom(LinkedHashSet.class)) {
            return new LinkedHashSet<>();
        }
        if (type.isAssignableFrom(TreeSet.class)) {
            return new TreeSet<>();
        }
        if (type.isAssignableFrom(Vector.class)) {
            return new Vector<>();
        }
        if (type.isAssignableFrom(LinkedList.class)) {
            return new LinkedList<>();
        }
        throw new PersistenceException("Mansart cannot create a collection of type " + type.getName()
            + ": declare the relationship as a Collection, a List or a Set (§2.2)");
    }
}
