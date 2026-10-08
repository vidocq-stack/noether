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
package io.vidocq.mansart.jpa.core.model.build.fixtures.callbacks;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;

/** An entity with its own listener and lifecycle methods, one overriding its superclass's. */
@Entity
@EntityListeners(StampListener.class)
public class Record extends Tracked {
    private String text;
    private String stamp;

    protected Record() {
    }

    public Record(long id, String text) {
        this.id = id;
        this.text = text;
    }

    public void text(String text) {
        this.text = text;
    }

    public void stamp(String stamp) {
        this.stamp = stamp;
    }

    public String stamp() {
        return stamp;
    }

    /** Overrides Tracked.onPersist: called once, in the place of the superclass method (§3.6.4). */
    @Override
    @PrePersist
    protected void onPersist() {
        Journal.write("Record.onPersist");
    }

    @PreRemove
    void removing() {
        Journal.write("Record.removing");
    }

    @PostRemove
    void removed() {
        Journal.write("Record.removed");
    }

    @PostUpdate
    void updated() {
        Journal.write("Record.updated");
    }

    @PostLoad
    void loaded() {
        Journal.write("Record.loaded");
    }
}
