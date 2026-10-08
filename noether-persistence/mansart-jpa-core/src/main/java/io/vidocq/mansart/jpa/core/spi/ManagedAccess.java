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
package io.vidocq.mansart.jpa.core.spi;

import java.util.List;

/**
 * Reads, writes and creates the instances of one managed class (an entity or an embeddable), without reflection.
 *
 * <p>Mansart generates the subclasses: at build time with {@code mansart-jpa-processor} (ordinary classes in the
 * package of the managed class, handed over by a {@link ManagedAccessProvider}), or at bootstrap as hidden classes of
 * the provider module (which then needs the package opened to it). Applications never implement it.
 *
 * <p>Attributes are designated by their index in {@link #attributes()}, which lists them as the entity model of the
 * provider does: {@code name:FIELD} or {@code name:PROPERTY}, the attributes of superclasses first. Primitive values are
 * boxed. Exceptions thrown by property accessors propagate unchanged.
 */
public abstract class ManagedAccess {

    private final Class<?> type;
    private final List<String> attributes;

    /**
     * @param type the managed class
     * @param attributes the attributes, as {@code name:FIELD} or {@code name:PROPERTY}, in index order
     */
    protected ManagedAccess(Class<?> type, List<String> attributes) {
        this.type = type;
        this.attributes = List.copyOf(attributes);
    }

    /** The managed class. */
    public final Class<?> type() {
        return type;
    }

    /** The attributes reached by index, as {@code name:FIELD} or {@code name:PROPERTY}. */
    public final List<String> attributes() {
        return attributes;
    }

    /** A new instance, through the no-arg constructor (§2.1); a record is built with {@link #construct}. */
    public Object instantiate() {
        throw new UnsupportedOperationException(type.getName() + " is not instantiated without its components");
    }

    /** A new record, through its canonical constructor, from its components in attribute order. */
    public Object construct(Object... components) {
        throw new UnsupportedOperationException(type.getName() + " is not a record");
    }

    /** The value of an attribute. */
    public abstract Object get(Object instance, int attribute);

    /** Writes an attribute; a record is immutable. */
    public void set(Object instance, int attribute, Object value) {
        throw new UnsupportedOperationException(type.getName() + " is a record, its components cannot be written");
    }

    /**
     * Rethrows {@code failure}, checked or not, unchanged: generated code calls method handles, which declare
     * {@code Throwable}, and must not wrap what an accessor threw.
     */
    protected static RuntimeException rethrow(Throwable failure) {
        throw ManagedAccess.<RuntimeException>sneaky(failure);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> E sneaky(Throwable failure) throws E {
        throw (E) failure;
    }
}
