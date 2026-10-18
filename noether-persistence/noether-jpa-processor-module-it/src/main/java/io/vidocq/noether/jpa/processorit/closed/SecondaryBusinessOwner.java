/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.processorit.closed;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;

@Entity
public class SecondaryBusinessOwner {
    private long id;
    private SecondaryBusinessCustomer customer;
    private SecondaryBusinessCustomer tuple;

    public SecondaryBusinessOwner() {}
    public SecondaryBusinessOwner(long id, SecondaryBusinessCustomer customer) { this.id = id; this.customer = customer; }

    @Id public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    @ManyToOne(cascade = CascadeType.PERSIST)
    @JoinColumn(referencedColumnName = "code")
    public SecondaryBusinessCustomer getCustomer() { return customer; }
    public void setCustomer(SecondaryBusinessCustomer customer) { this.customer = customer; }

    @ManyToOne(cascade = CascadeType.PERSIST)
    @JoinColumns({@JoinColumn(name = "tuple_revision", referencedColumnName = "revision"),
        @JoinColumn(name = "tuple_code", referencedColumnName = "code")})
    public SecondaryBusinessCustomer getTuple() { return tuple; }
    public void setTuple(SecondaryBusinessCustomer tuple) { this.tuple = tuple; }
}
