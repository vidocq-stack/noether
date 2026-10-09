/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.model.xml.fixtures;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import java.util.ArrayList;
import java.util.List;

@MappedSuperclass
public class LinkBase {
    @Id
    public long id;
    @ManyToOne(optional = false, cascade = CascadeType.PERSIST)
    @JoinColumn(name = "BASE_FK")
    public Excluded target;
    @ManyToMany(cascade = CascadeType.PERSIST)
    @JoinTable(name = "BASE_LINKS")
    public List<Excluded> targets = new ArrayList<>();
}
