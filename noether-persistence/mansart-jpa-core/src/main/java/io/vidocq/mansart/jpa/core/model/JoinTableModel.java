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

import java.util.List;

/**
 * A join table as written (§11.1.27), before its defaults are applied.
 *
 * @param name the table, or {@code null} for the default {@code Owner_Target}
 * @param joinColumns the columns referencing the owner, empty for the defaults
 * @param inverseJoinColumns the columns referencing the target, empty for the defaults
 */
public record JoinTableModel(String name, String schema, String catalog, List<JoinColumnModel> joinColumns,
        List<JoinColumnModel> inverseJoinColumns) {

    public JoinTableModel {
        joinColumns = List.copyOf(joinColumns);
        inverseJoinColumns = List.copyOf(inverseJoinColumns);
    }

    /** A join table whose every element takes its default. */
    public static JoinTableModel defaults() {
        return new JoinTableModel(null, null, null, List.of(), List.of());
    }
}
