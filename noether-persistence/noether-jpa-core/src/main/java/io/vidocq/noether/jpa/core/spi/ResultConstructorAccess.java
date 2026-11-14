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
package io.vidocq.noether.jpa.core.spi;

import java.util.List;

/**
 * The public constructors of a class a query builds without it being an entity: {@code SELECT NEW} (§4.8.2) and
 * {@code @ConstructorResult} (§3.11.15). {@code noether-jpa-processor} generates one, in the package of the class, for
 * each class a {@code @NamedQuery} or an {@code @SqlResultSetMapping} of its sources names: on the module path, the
 * package then needs no {@code exports}. A class without one is built reflectively.
 */
public abstract class ResultConstructorAccess {

    private final Class<?> type;
    private final List<List<Class<?>>> constructors;

    /**
     * @param type the class the constructors build
     * @param constructors the parameter types of each public constructor, in declaration order
     */
    protected ResultConstructorAccess(Class<?> type, List<List<Class<?>>> constructors) {
        this.type = type;
        this.constructors = constructors.stream().map(List::copyOf).toList();
    }

    /** The class the constructors build. */
    public final Class<?> type() {
        return type;
    }

    /** The parameter types of each public constructor, in declaration order. */
    public final List<List<Class<?>>> constructors() {
        return constructors;
    }

    /**
     * A new instance through public constructor {@code constructor}, from {@code arguments} already converted to its
     * parameter types (a primitive parameter takes its boxed value, never {@code null}).
     */
    public abstract Object construct(int constructor, Object[] arguments);
}
