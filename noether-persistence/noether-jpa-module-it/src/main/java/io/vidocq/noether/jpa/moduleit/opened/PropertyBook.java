/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.moduleit.opened;

import jakarta.persistence.*;

@Entity
public class PropertyBook {
    private long id;
    private String title;
    private PropertyBook parent;
    public PropertyBook() {}
    public PropertyBook(long id, String title, PropertyBook parent) {
        this.id = id;
        this.title = title;
        this.parent = parent;
    }
    @Id public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    @ManyToOne(fetch = FetchType.LAZY) public PropertyBook getParent() { return parent; }
    public void setParent(PropertyBook parent) { this.parent = parent; }
}
