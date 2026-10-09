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
package io.vidocq.mansart.jpa.core.model.build.fixtures.idx;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.MapKey;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Inverse one-to-many relationships, indexed: by an attribute of the agents (no column), by a key column and by a
 * position column that both live in the agents' table and that this side writes.
 */
@Entity
public class Bureau {
    @Id
    private long id;
    private String name;
    @Version
    private int version;
    @OneToMany(mappedBy = "bureau")
    @MapKey(name = "name")
    private Map<String, Agent> byName = new HashMap<>();
    @OneToMany(mappedBy = "bureau")
    @MapKeyColumn(name = "DESK")
    private Map<String, Agent> byDesk = new HashMap<>();
    @OneToMany(mappedBy = "bureau")
    @OrderColumn
    private List<Agent> roster = new ArrayList<>();

    protected Bureau() {
    }

    public Bureau(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public int version() {
        return version;
    }

    public Map<String, Agent> byName() {
        return byName;
    }

    public Map<String, Agent> byDesk() {
        return byDesk;
    }

    public List<Agent> roster() {
        return roster;
    }

    /** Both sides, as the application keeps them (§2.10). */
    public Agent hire(Agent agent, String desk) {
        agent.bureau(this);
        byName.put(agent.name(), agent);
        byDesk.put(desk, agent);
        roster.add(agent);
        return agent;
    }
}
