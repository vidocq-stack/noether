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

import jakarta.persistence.ParameterMode;
import java.util.List;
import java.util.Map;

/** A {@code @NamedStoredProcedureQuery} of a managed class (§10.4.2). */
public record NamedStoredProcedureModel(String name, String procedureName, List<Parameter> parameters, List<Class<?>> resultClasses,
        List<String> resultSetMappings, Map<String, String> hints) {

    public NamedStoredProcedureModel {
        parameters = List.copyOf(parameters);
        resultClasses = List.copyOf(resultClasses);
        resultSetMappings = List.copyOf(resultSetMappings);
        hints = Map.copyOf(hints);
    }

    /** A parameter in its declared order; an empty name is positional. */
    public record Parameter(String name, Class<?> type, ParameterMode mode) {
    }
}
