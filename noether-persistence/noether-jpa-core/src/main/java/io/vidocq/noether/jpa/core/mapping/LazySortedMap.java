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

import java.io.Serial;
import java.util.Comparator;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.SortedMap;
import java.util.TreeMap;

/** A lazy {@code SortedMap} or {@code NavigableMap}: every method loads the entries, then delegates to them. */
final class LazySortedMap<K, V> extends LazyMap<K, V> implements NavigableMap<K, V> {

    @Serial
    private static final long serialVersionUID = 1L;

    LazySortedMap(Object owner, int attribute, String name, Loader loader) {
        super(owner, attribute, name, loader);
    }

    @Override
    @SuppressWarnings("unchecked") // the loader assembles the entries of the attribute's own key and value types
    NavigableMap<K, V> accept(Object value) {
        if (value == null) return new TreeMap<>();
        if (value instanceof NavigableMap<?, ?> map) return (NavigableMap<K, V>) map;
        throw mismatch(value);
    }

    private NavigableMap<K, V> map() { return (NavigableMap<K, V>) loaded(); }

    @Override public Comparator<? super K> comparator() { return map().comparator(); }
    @Override public K firstKey() { return map().firstKey(); }
    @Override public K lastKey() { return map().lastKey(); }
    @Override public Entry<K, V> lowerEntry(K key) { return map().lowerEntry(key); }
    @Override public K lowerKey(K key) { return map().lowerKey(key); }
    @Override public Entry<K, V> floorEntry(K key) { return map().floorEntry(key); }
    @Override public K floorKey(K key) { return map().floorKey(key); }
    @Override public Entry<K, V> ceilingEntry(K key) { return map().ceilingEntry(key); }
    @Override public K ceilingKey(K key) { return map().ceilingKey(key); }
    @Override public Entry<K, V> higherEntry(K key) { return map().higherEntry(key); }
    @Override public K higherKey(K key) { return map().higherKey(key); }
    @Override public Entry<K, V> firstEntry() { return map().firstEntry(); }
    @Override public Entry<K, V> lastEntry() { return map().lastEntry(); }
    @Override public Entry<K, V> pollFirstEntry() { return map().pollFirstEntry(); }
    @Override public Entry<K, V> pollLastEntry() { return map().pollLastEntry(); }
    @Override public NavigableMap<K, V> descendingMap() { return map().descendingMap(); }
    @Override public NavigableSet<K> navigableKeySet() { return map().navigableKeySet(); }
    @Override public NavigableSet<K> descendingKeySet() { return map().descendingKeySet(); }
    @Override public NavigableMap<K, V> subMap(K from, boolean fromInclusive, K to, boolean toInclusive) {
        return map().subMap(from, fromInclusive, to, toInclusive);
    }
    @Override public NavigableMap<K, V> headMap(K to, boolean inclusive) { return map().headMap(to, inclusive); }
    @Override public NavigableMap<K, V> tailMap(K from, boolean inclusive) { return map().tailMap(from, inclusive); }
    @Override public SortedMap<K, V> subMap(K from, K to) { return map().subMap(from, to); }
    @Override public SortedMap<K, V> headMap(K to) { return map().headMap(to); }
    @Override public SortedMap<K, V> tailMap(K from) { return map().tailMap(from); }
}
