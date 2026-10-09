/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.processorit.closed;

import jakarta.persistence.AssociationOverride;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;

@Entity
public class Portfolio {
    @Id private long id;
    @Embedded
    @AssociationOverride(name = "owner", joinColumns = @JoinColumn(name = "PortfolioOwner"))
    private Profile profile;

    protected Portfolio() {
    }

    public Portfolio(long id, Profile profile) {
        this.id = id;
        this.profile = profile;
    }

    public Profile profile() { return profile; }
}
