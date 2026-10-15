/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.processorit.closed;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class LazyAccount {
    private long id;
    private String name;
    public LazyAccount() {}
    public LazyAccount(long id, String name) { this.id = id; this.name = name; }
    @Id public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
