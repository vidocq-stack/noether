/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.processorit.closed;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.SecondaryTables;

@Entity
@SecondaryTables(@SecondaryTable(name = "closed_business_details"))
public class SecondaryBusinessCustomer {
    private long id;
    private String code;

    public SecondaryBusinessCustomer() {}
    public SecondaryBusinessCustomer(long id, String code) { this.id = id; this.code = code; }

    @Id public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    @Column(table = "closed_business_details", unique = true, nullable = false, updatable = false)
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}
