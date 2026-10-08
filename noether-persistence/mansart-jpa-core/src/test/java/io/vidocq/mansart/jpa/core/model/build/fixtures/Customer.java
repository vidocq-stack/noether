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

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import java.util.UUID;

/** Field access (the @Id is on a field); every kind of basic attribute. */
@Entity
public class Customer {
    @Id
    private Long id;
    private String name;
    @Column(name = "E_MAIL", nullable = false, unique = true, length = 120)
    private String email;
    private int visits;
    private BigDecimal balance;
    @Column(precision = 12, scale = 2)
    private BigDecimal credit;
    private LocalDate since;
    private Date legacy;
    @Temporal(TemporalType.DATE)
    private Date birthDate;
    private UUID token;
    private Level level;
    @Enumerated(EnumType.STRING)
    private Level preferredLevel;
    private Grade grade;
    @Lob
    private String notes;
    private byte[] picture;
    @Version
    private int version;
    @Transient
    private String cache;
    private transient String alsoNotPersistent;
    private static int counter;
    @Basic(optional = false, fetch = FetchType.LAZY)
    private String biography;

    protected Customer() {
    }

    /** A getter that is not a persistent property: field access. */
    public String getDisplayName() {
        return name;
    }
}
