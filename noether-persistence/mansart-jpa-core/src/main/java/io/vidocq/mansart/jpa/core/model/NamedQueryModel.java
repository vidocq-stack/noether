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
package io.vidocq.mansart.jpa.core.model;

import java.util.Map;

/**
 * A named query a managed class declares (§10.4.1 {@code @NamedQuery}, {@code @NamedNativeQuery}): names are global to
 * the persistence unit.
 *
 * @param nativeQuery whether it is SQL rather than the query language
 * @param resultClass its {@code resultClass}, or {@code null}
 * @param lockMode the name of its {@code LockModeType} ({@code NONE} for a native query)
 * @param resultSetMapping the {@code resultSetMapping} of a native query, or {@code null}
 */
public record NamedQueryModel(String name, String query, boolean nativeQuery, Class<?> resultClass, String lockMode,
        Map<String, String> hints, String resultSetMapping) {

    public NamedQueryModel {
        hints = Map.copyOf(hints);
    }
}
