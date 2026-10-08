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

/** Property access (the @Id is on a getter), a named table and a renamed entity. */
@Entity(name = "Acct")
@Table(name = "ACCOUNTS", schema = "BANK")
public class Account {
    private long number;
    private String owner;
    private boolean active;
    private String computed;
    private String readOnly;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "acct_seq")
    @SequenceGenerator(name = "acct_seq", sequenceName = "ACCT_SEQ", allocationSize = 20)
    public long getNumber() {
        return number;
    }

    public void setNumber(long number) {
        this.number = number;
    }

    @Column(name = "OWNER_NAME")
    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Transient
    public String getComputed() {
        return computed;
    }

    public void setComputed(String computed) {
        this.computed = computed;
    }

    /** No setter: not a persistent property. */
    public String getReadOnly() {
        return readOnly;
    }
}
