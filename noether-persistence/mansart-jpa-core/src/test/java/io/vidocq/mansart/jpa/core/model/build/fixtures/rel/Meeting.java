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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

/** A foreign key of two columns, named and matched to the target's key columns. */
@Entity
public class Meeting {
    @Id
    private long id;
    private String topic;
    @ManyToOne(optional = false)
    @JoinColumn(name = "BLDG", referencedColumnName = "building")
    @JoinColumn(name = "NUM", referencedColumnName = "number")
    private Room room;

    protected Meeting() {
    }

    public Meeting(long id, String topic, Room room) {
        this.id = id;
        this.topic = topic;
        this.room = room;
    }

    public Room room() {
        return room;
    }

    public void room(Room room) {
        this.room = room;
    }
}
