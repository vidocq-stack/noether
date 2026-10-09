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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.Collection;

/** A unidirectional one-to-many: the default join table Coach_Player (Coach_id, trainees_id), owned and versioned. */
@Entity
public class Coach {
    @Id
    private long id;
    private String name;
    @Version
    private int version;
    @OneToMany
    private Collection<Player> trainees = new ArrayList<>();

    protected Coach() {
    }

    public Coach(long id, String name) {
        this.id = id;
        this.name = name;
    }

    public int version() {
        return version;
    }

    public Collection<Player> trainees() {
        return trainees;
    }

    public void trainees(Collection<Player> trainees) {
        this.trainees = trainees;
    }
}
