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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/** A lazy {@code Map}: every method loads the entries, then delegates to them. */
class LazyMap<K, V> extends AbstractLazy<Map<K, V>> implements Map<K, V> {

    @Serial
    private static final long serialVersionUID = 1L;

    LazyMap(Object owner, int attribute, String name, Loader loader) {
        super(owner, attribute, name, loader);
    }

    @Override
    @SuppressWarnings("unchecked") // the loader assembles the entries of the attribute's own key and value types
    Map<K, V> accept(Object value) {
        if (value == null) return new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) return (Map<K, V>) map;
        throw mismatch(value);
    }

    @Override public int size() { return loaded().size(); }
    @Override public boolean isEmpty() { return loaded().isEmpty(); }
    @Override public boolean containsKey(Object key) { return loaded().containsKey(key); }
    @Override public boolean containsValue(Object value) { return loaded().containsValue(value); }
    @Override public V get(Object key) { return loaded().get(key); }
    @Override public V put(K key, V value) { return loaded().put(key, value); }
    @Override public V remove(Object key) { return loaded().remove(key); }
    @Override public void putAll(Map<? extends K, ? extends V> m) { loaded().putAll(m); }
    @Override public void clear() { loaded().clear(); }
    @Override public Set<K> keySet() { return loaded().keySet(); }
    @Override public Collection<V> values() { return loaded().values(); }
    @Override public Set<Entry<K, V>> entrySet() { return loaded().entrySet(); }
    @Override public V getOrDefault(Object key, V defaultValue) { return loaded().getOrDefault(key, defaultValue); }
    @Override public void forEach(BiConsumer<? super K, ? super V> action) { loaded().forEach(action); }
    @Override public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) { loaded().replaceAll(function); }
    @Override public V putIfAbsent(K key, V value) { return loaded().putIfAbsent(key, value); }
    @Override public boolean remove(Object key, Object value) { return loaded().remove(key, value); }
    @Override public boolean replace(K key, V oldValue, V newValue) { return loaded().replace(key, oldValue, newValue); }
    @Override public V replace(K key, V value) { return loaded().replace(key, value); }
    @Override public V computeIfAbsent(K key, Function<? super K, ? extends V> f) { return loaded().computeIfAbsent(key, f); }
    @Override public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> f) {
        return loaded().computeIfPresent(key, f);
    }
    @Override public V compute(K key, BiFunction<? super K, ? super V, ? extends V> f) { return loaded().compute(key, f); }
    @Override public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> f) {
        return loaded().merge(key, value, f);
    }
}
