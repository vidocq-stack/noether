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

import java.util.ArrayList;
import java.util.List;

/** No annotation at all: only the mapping file makes it an entity (§12.2). */
public class Plain {
    private long id;
    private String label;
    private int price;
    private Address address;
    private String note;
    private List<String> tags = new ArrayList<>();

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public int getPrice() { return price; }
    public void setPrice(int price) { this.price = price; }
    public Address getAddress() { return address; }
    public void setAddress(Address address) { this.address = address; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
}
