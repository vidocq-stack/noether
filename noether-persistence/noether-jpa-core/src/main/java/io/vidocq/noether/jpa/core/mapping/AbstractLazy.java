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

import jakarta.persistence.PersistenceException;
import java.io.ObjectStreamException;
import java.io.Serial;
import java.io.Serializable;

/**
 * The state shared by the lazy collections: their owner, the loader of the persistence context, then the loaded
 * elements they delegate to. Serialized loaded, a wrapper is written as its elements; serialized unloaded, it is
 * written without its owner nor loader, and fails on its next access.
 */
abstract class AbstractLazy<D> implements LazyCollection, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int attribute;
    private final String name;
    private final transient Object owner;
    private transient Loader loader;
    private transient boolean loading;
    private transient D delegate;
    private transient Object baseline;

    AbstractLazy(Object owner, int attribute, String name, Loader loader) {
        this.owner = owner;
        this.attribute = attribute;
        this.name = name;
        this.loader = loader;
    }

    /** The elements, loaded on the first call. */
    final D loaded() {
        if (delegate == null) {
            initialize();
        }
        return delegate;
    }

    /** {@code value} as the delegate of this wrapper; {@code null} is an empty collection. */
    abstract D accept(Object value);

    @Override
    public final boolean initialized() {
        return delegate != null;
    }

    @Override
    public final Object owner() {
        return owner;
    }

    @Override
    public final int attribute() {
        return attribute;
    }

    @Override
    public final String attributeName() {
        return name;
    }

    @Override
    public final void initialize() {
        if (delegate != null) {
            return;
        }
        if (loading) {
            throw new PersistenceException("The lazy collection " + describe() + " was accessed while it was being loaded");
        }
        Loader current = loader;
        if (current == null) {
            throw new PersistenceException("The lazy collection " + describe() + " cannot be loaded: its owner is detached"
                + " (cleared, detached, serialized, or its entity manager closed) and it was not loaded before (§3.2.7)");
        }
        loading = true;
        try {
            current.load(this);
        } finally {
            loading = false;
        }
        if (delegate == null) {
            throw new PersistenceException("The lazy collection " + describe() + " was not loaded");
        }
        loader = null;
    }

    @Override
    public final Object baseline() {
        initialize();
        return baseline;
    }

    @Override
    public final void complete(Object elements, Object baseline) {
        if (delegate != null) {
            throw new IllegalStateException("The lazy collection " + describe() + " is already loaded");
        }
        D accepted = accept(elements);
        this.baseline = baseline;
        this.delegate = accepted;
    }

    @Override
    public final void disconnect() {
        loader = null;
    }

    final PersistenceException mismatch(Object value) {
        return new PersistenceException("The lazy collection " + describe() + " cannot hold a "
            + value.getClass().getName());
    }

    private String describe() {
        return (owner == null ? "" : owner.getClass().getSimpleName() + ".") + name;
    }

    @Override
    public final boolean equals(Object other) {
        return other == this || loaded().equals(other);
    }

    @Override
    public final int hashCode() {
        return loaded().hashCode();
    }

    /** Not a data access: an unloaded collection says so instead of loading, or failing, to be printed. */
    @Override
    public final String toString() {
        return delegate == null ? "{unloaded lazy collection " + describe() + "}" : delegate.toString();
    }

    @Serial
    final Object writeReplace() throws ObjectStreamException {
        return delegate == null ? this : delegate;
    }
}
