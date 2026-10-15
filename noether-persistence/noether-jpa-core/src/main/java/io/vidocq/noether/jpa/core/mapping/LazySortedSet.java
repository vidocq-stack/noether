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
import java.util.Iterator;
import java.util.NavigableSet;
import java.util.SortedSet;
import java.util.TreeSet;

/** A lazy {@code SortedSet} or {@code NavigableSet}: every method loads the elements, then delegates to them. */
final class LazySortedSet<E> extends LazyBag<E> implements NavigableSet<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    LazySortedSet(Object owner, int attribute, String name, Loader loader) {
        super(owner, attribute, name, loader);
    }

    @Override
    @SuppressWarnings("unchecked") // the loader assembles the elements of the attribute's own element type
    NavigableSet<E> accept(Object value) {
        if (value == null) return new TreeSet<>();
        if (value instanceof NavigableSet<?> set) return (NavigableSet<E>) set;
        throw mismatch(value);
    }

    private NavigableSet<E> set() { return (NavigableSet<E>) loaded(); }

    @Override public Comparator<? super E> comparator() { return set().comparator(); }
    @Override public E first() { return set().first(); }
    @Override public E last() { return set().last(); }
    @Override public E lower(E e) { return set().lower(e); }
    @Override public E floor(E e) { return set().floor(e); }
    @Override public E ceiling(E e) { return set().ceiling(e); }
    @Override public E higher(E e) { return set().higher(e); }
    @Override public E pollFirst() { return set().pollFirst(); }
    @Override public E pollLast() { return set().pollLast(); }
    @Override public NavigableSet<E> descendingSet() { return set().descendingSet(); }
    @Override public Iterator<E> descendingIterator() { return set().descendingIterator(); }
    @Override public NavigableSet<E> subSet(E from, boolean fromInclusive, E to, boolean toInclusive) {
        return set().subSet(from, fromInclusive, to, toInclusive);
    }
    @Override public NavigableSet<E> headSet(E to, boolean inclusive) { return set().headSet(to, inclusive); }
    @Override public NavigableSet<E> tailSet(E from, boolean inclusive) { return set().tailSet(from, inclusive); }
    @Override public SortedSet<E> subSet(E from, E to) { return set().subSet(from, to); }
    @Override public SortedSet<E> headSet(E to) { return set().headSet(to); }
    @Override public SortedSet<E> tailSet(E from) { return set().tailSet(from); }
    @Override public E getFirst() { return set().getFirst(); }
    @Override public E getLast() { return set().getLast(); }
    @Override public E removeFirst() { return set().removeFirst(); }
    @Override public E removeLast() { return set().removeLast(); }
    @Override public NavigableSet<E> reversed() { return set().reversed(); }
}
