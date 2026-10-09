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
import jakarta.persistence.OneToOne;

/** The inverse side of a one-to-one: the employee's DESK_ID column holds it. */
@Entity
public class Desk {
    @Id
    private long id;
    private String location;
    @OneToOne(mappedBy = "desk")
    private Emp emp;

    protected Desk() {
    }

    public Desk(long id, String location) {
        this.id = id;
        this.location = location;
    }

    public long id() {
        return id;
    }

    public String location() {
        return location;
    }

    public Emp emp() {
        return emp;
    }

    public void emp(Emp emp) {
        this.emp = emp;
    }
}
