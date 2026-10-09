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

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.List;

/** The inverse side of a one-to-many: the players' team_id column holds it; ordered, orphans removed. */
@Entity
public class Team {
    @Id
    private long id;
    private String name;
    @Version
    private int version;
    @OneToMany(mappedBy = "team", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("name")
    private List<Player> players = new ArrayList<>();

    protected Team() {
    }

    public Team(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public String name() {
        return name;
    }

    public int version() {
        return version;
    }

    public List<Player> players() {
        return players;
    }

    /** Both sides of the relationship, as the application keeps them (§2.10). */
    public Player sign(Player player) {
        players.add(player);
        player.team(this);
        return player;
    }
}
