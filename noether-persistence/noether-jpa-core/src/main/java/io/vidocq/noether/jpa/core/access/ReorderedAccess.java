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
package io.vidocq.noether.jpa.core.access;

import io.vidocq.noether.jpa.core.spi.ManagedAccess;
import java.util.List;

/**
 * The access of a record whose components come in another order than the attributes its owner lists: a record
 * {@code @IdClass} (§2.4.1) whose components do not follow the identifier attributes of the entity. It lists the
 * attributes in the owner's order and reaches each through the access of the record, which lists them in component
 * order, as its canonical constructor takes them (BUG-20261114-05).
 */
final class ReorderedAccess extends ManagedAccess {

    private final ManagedAccess record;
    private final int[] component;

    /**
     * @param record the access of the record, its attributes in component order
     * @param attributes the attributes in the owner's order, as {@code name:FIELD} or {@code name:PROPERTY}
     * @param component for each attribute, its index among the components
     */
    ReorderedAccess(ManagedAccess record, List<String> attributes, int[] component) {
        super(record.type(), attributes);
        this.record = record;
        this.component = component.clone();
    }

    @Override
    public Object construct(Object... components) {
        Object[] ordered = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            ordered[component[i]] = components[i];
        }
        return record.construct(ordered);
    }

    @Override
    public Object get(Object instance, int attribute) {
        return record.get(instance, component[attribute]);
    }
}
