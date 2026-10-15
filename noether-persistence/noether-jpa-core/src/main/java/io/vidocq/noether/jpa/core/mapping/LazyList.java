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
import java.util.Comparator;
import java.util.List;
import java.util.ListIterator;
import java.util.function.UnaryOperator;

/** A lazy {@code List}: every method loads the elements, then delegates to them. */
final class LazyList<E> extends LazyBag<E> implements List<E> {

    @Serial
    private static final long serialVersionUID = 1L;

    LazyList(Object owner, int attribute, String name, Loader loader) {
        super(owner, attribute, name, loader);
    }

    @Override
    @SuppressWarnings("unchecked") // the loader assembles the elements of the attribute's own element type
    List<E> accept(Object value) {
        if (value == null) return new ArrayList<>();
        if (value instanceof List<?> list) return (List<E>) list;
        throw mismatch(value);
    }

    private List<E> list() { return (List<E>) loaded(); }

    @Override public boolean addAll(int index, Collection<? extends E> c) { return list().addAll(index, c); }
    @Override public E get(int index) { return list().get(index); }
    @Override public E set(int index, E element) { return list().set(index, element); }
    @Override public void add(int index, E element) { list().add(index, element); }
    @Override public E remove(int index) { return list().remove(index); }
    @Override public int indexOf(Object o) { return list().indexOf(o); }
    @Override public int lastIndexOf(Object o) { return list().lastIndexOf(o); }
    @Override public ListIterator<E> listIterator() { return list().listIterator(); }
    @Override public ListIterator<E> listIterator(int index) { return list().listIterator(index); }
    @Override public List<E> subList(int fromIndex, int toIndex) { return list().subList(fromIndex, toIndex); }
    @Override public void replaceAll(UnaryOperator<E> operator) { list().replaceAll(operator); }
    @Override public void sort(Comparator<? super E> c) { list().sort(c); }
    @Override public void addFirst(E e) { list().addFirst(e); }
    @Override public void addLast(E e) { list().addLast(e); }
    @Override public E getFirst() { return list().getFirst(); }
    @Override public E getLast() { return list().getLast(); }
    @Override public E removeFirst() { return list().removeFirst(); }
    @Override public E removeLast() { return list().removeLast(); }
    @Override public List<E> reversed() { return list().reversed(); }
}
