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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** A lazy {@code Collection}: every method loads the elements, then delegates to them. */
class LazyBag<E> extends AbstractLazy<Collection<E>> implements Collection<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    LazyBag(Object owner, int attribute, String name, Loader loader) {
        super(owner, attribute, name, loader);
    }

    @Override
    @SuppressWarnings("unchecked") // the loader assembles the elements of the attribute's own element type
    Collection<E> accept(Object value) {
        if (value == null) return new ArrayList<>();
        if (value instanceof Collection<?> collection) return (Collection<E>) collection;
        throw mismatch(value);
    }

    @Override public int size() { return loaded().size(); }
    @Override public boolean isEmpty() { return loaded().isEmpty(); }
    @Override public boolean contains(Object o) { return loaded().contains(o); }
    @Override public Iterator<E> iterator() { return loaded().iterator(); }
    @Override public Object[] toArray() { return loaded().toArray(); }
    @Override public <T> T[] toArray(T[] a) { return loaded().toArray(a); }
    @Override public <T> T[] toArray(IntFunction<T[]> generator) { return loaded().toArray(generator); }
    @Override public boolean add(E e) { return loaded().add(e); }
    @Override public boolean remove(Object o) { return loaded().remove(o); }
    @Override public boolean containsAll(Collection<?> c) { return loaded().containsAll(c); }
    @Override public boolean addAll(Collection<? extends E> c) { return loaded().addAll(c); }
    @Override public boolean removeAll(Collection<?> c) { return loaded().removeAll(c); }
    @Override public boolean retainAll(Collection<?> c) { return loaded().retainAll(c); }
    @Override public boolean removeIf(Predicate<? super E> filter) { return loaded().removeIf(filter); }
    @Override public void clear() { loaded().clear(); }
    @Override public void forEach(Consumer<? super E> action) { loaded().forEach(action); }
    @Override public Spliterator<E> spliterator() { return loaded().spliterator(); }
    @Override public Stream<E> stream() { return loaded().stream(); }
    @Override public Stream<E> parallelStream() { return loaded().parallelStream(); }
}
