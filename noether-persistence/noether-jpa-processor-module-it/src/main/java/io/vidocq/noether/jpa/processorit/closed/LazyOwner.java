/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.processorit.closed;

import jakarta.persistence.*;

@Entity
public class LazyOwner {
    private long id;
    private LazyAccount account;
    public LazyOwner() {}
    public LazyOwner(long id, LazyAccount account) { this.id = id; this.account = account; }
    @Id public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    @ManyToOne(fetch = FetchType.LAZY) public LazyAccount getAccount() { return account; }
    public void setAccount(LazyAccount account) { this.account = account; }
}
