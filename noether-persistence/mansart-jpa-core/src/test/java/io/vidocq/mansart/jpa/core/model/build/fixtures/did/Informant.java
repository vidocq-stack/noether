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
import jakarta.persistence.ManyToOne;

/** §2.4.1.3 example 1a: an @IdClass whose part is the relationship to the parent (columns alias, handler_id). */
@Entity
@IdClass(InformantId.class)
public class Informant {
    @Id
    private String alias;
    @Id
    @ManyToOne
    private Officer handler;
    private String tip;

    protected Informant() {
    }

    public Informant(String alias, Officer handler, String tip) {
        this.alias = alias;
        this.handler = handler;
        this.tip = tip;
    }

    public Officer handler() {
        return handler;
    }

    public String tip() {
        return tip;
    }
}
