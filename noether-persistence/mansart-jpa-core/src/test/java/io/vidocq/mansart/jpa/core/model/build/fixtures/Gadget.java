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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.SecondaryTables;

/** A secondary table joined by its own key column, and one joined by the default (the primary key column). */
@Entity
@SecondaryTables({
    @SecondaryTable(name = "GADGET_DETAILS", pkJoinColumns = @PrimaryKeyJoinColumn(name = "GADGET_ID")),
    @SecondaryTable(name = "GADGET_NOTES")
})
public class Gadget {
    @Id
    private long id;
    private String name;
    @Column(name = "WAREHOUSE", table = "GADGET_DETAILS")
    private String warehouse;
    @Column(table = "GADGET_NOTES")
    private String note;

    protected Gadget() {
    }

    public Gadget(long id, String name, String warehouse, String note) {
        this.id = id;
        this.name = name;
        this.warehouse = warehouse;
        this.note = note;
    }

    public String warehouse() {
        return warehouse;
    }

    public void warehouse(String warehouse) {
        this.warehouse = warehouse;
    }

    public String note() {
        return note;
    }
}
