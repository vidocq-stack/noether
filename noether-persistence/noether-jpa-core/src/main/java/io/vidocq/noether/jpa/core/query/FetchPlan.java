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
package io.vidocq.noether.jpa.core.query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The attributes a query or a find initializes on the instances it returns (§4.4.5.3 fetch joins, §3.7.4 entity
 * graphs), each with the attributes to initialize on its targets in turn: a LAZY collection named here is loaded before
 * the results are returned, so that it stays usable once they are detached.
 */
public final class FetchPlan {

    private final Map<String, FetchPlan> attributes = new LinkedHashMap<>();

    /** The plan of the attribute {@code name}, added if it is not there. */
    public FetchPlan attribute(String name) {
        return attributes.computeIfAbsent(name, _ -> new FetchPlan());
    }

    public Map<String, FetchPlan> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public boolean isEmpty() {
        return attributes.isEmpty();
    }

    /** Adds the attributes of {@code other}, with theirs. */
    public void merge(FetchPlan other) {
        other.attributes.forEach((name, plan) -> attribute(name).merge(plan));
    }
}
