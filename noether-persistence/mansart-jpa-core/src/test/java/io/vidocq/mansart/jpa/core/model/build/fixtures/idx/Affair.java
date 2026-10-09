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

import io.vidocq.mansart.jpa.core.model.build.fixtures.coll.Sighting;
import io.vidocq.mansart.jpa.core.model.build.fixtures.coll.Trait;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.MapKeyJoinColumn;
import jakarta.persistence.OrderColumn;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Indexed collections of the owning side: an ordered many-to-many (position in its join table), a many-to-many keyed by
 * an entity (a key column in its join table), an ordered element collection, element collections keyed by a column
 * and by an enum.
 */
@Entity
public class Affair {
    @Id
    private long id;
    @ManyToMany
    @OrderColumn
    private List<Agent> team = new ArrayList<>();
    @ManyToMany
    @JoinTable(name = "AFFAIR_CONTACTS")
    @MapKeyJoinColumn(name = "BUREAU")
    private Map<Bureau, Agent> contacts = new HashMap<>();
    @ElementCollection
    @OrderColumn
    private List<String> clues = new ArrayList<>();
    @ElementCollection
    @MapKeyColumn(name = "PLACE")
    private Map<String, Sighting> sightings = new HashMap<>();
    @ElementCollection
    @MapKeyEnumerated(EnumType.STRING)
    private Map<Trait, String> notes = new HashMap<>();

    protected Affair() {
    }

    public Affair(long id) {
        this.id = id;
    }

    public List<Agent> team() {
        return team;
    }

    public Map<Bureau, Agent> contacts() {
        return contacts;
    }

    public List<String> clues() {
        return clues;
    }

    public Map<String, Sighting> sightings() {
        return sightings;
    }

    public Map<Trait, String> notes() {
        return notes;
    }
}
