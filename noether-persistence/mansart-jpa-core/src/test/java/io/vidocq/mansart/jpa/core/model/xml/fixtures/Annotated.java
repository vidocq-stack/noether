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
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;

/** Annotated, and partly overridden by the mapping file (§12.1: the XML wins where both say something). */
@Entity(name = "Ann")
@Table(name = "ANN_T")
@NamedQuery(name = "Annotated.byLabel", query = "SELECT a FROM Ann a WHERE a.id = -1")
@NamedQuery(name = "Annotated.all", query = "SELECT a FROM Renamed a ORDER BY a.id")
public class Annotated {
    @Id
    private long id;
    @Column(name = "A_LABEL")
    private String label;
    private String note;
    @Column(name = "KEPT_COLUMN")
    private String kept;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getKept() { return kept; }
    public void setKept(String kept) { this.kept = kept; }
}
