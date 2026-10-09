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
package io.vidocq.mansart.jpa.core.model.build.fixtures.rel;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import java.util.LinkedHashSet;
import java.util.Set;

/** The owning side of a many-to-one and of a bidirectional many-to-many with the default join table. */
@Entity
public class Player {
    @Id
    private long id;
    private String name;
    @ManyToOne
    private Team team;
    @ManyToMany
    private Set<Skill> skills = new LinkedHashSet<>();

    protected Player() {
    }

    public Player(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Team team() {
        return team;
    }

    public void team(Team team) {
        this.team = team;
    }

    public Set<Skill> skills() {
        return skills;
    }
}
