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
package io.vidocq.mansart.jpa.core.access;

import io.vidocq.mansart.jpa.core.model.EmbeddableModel;
import io.vidocq.mansart.jpa.core.model.EntityModel;

/**
 * Reads, writes and creates the instances of one managed class (an entity or an embeddable), without reflection.
 * Each instance is a hidden class generated for that managed class by {@link AccessGenerator}: its accessors are
 * constant method handles, so a call through it is compiled as a direct field or method access.
 *
 * <p>Attributes are designated by their index in the model ({@link EntityModel#attributes()},
 * {@link EmbeddableModel#attributes()}); primitive values are boxed. Exceptions thrown by property accessors propagate
 * unchanged: wrapping them in a {@code PersistenceException} (§2.3.2) is the engine's job.
 */
public abstract class ManagedAccess {

    private final Class<?> type;

    protected ManagedAccess(Class<?> type) {
        this.type = type;
    }

    /** The access of an entity, attributes of its mapped superclasses and entity superclasses included. */
    public static ManagedAccess of(EntityModel entity) {
        return AccessGenerator.generate(entity.javaType(), false, entity.attributes());
    }

    /** The access of an embeddable, as one owner sees it. */
    public static ManagedAccess of(EmbeddableModel embeddable) {
        return AccessGenerator.generate(embeddable.javaType(), embeddable.isRecord(), embeddable.attributes());
    }

    /** The managed class. */
    public final Class<?> type() {
        return type;
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
}
