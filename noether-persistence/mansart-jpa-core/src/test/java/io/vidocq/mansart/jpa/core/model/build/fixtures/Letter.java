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
package io.vidocq.mansart.jpa.core.model.build.fixtures;

import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** §11.1.10: conversions named by attribute — on the entity for an inherited one, on an embedded attribute for its own. */
@Entity
@Convert(attributeName = "signature", converter = UpperCaseConverter.class)
public class Letter extends Signed {
    @Id
    private long id;
    private char[] initials;
    @Convert(converter = StrictConverter.class)
    private String subject;
    @Embedded
    @Convert(attributeName = "street", converter = UpperCaseConverter.class)
    @Convert(attributeName = "geo.lat", converter = HalfConverter.class)
    private Postal address;

    protected Letter() {
    }

    public Letter(long id, String signature, String initials, String subject, Postal address) {
        super(signature);
        this.id = id;
        this.initials = initials.toCharArray();
        this.subject = subject;
        this.address = address;
    }

    public String initials() {
        return new String(initials);
    }

    public String subject() {
        return subject;
    }

    public Postal address() {
        return address;
    }
}
