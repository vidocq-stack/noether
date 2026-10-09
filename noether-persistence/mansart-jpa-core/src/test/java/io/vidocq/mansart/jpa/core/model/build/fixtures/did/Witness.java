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

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;

/** §2.4.1.3 example 1b: the part officerId of the key is the key of the officer (columns name, officer_id). */
@Entity
public class Witness {
    @EmbeddedId
    private WitnessKey key;
    @MapsId("officerId")
    @ManyToOne
    private Officer officer;

    protected Witness() {
    }

    /** The key's officerId is left to the provider, which derives it from the officer. */
    public Witness(String name, Officer officer) {
        this.key = new WitnessKey(name, 0);
        this.officer = officer;
    }

    public WitnessKey key() {
        return key;
    }

    public Officer officer() {
        return officer;
    }
}
