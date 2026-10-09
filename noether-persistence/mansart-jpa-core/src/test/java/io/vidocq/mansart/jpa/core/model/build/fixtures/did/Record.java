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
package io.vidocq.mansart.jpa.core.model.build.fixtures.did;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;

/**
 * §2.4.1.3 example 5a: the dependent shares the id class of its parent, its only @Id being the relationship (columns
 * FIRST, LAST).
 */
@Entity(name = "CriminalRecord")
@IdClass(SuspectId.class)
public class Record {
    @Id
    @OneToOne
    @JoinColumn(name = "FIRST", referencedColumnName = "first")
    @JoinColumn(name = "LAST", referencedColumnName = "last")
    private Profile profile;
    private int convictions;

    protected Record() {
    }

    public Record(Profile profile, int convictions) {
        this.profile = profile;
        this.convictions = convictions;
    }

    public Profile profile() {
        return profile;
    }

    public int convictions() {
        return convictions;
    }
}
