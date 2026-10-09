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
package io.vidocq.mansart.jpa.core.query;

import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
class MapOwner {
    @Id
    private long id;
    @ElementCollection
    @CollectionTable(name = "QUERY_MAP", joinColumns = @JoinColumn(name = "OWNER_ID"))
    @MapKeyColumn(name = "MAP_KEY")
    @Column(name = "MAP_VALUE")
    private Map<String, String> entries = new LinkedHashMap<>();

    protected MapOwner() {
    }

    MapOwner(long id, Map<String, String> entries) {
        this.id = id;
        this.entries.putAll(entries);
    }
}
