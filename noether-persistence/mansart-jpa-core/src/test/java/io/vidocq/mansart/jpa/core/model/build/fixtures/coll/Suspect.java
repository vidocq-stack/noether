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
package io.vidocq.mansart.jpa.core.model.build.fixtures.coll;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Element collections: basic values in the default table Suspect_aliases (Suspect_id, ALIAS) ordered by value,
 * embeddables in a collection table named as written, enums by name in the default table and column.
 */
@Entity
public class Suspect {
    @Id
    private long id;
    private String name;
    @Version
    private int version;
    @ElementCollection
    @Column(name = "ALIAS")
    @OrderBy
    private List<String> aliases = new ArrayList<>();
    @ElementCollection
    @CollectionTable(name = "SUSPECT_SIGHTINGS", joinColumns = @JoinColumn(name = "SUSPECT"))
    @AttributeOverride(name = "city", column = @Column(name = "TOWN"))
    @OrderBy("seen DESC")
    private List<Sighting> sightings = new ArrayList<>();
    @ElementCollection
    @Enumerated(EnumType.STRING)
    private Set<Trait> traits = new HashSet<>();

    protected Suspect() {
    }

    public Suspect(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public int version() {
        return version;
    }

    public List<String> aliases() {
        return aliases;
    }

    public List<Sighting> sightings() {
        return sightings;
    }

    public Set<Trait> traits() {
        return traits;
    }
}
