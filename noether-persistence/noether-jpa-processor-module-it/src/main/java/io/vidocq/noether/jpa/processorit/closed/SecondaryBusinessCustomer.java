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
import jakarta.persistence.UniqueConstraint;

@Entity
@SecondaryTables(@SecondaryTable(name = "closed_business_details",
    uniqueConstraints = @UniqueConstraint(columnNames = {"code", "revision"})))
public class SecondaryBusinessCustomer {
    private long id;
    private String code;
    private int revision;

    public SecondaryBusinessCustomer() {}
    public SecondaryBusinessCustomer(long id, String code) { this.id = id; this.code = code; }

    @Id public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    @Column(table = "closed_business_details", unique = true, nullable = false, updatable = false)
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    @Column(table = "closed_business_details", nullable = false, updatable = false)
    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }
}
