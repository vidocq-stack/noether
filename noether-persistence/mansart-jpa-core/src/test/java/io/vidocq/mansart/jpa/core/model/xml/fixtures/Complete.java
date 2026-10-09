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
package io.vidocq.mansart.jpa.core.model.xml.fixtures;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/** Its mapping is metadata-complete (§12.2.3.1): every annotation below is ignored. */
@Entity(name = "Ignored")
@Table(name = "WRONG_T")
public class Complete {
    @Id
    @Column(name = "WRONG_ID")
    private long id;
    @Transient
    private String kept;
    private String dropped;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getKept() { return kept; }
    public void setKept(String kept) { this.kept = kept; }

    @PrePersist
    void ignoredCallback() {
        throw new IllegalStateException("annotations of a metadata-complete class are ignored");
    }
}
