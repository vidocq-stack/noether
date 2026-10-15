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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;

/**
 * A LAZY collection-valued attribute not loaded with its owner (§2.10, §11.1.26): a {@code Collection}, {@code List},
 * {@code Set}, {@code SortedSet}, {@code NavigableSet}, {@code Map}, {@code SortedMap} or {@code NavigableMap} that
 * loads its elements on its first access, through the {@link Loader} of the persistence context that manages its
 * owner. Written out, not proxied: no reflection, no generated subclass of the entity.
 *
 * <p>Until it is loaded, the snapshot of its owner holds the wrapper itself; the flush, the dirty checking, the merge
 * and the shared cache recognise it and leave it alone. Once loaded, it is a plain collection of the declared type, and
 * keeps working when its owner is detached. Unloaded, it fails with a {@link jakarta.persistence.PersistenceException}
 * once its owner is no longer managed: never silently empty. Not thread-safe, as the entities are not.
 */
public interface LazyCollection {

    /** Loads a collection: computes its elements, then hands them over through {@link #complete}. */
    @FunctionalInterface
    interface Loader {
        void load(LazyCollection collection);
    }

    /** Whether the elements are loaded. */
    boolean initialized();

    /** The entity the collection belongs to; {@code null} once serialized. */
    Object owner();

    /** The index of the attribute in the state of its owner. */
    int attribute();

    /** The name of the attribute, for the messages. */
    String attributeName();

    /** Loads the elements, if not done yet. */
    void initialize();

    /** The snapshot of the elements as they were loaded; loads them first if needed. */
    Object baseline();

    /** Hands the loaded elements over, with their snapshot. Called once, by the {@link Loader}. */
    void complete(Object elements, Object baseline);

    /** Forgets the loader: an unloaded collection fails from now on (§3.2.7, detached owner). */
    void disconnect();

    /** Whether {@code value} is a lazy collection not loaded yet. */
    static boolean unloaded(Object value) {
        return value instanceof LazyCollection lazy && !lazy.initialized();
    }

    /**
     * An unloaded collection for an attribute declared {@code type}, or {@code null} when the declared type is not one
     * of the interfaces a wrapper can be assigned to (a concrete class such as {@code ArrayList}): that attribute is
     * loaded with its owner.
     */
    static LazyCollection of(Class<?> type, Object owner, int attribute, String name, Loader loader) {
        if (type == Collection.class) return new LazyBag<>(owner, attribute, name, loader);
        if (type == List.class) return new LazyList<>(owner, attribute, name, loader);
        if (type == Set.class) return new LazySet<>(owner, attribute, name, loader);
        if (type == SortedSet.class || type == NavigableSet.class) return new LazySortedSet<>(owner, attribute, name, loader);
        if (type == Map.class) return new LazyMap<>(owner, attribute, name, loader);
        if (type == SortedMap.class || type == NavigableMap.class) return new LazySortedMap<>(owner, attribute, name, loader);
        return null;
    }

    /** Whether an attribute declared {@code type} can hold a lazy collection. */
    static boolean supports(Class<?> type) {
        return type == Collection.class || type == List.class || type == Set.class || type == SortedSet.class
            || type == NavigableSet.class || type == Map.class || type == SortedMap.class || type == NavigableMap.class;
    }
}
