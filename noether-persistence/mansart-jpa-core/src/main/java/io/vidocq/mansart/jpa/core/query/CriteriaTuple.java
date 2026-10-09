/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.query;

import jakarta.persistence.Tuple;
import jakarta.persistence.TupleElement;
import jakarta.persistence.criteria.Selection;
import java.util.*;

/** §6.5.11: tuple elements retain selection identity, alias and Java type. */
final class CriteriaTuple implements Tuple {
    private final Object[] values;
    private final List<TupleElement<?>> elements;
    CriteriaTuple(Object[] values,List<Selection<?>> selections) {
        this.values=values.clone(); elements=new ArrayList<>(selections);
    }
    @Override public Object get(int i) {
        if (i<0 || i>=values.length) throw new IllegalArgumentException("Tuple index " + i);
        return values[i];
    }
    @Override public <X> X get(int i,Class<X> type) { return checked(get(i),type); }
    @Override public Object get(String alias) {
        if (alias==null) throw new IllegalArgumentException("Null tuple alias");
        for (int i=0;i<elements.size();i++) if (alias.equals(elements.get(i).getAlias())) return values[i];
        throw new IllegalArgumentException("Unknown tuple alias " + alias);
    }
    @Override public <X> X get(String alias,Class<X> type) { return checked(get(alias),type); }
    @SuppressWarnings("unchecked") // TupleElement<X> fixes the requested result type; identity verifies membership first.
    @Override public <X> X get(TupleElement<X> element) {
        for (int i=0;i<elements.size();i++) if (elements.get(i)==element) return (X) values[i];
        throw new IllegalArgumentException("Unknown tuple element");
    }
    private <X> X checked(Object value,Class<X> type) {
        if (value!=null && !type.isInstance(value)) throw new IllegalArgumentException("Wrong tuple result type");
        return type.cast(value);
    }
    @Override public Object[] toArray() { return values.clone(); }
    @Override public List<TupleElement<?>> getElements() { return new ArrayList<>(elements); }
}
